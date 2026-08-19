from __future__ import annotations

import argparse
from dataclasses import dataclass
import math
from pathlib import Path
from typing import Any

import numpy as np

from .best_evaluation import promote_best_evaluation
from .configuration import BridgeConnectionConfig
from .evaluation import EvaluationScenario, evaluation_contract, held_out_point_goals
from .metrics import write_csv, write_json
from .training_metadata import load_resume_metadata, resume_metadata_candidates, validate_metadata_compatibility
from .vec_env import MinecraftMachinesVecEnv


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--morphology", default="minecraft_machines:duopod")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--episodes", type=int, default=0, help="number of held-out scenarios to run; 0 runs the full manifest")
    parser.add_argument("--max-steps", type=int, default=256)
    parser.add_argument("--output", type=Path, default=Path("evaluation.json"))
    parser.add_argument("--allow-missing-metadata", action="store_true", help="evaluate legacy models that do not have a metadata sidecar")
    parser.add_argument("--best-model-output", type=Path, help="copy the evaluated model here when this evaluation beats the existing best evaluation")
    parser.add_argument("--best-evaluation-output", type=Path, help="evaluation JSON path paired with --best-model-output; defaults beside the best model")
    args = parser.parse_args(argv)
    if args.best_evaluation_output is not None and args.best_model_output is None:
        raise ValueError("--best-evaluation-output requires --best-model-output")
    if args.max_steps <= 0:
        raise ValueError("--max-steps must be positive")
    if args.episodes < 0:
        raise ValueError("--episodes must be non-negative")

    env = MinecraftMachinesVecEnv(BridgeConnectionConfig(args.host, args.port, args.token, args.morphology))
    try:
        policy_metadata, policy_metadata_path = _load_and_validate_policy_metadata(
            args.model,
            morphology_id=args.morphology,
            specs=env.client.specs or {},
            allow_missing=args.allow_missing_metadata,
        )
        from stable_baselines3 import PPO

        model = PPO.load(args.model, env=env)
        manifest = held_out_point_goals()
        scenarios = list(manifest.scenarios)
        if args.episodes > 0:
            scenarios = [scenarios[index % len(scenarios)] for index in range(args.episodes)]
        rows = []
        for start in range(0, len(scenarios), env.num_envs):
            chunk = scenarios[start:start + env.num_envs]
            padded = chunk + [chunk[-1]] * (env.num_envs - len(chunk))
            rows.extend(_evaluate_chunk(env, model, padded, len(chunk), args.max_steps))
    finally:
        env.close()
    summary = {
        "evaluation_valid": True,
        "episodes": len(rows),
        "mean_return": float(np.mean([row["return"] for row in rows])) if rows else 0.0,
        "worst_return": float(np.min([row["return"] for row in rows])) if rows else 0.0,
        "success_rate": float(np.mean([row["success"] for row in rows])) if rows else 0.0,
        "mean_final_distance": float(np.mean([row["final_distance_to_target"] for row in rows])) if rows else 0.0,
        "failure_rate": float(np.mean([row["termination_reason"] == "MACHINE_FAILURE" for row in rows])) if rows else 0.0,
        "mean_distance_travelled_blocks": _mean(rows, "distance_travelled_blocks"),
        "mean_path_directness": _mean(rows, "path_directness"),
        "mean_servo_load": _mean(rows, "mean_servo_load"),
        "peak_servo_load": _max(rows, "peak_servo_load"),
        "mean_forward_command_error": _mean(rows, "mean_forward_command_error"),
        "mean_yaw_command_error": _mean(rows, "mean_yaw_command_error"),
        "mean_time_to_target_steps": _mean_present(rows, "time_to_target_steps"),
    }
    payload = {
        "policy": _policy_record(args.model, policy_metadata, policy_metadata_path),
        "evaluation_contract": evaluation_contract(),
        "scenario_manifest": manifest.to_dict(),
        "summary": summary,
        "episodes": rows,
    }
    if args.best_model_output is not None:
        _require_complete_manifest_for_promotion(
            requested_episodes=args.episodes,
            evaluated_episodes=len(rows),
            manifest_episodes=len(manifest.scenarios),
        )
        payload["best_checkpoint"] = promote_best_evaluation(
            model_path=args.model,
            model_metadata=policy_metadata,
            evaluation_payload=payload,
            best_model_path=args.best_model_output,
            best_evaluation_path=args.best_evaluation_output,
        )
    write_json(args.output, payload)
    write_csv(args.output.with_suffix(".csv"), rows)
    return 0


def _load_and_validate_policy_metadata(
    model_path: Path,
    *,
    morphology_id: str,
    specs: dict[str, Any],
    allow_missing: bool,
) -> tuple[dict[str, Any] | None, Path | None]:
    metadata, metadata_path = load_resume_metadata(model_path)
    if metadata is None:
        if allow_missing:
            return None, None
        candidates = ", ".join(str(path) for path in resume_metadata_candidates(model_path))
        raise ValueError(f"missing model metadata for {model_path}; expected one of: {candidates}")
    validate_metadata_compatibility(metadata, morphology_id=morphology_id, specs=specs)
    return metadata, metadata_path


def _policy_record(model_path: Path, metadata: dict[str, Any] | None, metadata_path: Path | None) -> dict[str, Any]:
    return {
        "model_path": str(model_path),
        "metadata_path": str(metadata_path) if metadata_path is not None else None,
        "metadata": metadata,
    }


def _evaluate_chunk(
    env: MinecraftMachinesVecEnv,
    model,
    scenarios: list[EvaluationScenario],
    active_count: int,
    max_steps: int,
) -> list[dict]:
    if max_steps <= 0:
        raise ValueError("max_steps must be positive")
    targets = [scenario.target_offset() for scenario in scenarios]
    env.set_targets(targets)
    obs = env.reset()
    returns = np.zeros(env.num_envs, dtype=np.float64)
    action_total_variation = np.zeros(env.num_envs, dtype=np.float64)
    previous_actions = np.zeros((env.num_envs,) + env.action_space.shape, dtype=np.float64)
    completed = np.zeros(env.num_envs, dtype=bool)
    rows: list[dict] = []
    last_infos = [dict(info) for info in env.reset_infos]
    accumulators = [EvaluationMetricAccumulator(scenario) for scenario in scenarios]
    for index, info in enumerate(last_infos):
        accumulators[index].reset(info)

    for step_index in range(1, max_steps + 1):
        actions, _ = model.predict(obs, deterministic=True)
        actions = np.asarray(actions, dtype=np.float64)
        action_total_variation += np.sum(np.abs(actions - previous_actions), axis=1)
        previous_actions = actions
        obs, rewards, dones, infos = env.step(actions)
        returns += rewards
        last_infos = [dict(info) for info in infos]
        for index in range(active_count):
            if not completed[index]:
                accumulators[index].observe(infos[index])
            if not completed[index] and dones[index]:
                rows.append(_row(scenarios[index], index, step_index, returns[index], action_total_variation[index], infos[index], accumulators[index], timed_out=False))
                completed[index] = True
        if np.all(completed[:active_count]):
            break

    for index in range(active_count):
        if not completed[index]:
            rows.append(_row(scenarios[index], index, max_steps, returns[index], action_total_variation[index], last_infos[index], accumulators[index], timed_out=True))
    return rows


def _row(
    scenario: EvaluationScenario,
    slot: int,
    steps: int,
    episode_return: float,
    action_total_variation: float,
    info: dict,
    accumulator: "EvaluationMetricAccumulator",
    *,
    timed_out: bool,
) -> dict:
    if steps <= 0:
        raise ValueError("evaluation row steps must be positive")
    success = bool(info.get("success", False)) and not timed_out
    final_distance = _finite_number(info.get("distance_to_target"), "distance_to_target")
    episode_return = _finite_number(episode_return, "return")
    action_total_variation = _finite_number(action_total_variation, "action_total_variation")
    row = {
        **accumulator.row_fields(final_distance),
        "time_to_target_steps": steps if success else None,
    }
    return {
        "scenario_id": scenario.id,
        "slot": slot,
        "target_bearing_degrees": scenario.target_bearing_degrees,
        "target_distance_blocks": scenario.target_distance_blocks,
        "initial_yaw_degrees": scenario.initial_yaw_degrees,
        "terrain_stage": scenario.terrain_stage,
        "steps": steps,
        "return": episode_return,
        "success": success,
        "termination_reason": "EVALUATION_STEP_LIMIT" if timed_out else str(info.get("termination_reason", "NONE")),
        "final_distance_to_target": final_distance,
        "action_total_variation": action_total_variation,
        **row,
    }


@dataclass
class EvaluationMetricAccumulator:
    scenario: EvaluationScenario
    distance_travelled_blocks: float = 0.0
    forward_command_abs_error: float = 0.0
    yaw_command_abs_error: float = 0.0
    servo_load_sum: float = 0.0
    peak_servo_load: float = 0.0
    samples: int = 0
    initial_distance_to_target: float | None = None
    _previous_x: float | None = None
    _previous_z: float | None = None

    def reset(self, info: dict[str, Any]) -> None:
        measured_distance = _finite_number(info.get("distance_to_target"), "distance_to_target")
        if measured_distance < 0.0:
            raise ValueError(
                f"evaluation reset distance must be non-negative for scenario {self.scenario.id}: "
                f"{measured_distance}"
            )
        if abs(measured_distance - self.scenario.target_distance_blocks) > 1.0e-4:
            raise ValueError(
                "evaluation reset distance does not match manifest: "
                f"scenario={self.scenario.id}, measured={measured_distance}, "
                f"expected={self.scenario.target_distance_blocks}"
            )
        self.initial_distance_to_target = measured_distance
        self._previous_x, self._previous_z = _position(info)

    def observe(self, info: dict[str, Any]) -> None:
        x, z = _position(info)
        if self._previous_x is None or self._previous_z is None:
            raise ValueError(f"evaluation accumulator has no reset position for scenario {self.scenario.id}")
        self.distance_travelled_blocks += math.hypot(x - self._previous_x, z - self._previous_z)
        self._previous_x = x
        self._previous_z = z

        self.forward_command_abs_error += abs(_number(info, "local_forward_velocity") - _number(info, "desired_forward_velocity"))
        self.yaw_command_abs_error += abs(_number(info, "local_yaw_rate") - _number(info, "desired_yaw_rate"))
        self.servo_load_sum += abs(_number(info, "mean_servo_load"))
        self.peak_servo_load = max(self.peak_servo_load, abs(_number(info, "peak_servo_load")))
        self.samples += 1

    def row_fields(self, final_distance_to_target: float) -> dict[str, float]:
        if self.initial_distance_to_target is None:
            raise ValueError(f"evaluation accumulator was not reset for scenario {self.scenario.id}")
        if not math.isfinite(final_distance_to_target) or final_distance_to_target < 0.0:
            raise ValueError(
                f"invalid final distance for scenario {self.scenario.id}: {final_distance_to_target}"
            )
        progress = self.initial_distance_to_target - final_distance_to_target
        if progress > self.distance_travelled_blocks + 1.0e-4:
            raise ValueError(
                "evaluation geometry invariant failed: "
                f"scenario={self.scenario.id}, progress={progress}, "
                f"path_length={self.distance_travelled_blocks}"
            )
        if self.distance_travelled_blocks <= 1.0e-9:
            path_directness = 0.0
        else:
            path_directness = max(0.0, min(1.0, progress / self.distance_travelled_blocks))
        return {
            "initial_distance_to_target": float(self.initial_distance_to_target),
            "progress_to_target_blocks": float(progress),
            "distance_travelled_blocks": float(self.distance_travelled_blocks),
            "path_directness": float(path_directness),
            "mean_servo_load": float(self.servo_load_sum / self.samples) if self.samples else 0.0,
            "peak_servo_load": float(self.peak_servo_load),
            "mean_forward_command_error": float(self.forward_command_abs_error / self.samples) if self.samples else 0.0,
            "mean_yaw_command_error": float(self.yaw_command_abs_error / self.samples) if self.samples else 0.0,
        }


def _position(info: dict[str, Any]) -> tuple[float, float]:
    return (
        _finite_number(info.get("base_position_x"), "base_position_x"),
        _finite_number(info.get("base_position_z"), "base_position_z"),
    )


def _number(info: dict[str, Any], key: str) -> float:
    value = info.get(key, 0.0)
    try:
        result = float(value)
    except (TypeError, ValueError):
        return 0.0
    return result if math.isfinite(result) else 0.0


def _finite_number(value: Any, field: str) -> float:
    if isinstance(value, bool):
        raise ValueError(f"evaluation field {field} is not numeric: {value!r}")
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"evaluation field {field} is not numeric: {value!r}") from exc
    if not math.isfinite(result):
        raise ValueError(f"evaluation field {field} is not finite: {value!r}")
    return result


def _require_complete_manifest_for_promotion(
    *,
    requested_episodes: int,
    evaluated_episodes: int,
    manifest_episodes: int,
) -> None:
    if requested_episodes != 0 or evaluated_episodes != manifest_episodes:
        raise ValueError(
            "best-checkpoint promotion requires the complete held-out manifest; "
            "omit --episodes and evaluate every scenario"
        )


def _mean(rows: list[dict], key: str) -> float:
    return float(np.mean([row[key] for row in rows])) if rows else 0.0


def _max(rows: list[dict], key: str) -> float:
    return float(np.max([row[key] for row in rows])) if rows else 0.0


def _mean_present(rows: list[dict], key: str) -> float | None:
    values = [row[key] for row in rows if row.get(key) is not None]
    return float(np.mean(values)) if values else None


if __name__ == "__main__":
    raise SystemExit(main())
