from __future__ import annotations

import contextlib
import importlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from types import SimpleNamespace

import numpy as np

from minecraft_machines_training.training_metadata import model_metadata_path
from minecraft_machines_training.validate_target_change import run_target_change_validation


_MISSING = object()


class TargetChangeValidationTest(unittest.TestCase):
    def test_target_change_validation_updates_target_without_resetting_episode(self):
        env = _FakeVecEnv()
        model = _FakeModel()

        payload = run_target_change_validation(
            env,
            model,
            initial_target=(6.0, -3.0),
            updated_target=(6.0, 3.0),
            switch_step=2,
            max_steps=5,
        )

        summary = payload["summary"]
        self.assertEqual(env.set_targets_calls, [[[6.0, -3.0]]])
        self.assertEqual(env.update_targets_calls, [[[6.0, 3.0]]])
        self.assertEqual(model.observations_seen[2].tolist(), [[1.0, 1.0]])
        self.assertLess(summary["desired_yaw_before"], 0.0)
        self.assertGreater(summary["desired_yaw_after"], 0.0)
        self.assertNotEqual(summary["left_action_before"], summary["left_action_after"])
        self.assertTrue(summary["same_episode_after_update"])
        self.assertTrue(summary["same_machine_after_update"])
        self.assertTrue(summary["phase_preserved_at_switch"])
        self.assertEqual(summary["episode_length_before_switch"], 2)
        self.assertEqual(summary["episode_length_after_update"], 2)
        self.assertEqual(payload["initial_target"]["bearing_degrees"], -26.56505117707799)
        self.assertEqual(payload["updated_target"]["bearing_degrees"], 26.56505117707799)
        self.assertEqual(payload["samples"][1]["target_label"], "initial")
        self.assertEqual(payload["samples"][2]["target_label"], "updated")

    def test_target_change_cli_writes_json_csv_and_policy_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = root / "model.zip"
            output_path = root / "target_change.json"
            model_path.write_text("model", encoding="utf-8")
            model_metadata_path(model_path).write_text(json.dumps({
                "morphology_id": "minecraft_machines:duopod",
                "observation_schema_hash": "obs-hash",
                "action_schema_hash": "act-hash",
            }), encoding="utf-8")

            with fake_sb3():
                validation = importlib.import_module("minecraft_machines_training.validate_target_change")
                validation.MinecraftMachinesVecEnv = _FakeVecEnv

                result = validation.main([
                    "--port", "1234",
                    "--token", "token",
                    "--model", str(model_path),
                    "--output", str(output_path),
                    "--switch-step", "2",
                    "--max-steps", "4",
                ])

            payload = json.loads(output_path.read_text(encoding="utf-8"))
            csv_text = output_path.with_suffix(".csv").read_text(encoding="utf-8")

        self.assertEqual(result, 0)
        self.assertEqual(payload["policy"]["model_path"], str(model_path))
        self.assertEqual(payload["policy"]["metadata"]["observation_schema_hash"], "obs-hash")
        self.assertTrue(payload["summary"]["same_episode_after_update"])
        self.assertTrue(payload["summary"]["same_machine_after_update"])
        self.assertIn("desired_yaw_rate", csv_text)
        self.assertEqual(_FakePPO.loaded_paths[-1], model_path)


class _FakeModel:
    def __init__(self):
        self.calls = 0
        self.observations_seen: list[np.ndarray] = []

    def predict(self, obs, deterministic=True):
        self.calls += 1
        self.observations_seen.append(np.asarray(obs, dtype=np.float32).copy())
        action = np.array([[0.1 * self.calls, -0.1 * self.calls]], dtype=np.float64)
        return action, None


class _FakeVecEnv:
    def __init__(self, *args, **kwargs):
        self.num_envs = 1
        self.action_space = SimpleNamespace(shape=(2,))
        self.client = SimpleNamespace(specs=_specs())
        self.reset_infos = []
        self.set_targets_calls: list[list[list[float]]] = []
        self.update_targets_calls: list[list[list[float]]] = []
        self.step_count = 0
        self.target_label = "initial"
        self.closed = False

    def set_targets(self, targets):
        self.set_targets_calls.append(targets)
        self.target_label = "initial"

    def reset(self):
        self.step_count = 0
        self.reset_infos = [self._info(event="reset")]
        return np.array([[0.0, 0.0]], dtype=np.float32)

    def step(self, actions):
        self.step_count += 1
        info = self._info(event="step")
        return (
            np.array([[float(self.step_count), 0.0]], dtype=np.float32),
            np.array([1.0], dtype=np.float32),
            np.array([False], dtype=bool),
            [info],
        )

    def update_targets(self, targets):
        self.update_targets_calls.append(targets)
        self.target_label = "updated"
        self.reset_infos = [self._info(event="episode_update")]
        return np.array([[1.0, 1.0]], dtype=np.float32), [dict(self.reset_infos[0])]

    def close(self):
        self.closed = True

    def _info(self, *, event: str):
        desired_yaw = -0.75 if self.target_label == "initial" else 0.75
        return {
            "event": event,
            "episode_id": 77,
            "episode_length": self.step_count,
            "phase_rad": self.step_count * 0.25,
            "distance_to_target": 5.0,
            "desired_yaw_rate": desired_yaw,
            "local_yaw_rate": desired_yaw * 0.5,
            "termination_reason": "NONE",
            "machine_id": "machine-1",
            "base_position_x": float(self.step_count),
            "base_position_y": 64.0,
            "base_position_z": 0.0,
        }


@contextlib.contextmanager
def fake_sb3():
    saved = sys.modules.get("stable_baselines3", _MISSING)
    _FakePPO.reset()
    try:
        stable_baselines3 = types.ModuleType("stable_baselines3")
        stable_baselines3.PPO = _FakePPO
        sys.modules["stable_baselines3"] = stable_baselines3
        yield
    finally:
        if saved is _MISSING:
            sys.modules.pop("stable_baselines3", None)
        else:
            sys.modules["stable_baselines3"] = saved


class _FakePPO:
    loaded_paths: list[Path] = []

    @classmethod
    def reset(cls):
        cls.loaded_paths = []

    @classmethod
    def load(cls, path, env):
        cls.loaded_paths.append(Path(path))
        return _FakeModel()


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
                {"name": "act_1", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
            ],
        },
        "slotCount": 1,
        "controlTicks": 1,
        "curriculumStage": "flat_commands",
        "seed": 42,
        "observationSchemaHash": "obs-hash",
        "actionSchemaHash": "act-hash",
    }


if __name__ == "__main__":
    unittest.main()
