from __future__ import annotations

from copy import deepcopy
import json
import math
from pathlib import Path
import shutil
from typing import Any

from .evaluation import evaluation_contract, held_out_point_goals
from .metrics import write_json
from .training_metadata import update_training_metadata, write_model_metadata


def evaluation_score(summary: dict[str, Any]) -> tuple[float, float, float]:
    success_rate = _number(summary.get("success_rate"))
    mean_return = _number(summary.get("mean_return"))
    mean_final_distance = _number(summary.get("mean_final_distance"))
    return success_rate, mean_return, -mean_final_distance


def should_promote_evaluation(summary: dict[str, Any], previous_payload: dict[str, Any] | None) -> bool:
    if not _is_current_complete_evaluation(previous_payload):
        return True
    previous_summary = previous_payload["summary"]
    return evaluation_score(summary) > evaluation_score(previous_summary)


def promote_best_evaluation(
    *,
    model_path: Path,
    model_metadata: dict[str, Any] | None,
    evaluation_payload: dict[str, Any],
    best_model_path: Path,
    best_evaluation_path: Path | None = None,
) -> dict[str, Any]:
    best_evaluation_path = best_evaluation_path or best_model_path.with_suffix(".evaluation.json")
    previous_payload = _load_json(best_evaluation_path)
    previous_valid = _is_current_complete_evaluation(previous_payload)
    summary = evaluation_payload.get("summary", {})
    promoted = should_promote_evaluation(summary, previous_payload)
    result = {
        "promoted": promoted,
        "score": list(evaluation_score(summary)),
        "previous_score": list(evaluation_score(previous_payload["summary"])) if previous_valid else None,
        "previous_evaluation_valid": previous_valid,
        "best_model_path": str(best_model_path),
        "best_evaluation_path": str(best_evaluation_path),
    }
    if not promoted:
        return result

    best_model_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(model_path, best_model_path)
    best_payload = deepcopy(evaluation_payload)
    best_payload["best_checkpoint"] = result
    write_json(best_evaluation_path, best_payload)
    if model_metadata is not None:
        metadata = update_training_metadata(
            model_metadata,
            model_path=best_model_path,
            evaluation_metrics=summary,
        )
        metadata["best_evaluation_path"] = str(best_evaluation_path)
        write_model_metadata(best_model_path, metadata)
    return result


def _load_json(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid best evaluation JSON: {path}") from exc
    if not isinstance(loaded, dict):
        raise ValueError(f"best evaluation JSON must be an object: {path}")
    return loaded


def _number(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _is_current_complete_evaluation(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    if payload.get("evaluation_contract") != evaluation_contract():
        return False

    manifest = held_out_point_goals().to_dict()
    if payload.get("scenario_manifest") != manifest:
        return False

    summary = payload.get("summary")
    episodes = payload.get("episodes")
    scenarios = manifest["scenarios"]
    if not isinstance(summary, dict) or summary.get("evaluation_valid") is not True:
        return False
    summary_episodes = summary.get("episodes")
    if isinstance(summary_episodes, bool) or not isinstance(summary_episodes, int):
        return False
    if not isinstance(episodes, list) or summary_episodes != len(scenarios) or len(episodes) != len(scenarios):
        return False

    for episode, scenario in zip(episodes, scenarios):
        if not isinstance(episode, dict) or episode.get("scenario_id") != scenario["id"]:
            return False

    success_rate = _finite_number(summary.get("success_rate"))
    mean_return = _finite_number(summary.get("mean_return"))
    mean_final_distance = _finite_number(summary.get("mean_final_distance"))
    return (
        success_rate is not None
        and 0.0 <= success_rate <= 1.0
        and mean_return is not None
        and mean_final_distance is not None
        and mean_final_distance >= 0.0
    )


def _finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    result = float(value)
    return result if math.isfinite(result) else None
