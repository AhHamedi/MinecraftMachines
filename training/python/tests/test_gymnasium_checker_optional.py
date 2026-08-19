from __future__ import annotations

import importlib
import unittest

try:
    from gymnasium.utils.env_checker import check_env
except ImportError:  # pragma: no cover - optional dependency absent in the lightweight suite
    check_env = None

from minecraft_machines_training.configuration import BridgeConnectionConfig


@unittest.skipIf(check_env is None, "gymnasium is required for the optional environment checker test")
class OptionalGymnasiumCheckerTest(unittest.TestCase):
    def test_single_environment_passes_gymnasium_checker_with_mock_bridge(self):
        env_module = importlib.import_module("minecraft_machines_training.env")
        env_module = importlib.reload(env_module)
        client = _CheckerBridgeClient(BridgeConnectionConfig(port=1, token="token"))
        original_client_class = env_module.MinecraftMachinesBridgeClient
        env_module.MinecraftMachinesBridgeClient = lambda config: client
        env = None
        try:
            env = env_module.MinecraftMachinesEnv(BridgeConnectionConfig(port=1, token="token"))
            check_env(env, skip_render_check=True)
        finally:
            if env is not None:
                env.close()
            env_module.MinecraftMachinesBridgeClient = original_client_class

        self.assertGreater(client.reset_all_calls, 0)
        self.assertGreater(client.step_calls, 0)
        self.assertTrue(client.closed)


class _CheckerBridgeClient:
    def __init__(self, config: BridgeConnectionConfig):
        self.config = config
        self.specs = None
        self.closed = False
        self.reset_all_calls = 0
        self.step_calls = 0
        self._step_index = 0

    def create_session(self):
        self.specs = {
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
            "slotCount": 1,
            "controlTicks": 1,
            "curriculumStage": "flat_commands",
            "seed": 42,
            "observationSchemaHash": "obs-hash",
            "actionSchemaHash": "act-hash",
        }
        return {**self.specs, "sessionId": "session", "reset": self._reset("created")}

    def reset_all(self):
        self.reset_all_calls += 1
        self._step_index = 0
        return self._reset("reset_all")

    def step(self, actions):
        self.step_calls += 1
        self._step_index += 1
        value = max(-1.0, min(1.0, float(actions[0][0])))
        return {
            "observations": [[value, min(1.0, self._step_index / 10.0)]],
            "rewards": [1.0 - abs(value)],
            "terminated": [self._step_index >= 5],
            "truncated": [False],
            "infos": [{"step_index": self._step_index}],
            "resetObservations": [[]],
        }

    def close(self):
        self.closed = True

    def _reset(self, event: str):
        return {
            "observations": [[0.0, 0.0]],
            "infos": [{"event": event}],
        }


if __name__ == "__main__":
    unittest.main()
