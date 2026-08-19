from __future__ import annotations

import argparse
import math
from pathlib import Path
from typing import Any, Sequence

import numpy as np

from .configuration import BridgeConnectionConfig
from .evaluate import _load_and_validate_policy_metadata, _policy_record
from .metrics import write_csv, write_json
from .vec_env import MinecraftMachinesVecEnv


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--morphology", default="minecraft_machines:duopod")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("target_change_validation.json"))
    parser.add_argument("--max-steps", type=int, default=96)
    parser.add_argument("--switch-step", type=int, default=32)
    parser.add_argument("--initial-forward", type=float, default=6.0)
    parser.add_argument("--initial-right", type=float, default=-3.0)
    parser.add_argument("--updated-forward", type=float, default=6.0)
    parser.add_argument("--updated-right", type=float, default=3.0)
    parser.add_argument("--allow-missing-metadata", action="store_true", help="validate legacy models that do not have a metadata sidecar")
    args = parser.parse_args(argv)
    if args.switch_step < 1 or args.switch_step >= args.max_steps:
        raise ValueError("--switch-step must be at least 1 and less than --max-steps")

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
        payload = run_target_change_validation(
            env,
            model,
            initial_target=(args.initial_forward, args.initial_right),
            updated_target=(args.updated_forward, args.updated_right),
            switch_step=args.switch_step,
            max_steps=args.max_steps,
        )
    finally:
        env.close()

    payload["policy"] = _policy_record(args.model, policy_metadata, policy_metadata_path)
    write_json(args.output, payload)
    write_csv(args.output.with_suffix(".csv"), payload["samples"])
    return 0


def run_target_change_validation(
    env: MinecraftMachinesVecEnv,
    model,
    *,
    initial_target: tuple[float, float],
    updated_target: tuple[float, float],
    switch_step: int,
    max_steps: int,
) -> dict[str, Any]:
    if switch_step < 1 or switch_step >= max_steps:
        raise ValueError("switch_step must be at least 1 and less than max_steps")
    initial_targets = _repeat_target(initial_target, env.num_envs)
    updated_targets = _repeat_target(updated_target, env.num_envs)

    env.set_targets(initial_targets)
    obs = env.reset()
    reset_info = dict(env.reset_infos[0]) if env.reset_infos else {}
    samples: list[dict[str, Any]] = []
    before_switch: dict[str, Any] | None = None
    switch_update_info: dict[str, Any] | None = None
    after_switch: dict[str, Any] | None = None
    final_info = reset_info
    done_after_switch = False

    for step_index in range(1, max_steps + 1):
        actions, _ = model.predict(obs, deterministic=True)
        actions = np.asarray(actions, dtype=np.float64)
        obs, rewards, dones, infos = env.step(actions)
        info = dict(infos[0])
        final_info = info
        sample = _sample_row(
            step_index=step_index,
            target_label="initial" if step_index <= switch_step else "updated",
            info=info,
            action=actions[0],
            reward=float(rewards[0]),
            done=bool(dones[0]),
        )
        samples.append(sample)

        if step_index == switch_step:
            before_switch = sample
            updated_obs, update_infos = env.update_targets(updated_targets)
            if updated_obs is not None:
                obs = updated_obs
            switch_update_info = dict(update_infos[0]) if update_infos else {}
        elif step_index > switch_step and after_switch is None:
            after_switch = sample

        if bool(dones[0]):
            done_after_switch = step_index > switch_step
            break

    before_switch = before_switch or (samples[-1] if samples else {})
    after_switch = after_switch or (samples[-1] if samples else {})
    switch_update_info = switch_update_info or {}
    summary = _summary(
        initial_target=initial_target,
        updated_target=updated_target,
        switch_step=switch_step,
        reset_info=reset_info,
        before_switch=before_switch,
        switch_update_info=switch_update_info,
        after_switch=after_switch,
        final_info=final_info,
        completed_steps=len(samples),
        done_after_switch=done_after_switch,
    )
    return {
        "validation": "duopod_ppo_target_change_v1",
        "initial_target": _target_record(initial_target),
        "updated_target": _target_record(updated_target),
        "summary": summary,
        "samples": samples,
    }


def _summary(
    *,
    initial_target: tuple[float, float],
    updated_target: tuple[float, float],
    switch_step: int,
    reset_info: dict[str, Any],
    before_switch: dict[str, Any],
    switch_update_info: dict[str, Any],
    after_switch: dict[str, Any],
    final_info: dict[str, Any],
    completed_steps: int,
    done_after_switch: bool,
) -> dict[str, Any]:
    phase_before = _number(before_switch, "phase_rad")
    phase_after_update = _number(switch_update_info, "phase_rad")
    episode_before = before_switch.get("episode_id")
    episode_after_update = switch_update_info.get("episode_id")
    episode_final = final_info.get("episode_id")
    machine_before = reset_info.get("machine_id")
    machine_after_update = switch_update_info.get("machine_id")
    machine_final = final_info.get("machine_id")
    return {
        "switch_control_step": switch_step,
        "completed_steps": completed_steps,
        "old_target_bearing_degrees": _bearing_degrees(initial_target),
        "new_target_bearing_degrees": _bearing_degrees(updated_target),
        "desired_yaw_before": _number(before_switch, "desired_yaw_rate"),
        "desired_yaw_after": _number(after_switch, "desired_yaw_rate"),
        "observed_yaw_before": _number(before_switch, "local_yaw_rate"),
        "observed_yaw_after": _number(after_switch, "local_yaw_rate"),
        "left_action_before": _number(before_switch, "applied_left_action"),
        "right_action_before": _number(before_switch, "applied_right_action"),
        "left_action_after": _number(after_switch, "applied_left_action"),
        "right_action_after": _number(after_switch, "applied_right_action"),
        "episode_id_before": episode_before,
        "episode_id_after_update": episode_after_update,
        "episode_id_final": episode_final,
        "same_episode_after_update": episode_before == episode_after_update,
        "same_episode_final": episode_before == episode_final,
        "machine_id_before": machine_before,
        "machine_id_after_update": machine_after_update,
        "machine_id_final": machine_final,
        "same_machine_after_update": machine_before is not None and machine_before == machine_after_update,
        "same_machine_final": machine_before is not None and machine_before == machine_final,
        "phase_before_switch": phase_before,
        "phase_after_update": phase_after_update,
        "phase_preserved_at_switch": abs(phase_before - phase_after_update) <= 1.0e-9,
        "episode_length_before_switch": before_switch.get("episode_length"),
        "episode_length_after_update": switch_update_info.get("episode_length"),
        "done_after_switch": done_after_switch,
    }


def _sample_row(
    *,
    step_index: int,
    target_label: str,
    info: dict[str, Any],
    action: Sequence[float],
    reward: float,
    done: bool,
) -> dict[str, Any]:
    return {
        "step": step_index,
        "target_label": target_label,
        "episode_id": info.get("episode_id"),
        "episode_length": info.get("episode_length"),
        "phase_rad": _number(info, "phase_rad"),
        "distance_to_target": _number(info, "distance_to_target"),
        "desired_yaw_rate": _number(info, "desired_yaw_rate"),
        "local_yaw_rate": _number(info, "local_yaw_rate"),
        "applied_left_action": _action_value(action, 0),
        "applied_right_action": _action_value(action, 1),
        "reward": reward,
        "done": done,
        "termination_reason": str(info.get("termination_reason", "NONE")),
        "machine_id": info.get("machine_id"),
        "base_position_x": _number(info, "base_position_x"),
        "base_position_y": _number(info, "base_position_y"),
        "base_position_z": _number(info, "base_position_z"),
    }


def _repeat_target(target: tuple[float, float], count: int) -> list[list[float]]:
    return [[float(target[0]), float(target[1])] for _ in range(count)]


def _target_record(target: tuple[float, float]) -> dict[str, float]:
    return {
        "forward_blocks": float(target[0]),
        "right_blocks": float(target[1]),
        "bearing_degrees": _bearing_degrees(target),
        "distance_blocks": float(math.hypot(target[0], target[1])),
    }


def _bearing_degrees(target: tuple[float, float]) -> float:
    return float(math.degrees(math.atan2(target[1], target[0])))


def _action_value(action: Sequence[float], index: int) -> float:
    if index >= len(action):
        return 0.0
    value = float(action[index])
    return value if math.isfinite(value) else 0.0


def _number(info: dict[str, Any], key: str) -> float:
    value = info.get(key, 0.0)
    try:
        result = float(value)
    except (TypeError, ValueError):
        return 0.0
    return result if math.isfinite(result) else 0.0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
