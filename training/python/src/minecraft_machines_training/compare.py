from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

from .evaluation import evaluation_contract, held_out_point_goals


SUMMARY_METRICS: tuple[str, ...] = (
    "episodes",
    "success_rate",
    "mean_return",
    "worst_return",
    "failure_rate",
    "mean_final_distance",
    "mean_distance_travelled_blocks",
    "mean_path_directness",
    "mean_servo_load",
    "peak_servo_load",
    "mean_forward_command_error",
    "mean_yaw_command_error",
    "mean_time_to_target_steps",
)

EPISODE_METRICS: tuple[str, ...] = (
    "return",
    "success",
    "steps",
    "progress_to_target_blocks",
    "final_distance_to_target",
    "action_total_variation",
    "distance_travelled_blocks",
    "path_directness",
    "time_to_target_steps",
    "mean_servo_load",
    "peak_servo_load",
    "mean_forward_command_error",
    "mean_yaw_command_error",
)

LOWER_IS_BETTER = {
    "steps",
    "failure_rate",
    "mean_final_distance",
    "final_distance_to_target",
    "action_total_variation",
    "mean_servo_load",
    "peak_servo_load",
    "mean_forward_command_error",
    "mean_yaw_command_error",
    "mean_time_to_target_steps",
    "time_to_target_steps",
}

SUMMARY_FINITE_METRICS: tuple[str, ...] = (
    "success_rate",
    "mean_return",
    "worst_return",
    "failure_rate",
    "mean_final_distance",
    "mean_distance_travelled_blocks",
    "mean_path_directness",
    "mean_servo_load",
    "peak_servo_load",
    "mean_forward_command_error",
    "mean_yaw_command_error",
)

EPISODE_FINITE_METRICS: tuple[str, ...] = (
    "return",
    "action_total_variation",
    "mean_servo_load",
    "peak_servo_load",
    "mean_forward_command_error",
    "mean_yaw_command_error",
)

AGGREGATE_TOLERANCE = 1.0e-6
GEOMETRY_TOLERANCE = 1.0e-4


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--left", type=Path, required=True)
    parser.add_argument("--right", type=Path, required=True)
    parser.add_argument("--left-label", default="left")
    parser.add_argument("--right-label", default="right")
    parser.add_argument("--output", type=Path, help="optional JSON output path")
    args = parser.parse_args(argv)

    report = compare_evaluations(
        _load_json(args.left),
        _load_json(args.right),
        left_path=args.left,
        right_path=args.right,
        left_label=args.left_label,
        right_label=args.right_label,
    )
    text = json.dumps(report, indent=2, sort_keys=True)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


def compare_evaluations(
    left: dict[str, Any],
    right: dict[str, Any],
    *,
    left_path: Path | None = None,
    right_path: Path | None = None,
    left_label: str = "left",
    right_label: str = "right",
) -> dict[str, Any]:
    left_contract = _required_mapping(left, "evaluation_contract", "left")
    right_contract = _required_mapping(right, "evaluation_contract", "right")
    if left_contract != right_contract:
        raise SystemExit("evaluation contracts differ; refusing to compare")
    if left_contract != evaluation_contract():
        raise SystemExit("unsupported evaluation contract; refusing to compare")
    left_manifest = _required_mapping(left, "scenario_manifest", "left")
    right_manifest = _required_mapping(right, "scenario_manifest", "right")
    _validate_current_manifest(left_manifest, "left")
    _validate_current_manifest(right_manifest, "right")
    if left_manifest != right_manifest:
        raise SystemExit("scenario manifests differ; refusing to compare")
    _validate_policy_compatibility(left, right)
    left_summary = _required_mapping(left, "summary", "left")
    right_summary = _required_mapping(right, "summary", "right")
    _validate_summary(left_summary, "left")
    _validate_summary(right_summary, "right")
    left_episodes = _required_list(left, "episodes", "left")
    right_episodes = _required_list(right, "episodes", "right")
    manifest_scenarios = _manifest_scenarios(left_manifest)
    _validate_episodes(left_episodes, left_summary, "left", manifest_scenarios)
    _validate_episodes(right_episodes, right_summary, "right", manifest_scenarios)
    _validate_summary_aggregates(left_summary, left_episodes, "left")
    _validate_summary_aggregates(right_summary, right_episodes, "right")
    if _episode_ids(left_episodes) != _episode_ids(right_episodes):
        raise SystemExit("episode scenario order differs; refusing to compare")

    return {
        "evaluation_contract": left_contract,
        "scenario_manifest": _manifest_record(left_manifest),
        "left": _artifact_record(left_label, left_path, left),
        "right": _artifact_record(right_label, right_path, right),
        "metrics": _metric_comparisons(left_summary, right_summary, left_label, right_label),
        "episodes": _episode_comparisons(left_episodes, right_episodes, left_label, right_label),
    }


def _load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid evaluation JSON: {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise SystemExit(f"evaluation JSON must be an object: {path}")
    return payload


def _required_mapping(payload: dict[str, Any], key: str, label: str) -> dict[str, Any]:
    value = payload.get(key)
    if not isinstance(value, dict):
        raise SystemExit(f"{label} evaluation is missing object field {key!r}")
    return value


def _required_list(payload: dict[str, Any], key: str, label: str) -> list[Any]:
    value = payload.get(key)
    if not isinstance(value, list):
        raise SystemExit(f"{label} evaluation is missing list field {key!r}")
    return value


def _validate_summary(summary: dict[str, Any], label: str) -> None:
    if summary.get("evaluation_valid") is not True:
        raise SystemExit(f"{label} evaluation is not marked evaluation_valid=true")
    episode_count = summary.get("episodes")
    if isinstance(episode_count, bool) or not isinstance(episode_count, int) or episode_count <= 0:
        raise SystemExit(f"{label} summary is missing positive integer 'episodes'")
    for metric in SUMMARY_FINITE_METRICS:
        _finite_field(summary, metric, f"{label} summary")
    for rate in ("success_rate", "failure_rate"):
        value = _finite_field(summary, rate, f"{label} summary")
        if value < 0.0 or value > 1.0:
            raise SystemExit(f"{label} summary {rate} is outside [0, 1]")
    mean_time = summary.get("mean_time_to_target_steps")
    if mean_time is not None:
        numeric_time = _finite_value(mean_time, "mean_time_to_target_steps", f"{label} summary")
        if numeric_time <= 0.0:
            raise SystemExit(f"{label} summary mean_time_to_target_steps must be positive or null")


def _validate_current_manifest(manifest: dict[str, Any], label: str) -> None:
    expected = held_out_point_goals().to_dict()
    if manifest.get("id") != expected["id"] or manifest.get("version") != expected["version"]:
        raise SystemExit(f"{label} evaluation does not use the current v3 held-out manifest")
    if manifest.get("compatibility_hash") != expected["compatibility_hash"]:
        raise SystemExit(f"{label} evaluation does not use the current v3 manifest hash")
    if manifest != expected:
        raise SystemExit(f"{label} evaluation manifest content does not match the current v3 manifest")


def _validate_policy_compatibility(left: dict[str, Any], right: dict[str, Any]) -> None:
    left_identity = _policy_identity(left, "left")
    right_identity = _policy_identity(right, "right")
    if left_identity != right_identity:
        raise SystemExit("policy morphology or observation/action schemas differ; refusing to compare")


def _policy_identity(payload: dict[str, Any], label: str) -> tuple[str, str, str]:
    policy = _required_mapping(payload, "policy", label)
    metadata = policy.get("metadata")
    sources = (policy, metadata) if isinstance(metadata, dict) else (policy,)

    def required_string(key: str) -> str:
        for source in sources:
            value = source.get(key)
            if isinstance(value, str) and value:
                return value
        raise SystemExit(f"{label} policy is missing string field {key!r}")

    return (
        required_string("morphology_id"),
        required_string("observation_schema_hash"),
        required_string("action_schema_hash"),
    )


def _validate_episodes(
        episodes: list[Any],
        summary: dict[str, Any],
        label: str,
        manifest_scenarios: tuple[dict[str, Any], ...],
) -> None:
    summary_count = summary.get("episodes")
    expected_count = len(manifest_scenarios)
    if not manifest_scenarios:
        raise SystemExit("scenario manifest is missing scenario ids")
    if len(episodes) != expected_count:
        raise SystemExit(
            f"{label} evaluation requires full ordered manifest coverage: "
            f"{len(episodes)} != {expected_count}"
        )
    if summary_count != expected_count:
        raise SystemExit(
            f"{label} summary episode count does not cover the full manifest: "
            f"{summary_count} != {expected_count}"
        )
    if len(episodes) != summary_count:
        raise SystemExit(f"{label} episode count differs from summary: {len(episodes)} != {summary_count}")
    for index, episode in enumerate(episodes):
        if not isinstance(episode, dict):
            raise SystemExit(f"{label} episode {index} is not an object")
        scenario_id = episode.get("scenario_id")
        if not isinstance(scenario_id, str):
            raise SystemExit(f"{label} episode {index} is missing scenario_id")
        scenario = manifest_scenarios[index]
        expected_id = scenario["id"]
        if scenario_id != expected_id:
            raise SystemExit(
                f"{label} episode {index} scenario_id {scenario_id!r} does not match manifest scenario order {expected_id!r}"
            )
        slot = episode.get("slot")
        if slot is not None and (isinstance(slot, bool) or not isinstance(slot, int) or slot < 0):
            raise SystemExit(f"{label} episode {index} has invalid slot")
        expected_bearing = _finite_field(scenario, "target_bearing_degrees", f"manifest scenario {expected_id}")
        reported_bearing = _finite_field(episode, "target_bearing_degrees", f"{label} episode {index}")
        expected_distance = _finite_field(scenario, "target_distance_blocks", f"manifest scenario {expected_id}")
        reported_target = _finite_field(episode, "target_distance_blocks", f"{label} episode {index}")
        initial_distance = _finite_field(episode, "initial_distance_to_target", f"{label} episode {index}")
        final_distance = _finite_field(episode, "final_distance_to_target", f"{label} episode {index}")
        path_length = _finite_field(episode, "distance_travelled_blocks", f"{label} episode {index}")
        reported_progress = _finite_field(episode, "progress_to_target_blocks", f"{label} episode {index}")
        directness = _finite_field(episode, "path_directness", f"{label} episode {index}")
        if abs(reported_bearing - expected_bearing) > GEOMETRY_TOLERANCE:
            raise SystemExit(f"{label} episode {index} target bearing disagrees with manifest")
        if "initial_yaw_degrees" in episode:
            expected_yaw = _finite_field(scenario, "initial_yaw_degrees", f"manifest scenario {expected_id}")
            reported_yaw = _finite_field(episode, "initial_yaw_degrees", f"{label} episode {index}")
            if abs(reported_yaw - expected_yaw) > GEOMETRY_TOLERANCE:
                raise SystemExit(f"{label} episode {index} initial yaw disagrees with manifest")
        if "terrain_stage" in episode and episode.get("terrain_stage") != scenario.get("terrain_stage"):
            raise SystemExit(f"{label} episode {index} terrain stage disagrees with manifest")
        if min(expected_distance, reported_target, initial_distance, final_distance, path_length) < 0.0:
            raise SystemExit(f"{label} episode {index} has a negative geometry metric")
        if abs(reported_target - expected_distance) > GEOMETRY_TOLERANCE:
            raise SystemExit(f"{label} episode {index} target distance disagrees with manifest")
        if abs(initial_distance - expected_distance) > GEOMETRY_TOLERANCE:
            raise SystemExit(f"{label} episode {index} initial distance disagrees with manifest")
        progress = initial_distance - final_distance
        if abs(reported_progress - progress) > GEOMETRY_TOLERANCE:
            raise SystemExit(f"{label} episode {index} reported target progress is inconsistent")
        if progress > path_length + GEOMETRY_TOLERANCE:
            raise SystemExit(f"{label} episode {index} target progress exceeds measured path length")
        expected_directness = 0.0 if path_length <= 1.0e-9 else max(0.0, min(1.0, progress / path_length))
        if directness < 0.0 or directness > 1.0:
            raise SystemExit(f"{label} episode {index} path directness is outside [0, 1]")
        if abs(directness - expected_directness) > GEOMETRY_TOLERANCE:
            raise SystemExit(f"{label} episode {index} path directness is inconsistent with progress/path length")

        success = episode.get("success")
        if not isinstance(success, bool):
            raise SystemExit(f"{label} episode {index} is missing boolean 'success'")
        termination_reason = episode.get("termination_reason")
        if not isinstance(termination_reason, str) or not termination_reason:
            raise SystemExit(f"{label} episode {index} is missing termination_reason")
        if success != (termination_reason == "SUCCESS"):
            raise SystemExit(f"{label} episode {index} success disagrees with termination_reason")

        steps = _positive_integer_field(episode, "steps", f"{label} episode {index}")
        for metric in EPISODE_FINITE_METRICS:
            value = _finite_field(episode, metric, f"{label} episode {index}")
            if metric != "return" and value < 0.0:
                raise SystemExit(f"{label} episode {index} metric {metric!r} must be non-negative")

        time_to_target = episode.get("time_to_target_steps")
        if success:
            target_steps = _positive_integer_value(
                time_to_target,
                "time_to_target_steps",
                f"{label} episode {index}",
            )
            if target_steps > steps:
                raise SystemExit(f"{label} episode {index} time_to_target_steps exceeds episode steps")
        elif time_to_target is not None:
            raise SystemExit(f"{label} episode {index} has time_to_target_steps without success")


def _validate_summary_aggregates(summary: dict[str, Any], episodes: list[Any], label: str) -> None:
    rows = [episode for episode in episodes if isinstance(episode, dict)]
    success_count = sum(1 for row in rows if row["success"])
    failure_count = sum(1 for row in rows if row["termination_reason"] == "MACHINE_FAILURE")
    expected: dict[str, float] = {
        "success_rate": success_count / len(rows),
        "failure_rate": failure_count / len(rows),
        "mean_return": _mean(rows, "return"),
        "worst_return": min(float(row["return"]) for row in rows),
        "mean_final_distance": _mean(rows, "final_distance_to_target"),
        "mean_distance_travelled_blocks": _mean(rows, "distance_travelled_blocks"),
        "mean_path_directness": _mean(rows, "path_directness"),
        "mean_servo_load": _mean(rows, "mean_servo_load"),
        "peak_servo_load": max(float(row["peak_servo_load"]) for row in rows),
        "mean_forward_command_error": _mean(rows, "mean_forward_command_error"),
        "mean_yaw_command_error": _mean(rows, "mean_yaw_command_error"),
    }
    for metric, expected_value in expected.items():
        reported = _finite_field(summary, metric, f"{label} summary")
        if not math.isclose(reported, expected_value, rel_tol=AGGREGATE_TOLERANCE, abs_tol=AGGREGATE_TOLERANCE):
            raise SystemExit(
                f"{label} summary {metric} disagrees with episode rows: "
                f"{reported} != {expected_value}"
            )

    target_times = [float(row["time_to_target_steps"]) for row in rows if row["time_to_target_steps"] is not None]
    expected_time = sum(target_times) / len(target_times) if target_times else None
    reported_time = summary.get("mean_time_to_target_steps")
    if expected_time is None:
        if reported_time is not None:
            raise SystemExit(f"{label} summary mean_time_to_target_steps must be null without successes")
    else:
        numeric_time = _finite_value(reported_time, "mean_time_to_target_steps", f"{label} summary")
        if not math.isclose(numeric_time, expected_time, rel_tol=AGGREGATE_TOLERANCE, abs_tol=AGGREGATE_TOLERANCE):
            raise SystemExit(
                f"{label} summary mean_time_to_target_steps disagrees with episode rows: "
                f"{numeric_time} != {expected_time}"
            )


def _episode_ids(episodes: list[Any]) -> tuple[str, ...]:
    return tuple(str(episode["scenario_id"]) for episode in episodes)


def _manifest_scenarios(manifest: dict[str, Any]) -> tuple[dict[str, Any], ...]:
    scenarios = manifest.get("scenarios")
    if not isinstance(scenarios, list):
        return ()
    validated: list[dict[str, Any]] = []
    for index, scenario in enumerate(scenarios):
        if not isinstance(scenario, dict) or not isinstance(scenario.get("id"), str):
            raise SystemExit(f"scenario manifest entry {index} is missing id")
        validated.append(scenario)
    return tuple(validated)


def _finite_field(payload: dict[str, Any], key: str, label: str) -> float:
    return _finite_value(payload.get(key), key, label)


def _finite_value(value: Any, key: str, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        raise SystemExit(f"{label} is missing finite numeric field {key!r}")
    return float(value)


def _positive_integer_field(payload: dict[str, Any], key: str, label: str) -> int:
    return _positive_integer_value(payload.get(key), key, label)


def _positive_integer_value(value: Any, key: str, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise SystemExit(f"{label} is missing positive integer field {key!r}")
    return value


def _mean(rows: list[dict[str, Any]], key: str) -> float:
    return sum(float(row[key]) for row in rows) / len(rows)


def _manifest_record(manifest: dict[str, Any]) -> dict[str, Any]:
    scenarios = manifest.get("scenarios")
    return {
        "id": manifest.get("id"),
        "version": manifest.get("version"),
        "compatibility_hash": manifest.get("compatibility_hash"),
        "scenario_count": len(scenarios) if isinstance(scenarios, list) else None,
    }


def _artifact_record(label: str, path: Path | None, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "label": label,
        "path": str(path) if path is not None else None,
        "policy": payload.get("policy"),
        "summary": payload.get("summary", {}),
    }


def _metric_comparisons(
    left_summary: dict[str, Any],
    right_summary: dict[str, Any],
    left_label: str,
    right_label: str,
) -> dict[str, dict[str, Any]]:
    metrics: dict[str, dict[str, Any]] = {}
    for metric in SUMMARY_METRICS:
        left_value = _numeric_or_none(left_summary.get(metric))
        right_value = _numeric_or_none(right_summary.get(metric))
        if left_value is None and right_value is None:
            continue
        delta = None if left_value is None or right_value is None else right_value - left_value
        metrics[metric] = {
            "left": left_value,
            "right": right_value,
            "delta": delta,
            "better": _better(metric, delta, left_label, right_label),
        }
    return metrics


def _episode_comparisons(
    left_episodes: list[Any],
    right_episodes: list[Any],
    left_label: str,
    right_label: str,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for left_episode, right_episode in zip(left_episodes, right_episodes):
        assert isinstance(left_episode, dict)
        assert isinstance(right_episode, dict)
        rows.append({
            "scenario_id": left_episode["scenario_id"],
            "target_bearing_degrees": left_episode.get("target_bearing_degrees", right_episode.get("target_bearing_degrees")),
            "target_distance_blocks": left_episode.get("target_distance_blocks", right_episode.get("target_distance_blocks")),
            "metrics": _episode_metric_comparisons(left_episode, right_episode, left_label, right_label),
            "termination_reason": {
                "left": left_episode.get("termination_reason"),
                "right": right_episode.get("termination_reason"),
            },
        })
    return rows


def _episode_metric_comparisons(
    left_episode: dict[str, Any],
    right_episode: dict[str, Any],
    left_label: str,
    right_label: str,
) -> dict[str, dict[str, Any]]:
    metrics: dict[str, dict[str, Any]] = {}
    for metric in EPISODE_METRICS:
        left_value = _numeric_or_none(left_episode.get(metric))
        right_value = _numeric_or_none(right_episode.get(metric))
        if left_value is None and right_value is None:
            continue
        delta = None if left_value is None or right_value is None else right_value - left_value
        metrics[metric] = {
            "left": left_value,
            "right": right_value,
            "delta": delta,
            "better": _better(metric, delta, left_label, right_label),
        }
    return metrics


def _numeric_or_none(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        return float(value)
    return None


def _better(metric: str, delta: float | None, left_label: str, right_label: str) -> str | None:
    if delta is None:
        return None
    if abs(delta) <= 1.0e-12:
        return "tie"
    if metric == "episodes":
        return None
    if metric in LOWER_IS_BETTER:
        return right_label if delta < 0.0 else left_label
    return right_label if delta > 0.0 else left_label


if __name__ == "__main__":
    raise SystemExit(main())
