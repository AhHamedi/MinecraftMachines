from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from minecraft_machines_training import live_runbook
from minecraft_machines_training.live_runbook import LiveRunbookSettings, preflight, render_runbook
from minecraft_machines_training.training_metadata import model_metadata_path


class LiveRunbookTest(unittest.TestCase):
    def test_render_runbook_includes_live_test_training_and_evaluation_commands(self):
        settings = _settings(model_path=Path("runs/duopod_ppo/model.zip"))

        text = render_runbook(settings, {"basic", "ppo", "eval"})

        self.assertIn("/mm train bridge start duopod 4", text)
        self.assertIn("/mm train bridge start duopod_at <x> <y> <z> north 4", text)
        self.assertIn("MM_TRAINING_BRIDGE_LIVE=1", text)
        self.assertIn("MM_TRAINING_BRIDGE_LIVE_PPO=1", text)
        self.assertIn("MM_TRAINING_BRIDGE_LIVE_EVAL=1", text)
        self.assertIn("MM_TRAINING_BRIDGE_LIVE_MODEL=runs/duopod_ppo/model.zip", text)
        self.assertIn("minecraft_machines_training.train_ppo", text)
        self.assertIn("minecraft_machines_training.evaluate", text)
        self.assertIn("minecraft_machines_training.compare", text)
        self.assertIn("minecraft_machines_training.validate_target_change", text)
        self.assertIn("--host 127.0.0.1", text)
        self.assertIn("--port 25575", text)
        self.assertIn("--token token", text)
        self.assertIn("--model runs/duopod_ppo/model.zip", text)
        self.assertIn("--left minecraft_machines/duopod_cem_evaluation_latest.json", text)
        self.assertIn("--right runs/duopod_ppo/evaluation.json", text)
        self.assertIn("--output runs/duopod_ppo/cem_vs_ppo_comparison.json", text)
        self.assertIn("--check", text)
        self.assertIn("--connect", text)
        self.assertIn("Run this schema preflight only after the in-game bridge is already running", text)

    def test_preflight_reports_missing_bridge_values_dependencies_and_model(self):
        settings = _settings(port=0, token="", model_path=None)

        issues = preflight(
            settings,
            {"basic", "ppo", "eval"},
            dependency_available=lambda module: False,
        )

        self.assertIn("missing bridge port; set MM_TRAINING_BRIDGE_PORT or pass --port", issues)
        self.assertIn("missing bridge token; set MM_TRAINING_BRIDGE_TOKEN or pass --token", issues)
        self.assertIn("missing optional Python dependency: stable_baselines3", issues)
        self.assertIn("missing optional Python dependency: torch", issues)
        self.assertIn("missing saved model path; set MM_TRAINING_BRIDGE_LIVE_MODEL or pass --model", issues)

    def test_preflight_accepts_saved_model_with_metadata_without_connecting(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path = Path(directory) / "model.zip"
            model_path.write_text("model", encoding="utf-8")
            model_metadata_path(model_path).write_text(json.dumps({
                "morphology_id": "minecraft_machines:duopod",
                "observation_schema_hash": "obs-hash",
                "action_schema_hash": "act-hash",
            }), encoding="utf-8")

            issues = preflight(
                _settings(model_path=model_path),
                {"eval"},
                dependency_available=lambda module: True,
            )

        self.assertEqual(issues, [])

    def test_preflight_reports_missing_model_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path = Path(directory) / "model.zip"
            model_path.write_text("model", encoding="utf-8")

            issues = preflight(
                _settings(model_path=model_path),
                {"eval"},
                dependency_available=lambda module: True,
            )

        self.assertEqual(len(issues), 1)
        self.assertIn("missing model metadata sidecar", issues[0])

    def test_preflight_connect_validates_live_schema_against_model_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            model_path = Path(directory) / "model.zip"
            model_path.write_text("model", encoding="utf-8")
            model_metadata_path(model_path).write_text(json.dumps({
                "morphology_id": "minecraft_machines:duopod",
                "observation_schema_hash": "obs-hash",
                "action_schema_hash": "different-action-hash",
            }), encoding="utf-8")

            original_client = live_runbook.MinecraftMachinesBridgeClient
            live_runbook.MinecraftMachinesBridgeClient = _FakeRunbookBridgeClient
            try:
                issues = preflight(
                    _settings(model_path=model_path),
                    {"eval"},
                    connect=True,
                    dependency_available=lambda module: True,
                )
            finally:
                live_runbook.MinecraftMachinesBridgeClient = original_client

        self.assertEqual(len(issues), 1)
        self.assertIn("action schema hash mismatch", issues[0])
        self.assertTrue(_FakeRunbookBridgeClient.last_instance.closed)

    def test_preflight_connect_reports_live_slot_count_mismatch(self):
        original_client = live_runbook.MinecraftMachinesBridgeClient
        live_runbook.MinecraftMachinesBridgeClient = _FakeRunbookBridgeClient
        _FakeRunbookBridgeClient.slot_count = 8
        try:
            issues = preflight(
                _settings(port=25575, model_path=None),
                {"basic"},
                connect=True,
                dependency_available=lambda module: True,
            )
        finally:
            _FakeRunbookBridgeClient.slot_count = 4
            live_runbook.MinecraftMachinesBridgeClient = original_client

        self.assertEqual(issues, ["bridge slot count mismatch: 8 != 4"])
        self.assertTrue(_FakeRunbookBridgeClient.last_instance.closed)

    def test_main_returns_failure_for_static_preflight_issues(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            result = live_runbook.main([
                "--mode", "basic",
                "--port", "0",
                "--token", "",
                "--check",
            ])

        self.assertEqual(result, 2)
        self.assertIn("Preflight failed", stdout.getvalue())
        self.assertIn("missing bridge port", stdout.getvalue())

    def test_main_returns_success_for_basic_static_preflight(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            result = live_runbook.main([
                "--mode", "basic",
                "--port", "1234",
                "--token", "token",
                "--check",
            ])

        self.assertEqual(result, 0)
        self.assertIn("Preflight passed", stdout.getvalue())

    def test_main_returns_success_when_printing_without_check(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            result = live_runbook.main([
                "--mode", "basic",
                "--port", "1234",
                "--token", "token",
            ])

        self.assertEqual(result, 0)
        self.assertIn("Minecraft Machines live PPO bridge runbook", stdout.getvalue())


class _FakeRunbookBridgeClient:
    last_instance: "_FakeRunbookBridgeClient"
    slot_count = 4

    def __init__(self, config):
        self.config = config
        self.closed = False
        type(self).last_instance = self

    def get_specs(self):
        return {
            "morphologyId": "minecraft_machines:duopod",
            "slotCount": self.slot_count,
            "observationSchemaHash": "obs-hash",
            "actionSchemaHash": "act-hash",
        }

    def close(self):
        self.closed = True


def _settings(
    *,
    host: str = "127.0.0.1",
    port: int = 25575,
    token: str = "token",
    model_path: Path | None = Path("runs/duopod_ppo/model.zip"),
) -> LiveRunbookSettings:
    return LiveRunbookSettings(
        host=host,
        port=port,
        token=token,
        morphology="minecraft_machines:duopod",
        slot_count=4,
        model_path=model_path,
        cem_evaluation_path=Path("minecraft_machines/duopod_cem_evaluation_latest.json"),
        run_name="duopod_ppo",
        output_dir=Path("runs"),
        timesteps=128,
        python="python3",
        timeout_seconds=180.0,
    )


if __name__ == "__main__":
    unittest.main()
