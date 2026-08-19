from __future__ import annotations

from copy import deepcopy
from dataclasses import asdict, is_dataclass
import json
from pathlib import Path
import subprocess
from typing import Any

from .metrics import write_json


METADATA_FORMAT = "minecraft_machines_ppo_training_metadata_v1"
POLICY_TYPE = "stable_baselines3_ppo"


def repository_commit_hash(repo_root: Path | None = None) -> str | None:
    cwd = repo_root or Path.cwd()
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=cwd,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    value = result.stdout.strip()
    return value or None


def build_training_metadata(
    *,
    morphology_id: str,
    curriculum_stage: str,
    specs: dict[str, Any],
    ppo_config: Any,
    num_envs: int,
    random_seed: int,
    total_timesteps: int,
    run_name: str,
    model_path: Path | None = None,
    resume_from: Path | None = None,
    repository_root: Path | None = None,
    evaluation_metrics: dict[str, Any] | None = None,
    normalization_statistics: dict[str, Any] | None = None,
) -> dict[str, Any]:
    observation_spec = deepcopy(specs.get("observationSpec") or {})
    action_spec = deepcopy(specs.get("actionSpec") or {})
    return {
        "format": METADATA_FORMAT,
        "policy_type": POLICY_TYPE,
        "run_name": run_name,
        "morphology_id": morphology_id,
        "curriculum_stage": curriculum_stage,
        "random_seed": int(random_seed),
        "total_timesteps": int(total_timesteps),
        "trained_timesteps": 0,
        "num_envs": int(num_envs),
        "model_path": str(model_path) if model_path is not None else None,
        "resume_from": str(resume_from) if resume_from is not None else None,
        "observation_schema_hash": specs.get("observationSchemaHash"),
        "action_schema_hash": specs.get("actionSchemaHash"),
        "observation_size": len(observation_spec.get("fields", [])),
        "action_size": len(action_spec.get("fields", [])),
        "observation_spec": observation_spec,
        "action_spec": action_spec,
        "bridge": {
            "protocol_version": specs.get("protocolVersion"),
            "mod_version": specs.get("modVersion"),
            "slot_count": specs.get("slotCount"),
            "control_ticks": specs.get("controlTicks"),
            "seed": specs.get("seed"),
            "server_curriculum_stage": specs.get("curriculumStage"),
        },
        "ppo_config": _config_dict(ppo_config),
        "evaluation_metrics": dict(evaluation_metrics or {}),
        "normalization_statistics": normalization_statistics,
        "repository_commit_hash": repository_commit_hash(repository_root),
        "latest_checkpoint_path": None,
        "training_complete": False,
    }


def update_training_metadata(
    metadata: dict[str, Any],
    *,
    num_timesteps: int | None = None,
    model_path: Path | None = None,
    latest_checkpoint_path: Path | None = None,
    training_complete: bool | None = None,
    evaluation_metrics: dict[str, Any] | None = None,
    normalization_statistics: dict[str, Any] | None = None,
) -> dict[str, Any]:
    updated = deepcopy(metadata)
    if num_timesteps is not None:
        updated["trained_timesteps"] = int(num_timesteps)
        updated["num_timesteps"] = int(num_timesteps)
    if model_path is not None:
        updated["model_path"] = str(model_path)
    if latest_checkpoint_path is not None:
        updated["latest_checkpoint_path"] = str(latest_checkpoint_path)
    if training_complete is not None:
        updated["training_complete"] = bool(training_complete)
    if evaluation_metrics is not None:
        updated["evaluation_metrics"] = dict(evaluation_metrics)
    if normalization_statistics is not None:
        updated["normalization_statistics"] = dict(normalization_statistics)
    return updated


def model_metadata_path(model_path: Path) -> Path:
    return model_path.with_suffix(".metadata.json")


def write_model_metadata(model_path: Path, metadata: dict[str, Any]) -> Path:
    metadata_path = model_metadata_path(model_path)
    write_json(metadata_path, metadata)
    return metadata_path


def resume_metadata_candidates(model_path: Path) -> list[Path]:
    candidates = [
        model_metadata_path(model_path),
        model_path.parent / "metadata.final.json",
        model_path.parent / "metadata.json",
    ]
    unique: list[Path] = []
    for candidate in candidates:
        if candidate not in unique:
            unique.append(candidate)
    return unique


def load_resume_metadata(model_path: Path) -> tuple[dict[str, Any] | None, Path | None]:
    for candidate in resume_metadata_candidates(model_path):
        if candidate.exists():
            try:
                return json.loads(candidate.read_text(encoding="utf-8")), candidate
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid resume metadata JSON: {candidate}") from exc
    return None, None


def validate_metadata_compatibility(metadata: dict[str, Any] | None, *, morphology_id: str, specs: dict[str, Any]) -> None:
    if not metadata:
        return
    recorded_morphology = metadata.get("morphology_id") or metadata.get("morphology")
    if recorded_morphology and recorded_morphology != morphology_id:
        raise ValueError(f"resume metadata morphology mismatch: {recorded_morphology} != {morphology_id}")
    _validate_hash(
        "observation schema",
        _recorded_hash(metadata, "observation_schema_hash", "observationSchemaHash"),
        specs.get("observationSchemaHash"),
    )
    _validate_hash(
        "action schema",
        _recorded_hash(metadata, "action_schema_hash", "actionSchemaHash"),
        specs.get("actionSchemaHash"),
    )


def _validate_hash(label: str, recorded: Any, current: Any) -> None:
    if recorded is not None and current is not None and recorded != current:
        raise ValueError(f"resume metadata {label} hash mismatch: {recorded} != {current}")


def _recorded_hash(metadata: dict[str, Any], primary_key: str, legacy_key: str) -> Any:
    if primary_key in metadata:
        return metadata.get(primary_key)
    specs = metadata.get("specs")
    if isinstance(specs, dict):
        return specs.get(legacy_key)
    return None


def _config_dict(config: Any) -> dict[str, Any]:
    if is_dataclass(config):
        return asdict(config)
    return dict(config)
