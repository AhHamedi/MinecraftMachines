from __future__ import annotations

import contextlib
import importlib
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest

from minecraft_machines_training.training_metadata import model_metadata_path


_MISSING = object()


class TrainPpoCliTest(unittest.TestCase):
    def test_train_ppo_fresh_path_writes_model_metadata_and_checkpoints(self):
        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory)
            with fake_sb3_and_torch():
                train_ppo = importlib.import_module("minecraft_machines_training.train_ppo")
                train_ppo = importlib.reload(train_ppo)
                envs: list[_FakeVecEnv] = []
                train_ppo.MinecraftMachinesVecEnv = lambda config: _FakeVecEnv(config, envs)

                result = train_ppo.main([
                    "--port", "1234",
                    "--token", "token",
                    "--envs", "2",
                    "--timesteps", "4",
                    "--n-steps", "2",
                    "--batch-size", "2",
                    "--checkpoint-interval", "2",
                    "--run-name", "demo",
                    "--output-dir", str(output_dir),
                    "--curriculum", "flat_point_goals",
                    "--seed", "99",
                ])

            run_dir = output_dir / "demo"
            model_path = run_dir / "model.zip"
            checkpoint_path = run_dir / "checkpoints" / "demo_4_steps.zip"
            model_exists = model_path.exists()
            checkpoint_exists = checkpoint_path.exists()
            checkpoint_metadata_exists = model_metadata_path(checkpoint_path).exists()
            metadata = json.loads((run_dir / "metadata.final.json").read_text(encoding="utf-8"))
            model_metadata = json.loads(model_metadata_path(model_path).read_text(encoding="utf-8"))

        self.assertEqual(result, 0)
        self.assertEqual(len(envs), 1)
        self.assertEqual(envs[0].curriculum_stage, "flat_point_goals")
        self.assertTrue(envs[0].closed)
        self.assertTrue(model_exists)
        self.assertTrue(checkpoint_exists)
        self.assertTrue(checkpoint_metadata_exists)
        self.assertTrue(metadata["training_complete"])
        self.assertEqual(metadata["trained_timesteps"], 4)
        self.assertEqual(metadata["random_seed"], 99)
        self.assertEqual(metadata["latest_checkpoint_path"], str(checkpoint_path))
        self.assertEqual(model_metadata["model_path"], str(model_path))
        self.assertEqual(_FakePPO.learn_calls[-1]["reset_num_timesteps"], True)
        self.assertEqual(_FakePPO.created[-1].kwargs["policy_kwargs"]["net_arch"], [128, 128])

    def test_train_ppo_resume_path_loads_model_and_preserves_timesteps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resume_model = root / "previous.zip"
            resume_model.write_text("previous", encoding="utf-8")
            model_metadata_path(resume_model).write_text(json.dumps({
                "morphology_id": "minecraft_machines:duopod",
                "observation_schema_hash": "obs-hash",
                "action_schema_hash": "act-hash",
            }), encoding="utf-8")
            with fake_sb3_and_torch():
                train_ppo = importlib.import_module("minecraft_machines_training.train_ppo")
                train_ppo = importlib.reload(train_ppo)
                envs: list[_FakeVecEnv] = []
                train_ppo.MinecraftMachinesVecEnv = lambda config: _FakeVecEnv(config, envs)

                result = train_ppo.main([
                    "--port", "1234",
                    "--token", "token",
                    "--envs", "2",
                    "--timesteps", "4",
                    "--n-steps", "2",
                    "--batch-size", "2",
                    "--checkpoint-interval", "0",
                    "--run-name", "resume",
                    "--output-dir", str(root),
                    "--resume-from", str(resume_model),
                ])

            metadata = json.loads((root / "resume" / "metadata.final.json").read_text(encoding="utf-8"))

        self.assertEqual(result, 0)
        self.assertEqual(_FakePPO.loaded_paths[-1], resume_model)
        self.assertEqual(_FakePPO.learn_calls[-1]["reset_num_timesteps"], False)
        self.assertEqual(metadata["resume_from"], str(resume_model))
        self.assertEqual(metadata["trained_timesteps"], 4)
        self.assertTrue(envs[0].closed)


@contextlib.contextmanager
def fake_sb3_and_torch():
    names = ["stable_baselines3", "torch"]
    saved = {name: sys.modules.get(name, _MISSING) for name in names}
    _FakePPO.reset()
    try:
        stable_baselines3 = types.ModuleType("stable_baselines3")
        stable_baselines3.PPO = _FakePPO
        torch = types.ModuleType("torch")
        torch.nn = types.SimpleNamespace(Tanh=_FakeTanh)
        sys.modules["stable_baselines3"] = stable_baselines3
        sys.modules["torch"] = torch
        yield
    finally:
        for name, module in saved.items():
            if module is _MISSING:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module


class _FakePPO:
    created: list["_FakePPO"] = []
    loaded_paths: list[Path] = []
    learn_calls: list[dict] = []

    @classmethod
    def reset(cls) -> None:
        cls.created = []
        cls.loaded_paths = []
        cls.learn_calls = []

    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs
        self.num_timesteps = 0
        self.saved_paths: list[Path] = []
        _FakePPO.created.append(self)

    @classmethod
    def load(cls, path, **kwargs):
        cls.loaded_paths.append(Path(path))
        model = cls("loaded", **kwargs)
        model.loaded_from = Path(path)
        return model

    def learn(self, *, total_timesteps, callback, tb_log_name, reset_num_timesteps):
        _FakePPO.learn_calls.append({
            "total_timesteps": total_timesteps,
            "tb_log_name": tb_log_name,
            "reset_num_timesteps": reset_num_timesteps,
        })
        callback.model = self
        callback._on_training_start()
        midpoint = max(1, total_timesteps // 2)
        for step in (midpoint, total_timesteps):
            self.num_timesteps = step
            callback._on_step()
        callback._on_training_end()
        return self

    def save(self, path):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("model", encoding="utf-8")
        self.saved_paths.append(path)


class _FakeTanh:
    pass


class _FakeVecEnv:
    def __init__(self, config, envs: list["_FakeVecEnv"]):
        self.config = config
        self.num_envs = 2
        self.client = types.SimpleNamespace(specs=_specs())
        self.curriculum_stage = None
        self.closed = False
        envs.append(self)

    def set_curriculum(self, stage: str) -> None:
        self.curriculum_stage = stage

    def close(self) -> None:
        self.closed = True


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
        "controlTicks": 1,
        "curriculumStage": "flat_commands",
        "seed": 42,
        "observationSchemaHash": "obs-hash",
        "actionSchemaHash": "act-hash",
    }


if __name__ == "__main__":
    unittest.main()
