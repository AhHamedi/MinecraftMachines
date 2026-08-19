from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from minecraft_machines_training.callbacks import MetadataCheckpointCallback
from minecraft_machines_training.configuration import PPOTrainingConfig
from minecraft_machines_training.training_metadata import (
    build_training_metadata,
    load_resume_metadata,
    model_metadata_path,
    repository_commit_hash,
    resume_metadata_candidates,
    update_training_metadata,
    validate_metadata_compatibility,
)


def _specs() -> dict:
    return {
        "protocolVersion": 1,
        "modVersion": "test",
        "morphologyId": "minecraft_machines:duopod",
        "observationSpec": {
            "schemaId": "test:obs",
            "schemaVersion": 1,
            "fields": [
                {"name": "obs_0", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
                {"name": "obs_1", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
            ],
        },
        "actionSpec": {
            "schemaId": "test:act",
            "schemaVersion": 1,
            "fields": [
                {"name": "act_0", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
            ],
        },
        "slotCount": 2,
        "controlTicks": 4,
        "curriculumStage": "flat_commands",
        "seed": 42,
        "observationSchemaHash": "obs-hash",
        "actionSchemaHash": "act-hash",
    }


class TrainingMetadataTest(unittest.TestCase):
    def test_build_training_metadata_records_required_schema_and_training_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            metadata = build_training_metadata(
                morphology_id="minecraft_machines:duopod",
                curriculum_stage="flat_commands",
                specs=_specs(),
                ppo_config=PPOTrainingConfig(total_timesteps=64, n_steps=8, batch_size=8),
                num_envs=2,
                random_seed=7,
                total_timesteps=64,
                run_name="test_run",
                model_path=Path("runs/test_run/model.zip"),
                repository_root=Path(directory),
            )

        self.assertEqual(metadata["format"], "minecraft_machines_ppo_training_metadata_v1")
        self.assertEqual(metadata["policy_type"], "stable_baselines3_ppo")
        self.assertEqual(metadata["morphology_id"], "minecraft_machines:duopod")
        self.assertEqual(metadata["curriculum_stage"], "flat_commands")
        self.assertEqual(metadata["observation_schema_hash"], "obs-hash")
        self.assertEqual(metadata["action_schema_hash"], "act-hash")
        self.assertEqual(metadata["observation_size"], 2)
        self.assertEqual(metadata["action_size"], 1)
        self.assertEqual(metadata["random_seed"], 7)
        self.assertEqual(metadata["total_timesteps"], 64)
        self.assertEqual(metadata["bridge"]["slot_count"], 2)
        self.assertIsNone(metadata["normalization_statistics"])
        self.assertIsNone(metadata["repository_commit_hash"])

    def test_update_training_metadata_keeps_original_payload_immutable(self):
        metadata = build_training_metadata(
            morphology_id="minecraft_machines:duopod",
            curriculum_stage="flat_commands",
            specs=_specs(),
            ppo_config=PPOTrainingConfig(total_timesteps=64, n_steps=8, batch_size=8),
            num_envs=2,
            random_seed=7,
            total_timesteps=64,
            run_name="test_run",
        )

        updated = update_training_metadata(
            metadata,
            num_timesteps=16,
            model_path=Path("model.zip"),
            latest_checkpoint_path=Path("checkpoints/ppo_16_steps.zip"),
            training_complete=True,
        )

        self.assertEqual(metadata["trained_timesteps"], 0)
        self.assertFalse(metadata["training_complete"])
        self.assertEqual(updated["trained_timesteps"], 16)
        self.assertEqual(updated["num_timesteps"], 16)
        self.assertEqual(updated["model_path"], "model.zip")
        self.assertEqual(updated["latest_checkpoint_path"], "checkpoints/ppo_16_steps.zip")
        self.assertTrue(updated["training_complete"])

    def test_repository_commit_hash_returns_none_outside_git(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertIsNone(repository_commit_hash(Path(directory)))

    def test_resume_metadata_loads_model_sidecar_first(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path = Path(directory) / "checkpoint.zip"
            sidecar_path = model_metadata_path(model_path)
            sidecar_path.write_text(json.dumps({"morphology_id": "sidecar"}), encoding="utf-8")
            (Path(directory) / "metadata.final.json").write_text(json.dumps({"morphology_id": "final"}), encoding="utf-8")

            metadata, source = load_resume_metadata(model_path)

        self.assertEqual(metadata, {"morphology_id": "sidecar"})
        self.assertEqual(source, sidecar_path)

    def test_resume_metadata_candidates_cover_sidecar_and_run_metadata(self):
        candidates = resume_metadata_candidates(Path("runs/demo/model.zip"))

        self.assertEqual(
            candidates,
            [
                Path("runs/demo/model.metadata.json"),
                Path("runs/demo/metadata.final.json"),
                Path("runs/demo/metadata.json"),
            ],
        )

    def test_validate_metadata_compatibility_rejects_mismatched_schema(self):
        metadata = {
            "morphology_id": "minecraft_machines:duopod",
            "observation_schema_hash": "old-obs",
            "action_schema_hash": "act-hash",
        }

        with self.assertRaises(ValueError):
            validate_metadata_compatibility(metadata, morphology_id="minecraft_machines:duopod", specs=_specs())

    def test_checkpoint_callback_saves_periodic_model_and_metadata_sidecar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = build_training_metadata(
                morphology_id="minecraft_machines:duopod",
                curriculum_stage="flat_commands",
                specs=_specs(),
                ppo_config=PPOTrainingConfig(total_timesteps=64, n_steps=8, batch_size=8),
                num_envs=2,
                random_seed=7,
                total_timesteps=64,
                run_name="demo",
                repository_root=root,
            )
            callback = MetadataCheckpointCallback(
                root / "metadata.training.json",
                metadata,
                final_metadata_path=root / "metadata.final.json",
                checkpoint_dir=root / "checkpoints",
                checkpoint_interval_steps=10,
                checkpoint_prefix="demo",
            )
            model = _FakeModel()
            callback.model = model

            model.num_timesteps = 9
            self.assertTrue(callback._on_step())
            self.assertEqual(model.saved_paths, [])

            model.num_timesteps = 10
            self.assertTrue(callback._on_step())
            model.num_timesteps = 19
            self.assertTrue(callback._on_step())
            model.num_timesteps = 20
            self.assertTrue(callback._on_step())
            callback._on_training_end()

            first_checkpoint = root / "checkpoints" / "demo_10_steps.zip"
            second_checkpoint = root / "checkpoints" / "demo_20_steps.zip"
            self.assertEqual(model.saved_paths, [first_checkpoint, second_checkpoint])
            self.assertTrue(first_checkpoint.exists())
            self.assertTrue(model_metadata_path(first_checkpoint).exists())
            final_metadata = json.loads((root / "metadata.final.json").read_text(encoding="utf-8"))
            self.assertEqual(final_metadata["trained_timesteps"], 20)
            self.assertEqual(final_metadata["latest_checkpoint_path"], str(second_checkpoint))
            self.assertTrue(final_metadata["training_complete"])


class _FakeModel:
    def __init__(self):
        self.num_timesteps = 0
        self.saved_paths: list[Path] = []

    def save(self, path: Path) -> None:
        self.saved_paths.append(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("model", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
