from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

from minecraft_machines_training.client import MinecraftMachinesBridgeClient
from minecraft_machines_training.configuration import BridgeConnectionConfig
from minecraft_machines_training.evaluate import main as evaluate_main
from minecraft_machines_training.train_ppo import main as train_ppo_main
from minecraft_machines_training.training_metadata import model_metadata_path
from minecraft_machines_training.validate_target_change import main as validate_target_change_main

try:
    import pytest
except ImportError:  # pragma: no cover - pytest is optional for the stdlib suite
    pytestmark = ()
else:
    pytestmark = pytest.mark.live


def _live_enabled() -> bool:
    return os.environ.get("MM_TRAINING_BRIDGE_LIVE") == "1"


def _live_ppo_enabled() -> bool:
    return _live_enabled() and os.environ.get("MM_TRAINING_BRIDGE_LIVE_PPO") == "1"


def _live_eval_enabled() -> bool:
    return _live_enabled() and os.environ.get("MM_TRAINING_BRIDGE_LIVE_EVAL") == "1"


def _sb3_available() -> bool:
    return importlib.util.find_spec("stable_baselines3") is not None and importlib.util.find_spec("torch") is not None


def _live_model_path() -> Path:
    return Path(os.environ.get("MM_TRAINING_BRIDGE_LIVE_MODEL", "")).expanduser()


def _live_config() -> BridgeConnectionConfig:
    return BridgeConnectionConfig(
        host=os.environ.get("MM_TRAINING_BRIDGE_HOST", "127.0.0.1"),
        port=int(os.environ.get("MM_TRAINING_BRIDGE_PORT", "0")),
        token=os.environ.get("MM_TRAINING_BRIDGE_TOKEN", ""),
        morphology=os.environ.get("MM_TRAINING_BRIDGE_MORPHOLOGY", "minecraft_machines:duopod"),
        timeout_seconds=float(os.environ.get("MM_TRAINING_BRIDGE_TIMEOUT_SECONDS", "180")),
    )


@unittest.skipUnless(_live_enabled(), "set MM_TRAINING_BRIDGE_LIVE=1 with bridge host/port/token to run live tests")
class LiveBridgeIntegrationTest(unittest.TestCase):
    def test_live_bridge_session_reset_step_and_close(self):
        client = MinecraftMachinesBridgeClient(_live_config())
        try:
            created = client.create_session()
            specs = client.get_specs()
            self.assertEqual(specs["morphologyId"], _live_config().morphology)
            self.assertEqual(created["observationSchemaHash"], specs["observationSchemaHash"])
            self.assertEqual(created["actionSchemaHash"], specs["actionSchemaHash"])

            reset = client.reset_all()
            slot_count = int(specs["slotCount"])
            observation_size = len(specs["observationSpec"]["fields"])
            action_size = len(specs["actionSpec"]["fields"])
            self.assertEqual(len(reset["observations"]), slot_count)
            self.assertEqual(len(reset["observations"][0]), observation_size)

            neutral_actions = [[0.0 for _ in range(action_size)] for _ in range(slot_count)]
            step = client.step(neutral_actions)
            self.assertEqual(len(step["observations"]), slot_count)
            self.assertEqual(len(step["observations"][0]), observation_size)
            self.assertEqual(len(step["rewards"]), slot_count)
            self.assertEqual(len(step["terminated"]), slot_count)
            self.assertEqual(len(step["truncated"]), slot_count)
            self.assertEqual(len(step["infos"]), slot_count)
        finally:
            client.close()

    def test_live_bridge_update_targets_preserves_episode_machine_and_phase(self):
        client = MinecraftMachinesBridgeClient(_live_config())
        try:
            client.create_session()
            specs = client.get_specs()
            slot_count = int(specs["slotCount"])
            observation_size = len(specs["observationSpec"]["fields"])
            action_size = len(specs["actionSpec"]["fields"])
            initial_targets = [[6.0, -3.0] for _ in range(slot_count)]
            updated_targets = [[6.0, 3.0] for _ in range(slot_count)]

            reset_response = client.set_targets(initial_targets)
            self.assertIn("reset", reset_response)
            self.assertNotIn("update", reset_response)

            neutral_actions = [[0.0 for _ in range(action_size)] for _ in range(slot_count)]
            step_before_update = client.step(neutral_actions)
            info_before = step_before_update["infos"][0]
            self.assertIn("episode_id", info_before)
            self.assertIn("phase_rad", info_before)

            update_response = client.update_targets(updated_targets)
            self.assertIn("update", update_response)
            self.assertNotIn("reset", update_response)
            update_payload = update_response["update"]
            self.assertEqual(len(update_payload["observations"]), slot_count)
            self.assertEqual(len(update_payload["observations"][0]), observation_size)
            self.assertEqual(len(update_payload["infos"]), slot_count)
            info_update = update_payload["infos"][0]

            self.assertEqual("episode_update", info_update["event"])
            self.assertEqual(info_before["episode_id"], info_update["episode_id"])
            self.assertEqual(info_before["episode_length"], info_update["episode_length"])
            self.assertAlmostEqual(float(info_before["phase_rad"]), float(info_update["phase_rad"]))
            if "machine_id" in info_before and "machine_id" in info_update:
                self.assertEqual(info_before["machine_id"], info_update["machine_id"])

            step_after_update = client.step(neutral_actions)
            self.assertEqual(len(step_after_update["observations"]), slot_count)
            self.assertEqual(len(step_after_update["observations"][0]), observation_size)
        finally:
            client.close()

    @unittest.skipUnless(_live_ppo_enabled(), "set MM_TRAINING_BRIDGE_LIVE=1 and MM_TRAINING_BRIDGE_LIVE_PPO=1 to run live PPO smoke training")
    @unittest.skipUnless(_sb3_available(), "stable-baselines3 and torch are required for live PPO smoke training")
    def test_live_bridge_tiny_ppo_training_writes_model_and_metadata(self):
        config = _live_config()
        specs_client = MinecraftMachinesBridgeClient(config)
        try:
            specs = specs_client.get_specs()
        finally:
            specs_client.close()
        slot_count = int(specs["slotCount"])
        timesteps = max(2, slot_count * 2)

        with tempfile.TemporaryDirectory() as directory:
            output_dir = Path(directory)
            result = train_ppo_main([
                "--host", config.host,
                "--port", str(config.port),
                "--token", config.token,
                "--morphology", config.morphology,
                "--envs", str(slot_count),
                "--timesteps", str(timesteps),
                "--curriculum", "flat_commands",
                "--run-name", "live_ppo_smoke",
                "--output-dir", str(output_dir),
                "--checkpoint-interval", "0",
                "--seed", "7",
                "--n-steps", "2",
                "--batch-size", "2",
                "--n-epochs", "1",
            ])

            model_path = output_dir / "live_ppo_smoke" / "model.zip"
            metadata_path = output_dir / "live_ppo_smoke" / "metadata.final.json"
            sidecar_path = model_metadata_path(model_path)
            self.assertEqual(result, 0)
            self.assertTrue(model_path.exists())
            self.assertTrue(metadata_path.exists())
            self.assertTrue(sidecar_path.exists())

    @unittest.skipUnless(_live_eval_enabled(), "set MM_TRAINING_BRIDGE_LIVE=1 and MM_TRAINING_BRIDGE_LIVE_EVAL=1 to run live PPO evaluation")
    @unittest.skipUnless(_sb3_available(), "stable-baselines3 and torch are required for live PPO evaluation")
    def test_live_bridge_saved_ppo_evaluation_writes_json_csv(self):
        config = _live_config()
        model_path = _live_model_path()
        self.assertTrue(model_path.is_file(), "set MM_TRAINING_BRIDGE_LIVE_MODEL to a saved PPO model.zip with metadata sidecar")

        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "live_evaluation.json"
            result = evaluate_main([
                "--host", config.host,
                "--port", str(config.port),
                "--token", config.token,
                "--morphology", config.morphology,
                "--model", str(model_path),
                "--output", str(output_path),
                "--episodes", "1",
                "--max-steps", "2",
            ])

            self.assertEqual(result, 0)
            self.assertTrue(output_path.exists())
            self.assertTrue(output_path.with_suffix(".csv").exists())
            payload = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertIn("policy", payload)
            self.assertEqual(payload["summary"]["episodes"], 1)
            self.assertIn("scenario_manifest", payload)

    @unittest.skipUnless(_live_eval_enabled(), "set MM_TRAINING_BRIDGE_LIVE=1 and MM_TRAINING_BRIDGE_LIVE_EVAL=1 to run live PPO target-change validation")
    @unittest.skipUnless(_sb3_available(), "stable-baselines3 and torch are required for live PPO target-change validation")
    def test_live_bridge_saved_ppo_target_change_validation_writes_json_csv(self):
        config = _live_config()
        model_path = _live_model_path()
        self.assertTrue(model_path.is_file(), "set MM_TRAINING_BRIDGE_LIVE_MODEL to a saved PPO model.zip with metadata sidecar")

        with tempfile.TemporaryDirectory() as directory:
            output_path = Path(directory) / "live_target_change.json"
            result = validate_target_change_main([
                "--host", config.host,
                "--port", str(config.port),
                "--token", config.token,
                "--morphology", config.morphology,
                "--model", str(model_path),
                "--output", str(output_path),
                "--switch-step", "1",
                "--max-steps", "3",
            ])

            self.assertEqual(result, 0)
            self.assertTrue(output_path.exists())
            self.assertTrue(output_path.with_suffix(".csv").exists())
            payload = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertIn("policy", payload)
            self.assertEqual(payload["validation"], "duopod_ppo_target_change_v1")
            self.assertIn("same_episode_after_update", payload["summary"])


if __name__ == "__main__":
    unittest.main()
