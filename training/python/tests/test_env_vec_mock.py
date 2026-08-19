from __future__ import annotations

import contextlib
import importlib
import sys
import types
import unittest

import numpy as np

from minecraft_machines_training.configuration import BridgeConnectionConfig


_MISSING = object()


class FakeBox:
    def __init__(self, *, low, high, shape, dtype):
        self.low = low
        self.high = high
        self.shape = tuple(shape)
        self.dtype = dtype


class FakeGymEnv:
    def reset(self, *, seed=None):
        self.seed = seed


class FakeVecEnv:
    def __init__(self, num_envs, observation_space, action_space):
        self.num_envs = num_envs
        self.observation_space = observation_space
        self.action_space = action_space
        self.reset_infos = [{} for _ in range(num_envs)]


class FakeBridgeClient:
    def __init__(self, config: BridgeConnectionConfig, *, slot_count: int = 1):
        self.config = config
        self.slot_count = slot_count
        self.session_id = None
        self.specs = None
        self.closed = False
        self.reset_all_calls = 0
        self.step_calls: list[list[list[float]]] = []
        self.curriculum_stage = None
        self.targets = None
        self.updated_targets = None
        self.updated_commands = None

    def create_session(self):
        self.session_id = "session"
        self.specs = _specs(self.slot_count)
        return {
            **self.specs,
            "sessionId": self.session_id,
            "reset": self._reset("created"),
        }

    def reset_all(self):
        self.reset_all_calls += 1
        return self._reset("reset_all")

    def step(self, actions):
        self.step_calls.append(actions)
        observations = [[float(row[0]), float(index)] for index, row in enumerate(actions)]
        return {
            "observations": observations,
            "rewards": [float(index + 1) for index, _ in enumerate(actions)],
            "terminated": [index == 0 for index, _ in enumerate(actions)],
            "truncated": [index == 1 for index, _ in enumerate(actions)],
            "infos": [
                {"slot": 0, "terminal_observation": [9.0, 9.0]},
                {"slot": 1, "terminal_observation": [8.0, 8.0]},
            ][: len(actions)],
            "resetObservations": [[0.1, 0.2], [0.3, 0.4]][: len(actions)],
        }

    def set_curriculum(self, stage: str):
        self.curriculum_stage = stage
        return {"curriculumStage": stage, "reset": self._reset("set_curriculum")}

    def set_targets(self, targets):
        self.targets = targets
        return {"reset": self._reset("set_targets")}

    def update_targets(self, targets):
        self.updated_targets = targets
        return {"update": self._reset("episode_update")}

    def update_commands(self, commands):
        self.updated_commands = commands
        return {"update": self._reset("command_update")}

    def close(self):
        self.closed = True

    def _reset(self, event: str):
        return {
            "observations": [[0.0, float(index)] for index in range(self.slot_count)],
            "infos": [{"event": event, "slot": index} for index in range(self.slot_count)],
        }


class EnvVecMockTest(unittest.TestCase):
    def test_single_gymnasium_env_uses_created_reset_then_steps(self):
        with fake_optional_modules():
            env_module = importlib.import_module("minecraft_machines_training.env")
            env_module = importlib.reload(env_module)
            client = FakeBridgeClient(BridgeConnectionConfig(port=1, token="token"), slot_count=1)
            env_module.MinecraftMachinesBridgeClient = lambda config: client

            env = env_module.MinecraftMachinesEnv(BridgeConnectionConfig(port=1, token="token"))
            observation, info = env.reset(seed=123)
            next_observation, reward, terminated, truncated, step_info = env.step(np.array([0.5], dtype=np.float32))
            update_info = env.update_target([6.0, 1.0])
            with self.assertRaisesRegex(ValueError, "action shape"):
                env.step(np.array([0.5, 0.0], dtype=np.float32))
            with self.assertRaisesRegex(ValueError, "finite"):
                env.step(np.array([np.nan], dtype=np.float32))
            with self.assertRaisesRegex(ValueError, r"\[-1, 1\]"):
                env.step(np.array([1.25], dtype=np.float32))
            env.close()

        self.assertEqual(env.observation_space.shape, (2,))
        self.assertEqual(env.action_space.shape, (1,))
        self.assertEqual(client.reset_all_calls, 0)
        np.testing.assert_array_equal(observation, np.array([0.0, 0.0], dtype=np.float32))
        self.assertEqual(info["event"], "created")
        np.testing.assert_array_equal(next_observation, np.array([0.5, 0.0], dtype=np.float32))
        self.assertEqual(reward, 1.0)
        self.assertTrue(terminated)
        self.assertFalse(truncated)
        self.assertEqual(step_info["slot"], 0)
        self.assertEqual(update_info["event"], "episode_update")
        self.assertEqual(client.updated_targets, [[6.0, 1.0]])
        self.assertEqual(client.step_calls, [[[0.5]]])
        self.assertTrue(client.closed)

    def test_sb3_vec_env_handles_terminal_infos_and_reset_observations(self):
        with fake_optional_modules():
            vec_module = importlib.import_module("minecraft_machines_training.vec_env")
            vec_module = importlib.reload(vec_module)
            client = FakeBridgeClient(BridgeConnectionConfig(port=1, token="token"), slot_count=2)
            vec_module.MinecraftMachinesBridgeClient = lambda config: client

            env = vec_module.MinecraftMachinesVecEnv(BridgeConnectionConfig(port=1, token="token"))
            reset_observations = env.reset()
            self.assertEqual(env.seed(100), [100, 101])
            self.assertEqual(env._seeds, [100, 101])
            env.set_options({"difficulty": "test"})
            self.assertEqual(env._options, [{"difficulty": "test"}, {"difficulty": "test"}])
            env.set_options([{"slot": 0}, {"slot": 1}])
            self.assertEqual(env._options, [{"slot": 0}, {"slot": 1}])
            with self.assertRaisesRegex(ValueError, "options length"):
                env.set_options([{"slot": 0}])
            env.step_async([[0.25], [-0.5]])
            with self.assertRaises(RuntimeError):
                env.step_async([[0.0], [0.0]])
            observations, rewards, dones, infos = env.step_wait()
            with self.assertRaisesRegex(ValueError, "action batch shape"):
                env.step_async([[0.0]])
            with self.assertRaisesRegex(ValueError, "finite"):
                env.step_async([[np.nan], [0.0]])
            with self.assertRaisesRegex(ValueError, r"\[-1, 1\]"):
                env.step_async([[0.0], [1.1]])
            env.set_curriculum("low_bumps_commands")
            cached_after_curriculum = env.reset()
            self.assertEqual(env._seeds, [None, None])
            self.assertEqual(env._options, [{}, {}])
            env.set_targets([[4.0, -2.0], [4.0, 2.0]])
            cached_after_targets = env.reset()
            reset_calls_before_update = client.reset_all_calls
            env.update_targets([[6.0, -3.0], [6.0, 3.0]])
            env.update_commands([[1.0, 0.0, 0.5], [1.0, 0.0, -0.5]])
            env.close()

        self.assertEqual(env.num_envs, 2)
        np.testing.assert_array_equal(reset_observations, np.array([[0.0, 0.0], [0.0, 1.0]], dtype=np.float32))
        np.testing.assert_array_equal(observations, np.array([[0.1, 0.2], [0.3, 0.4]], dtype=np.float32))
        np.testing.assert_array_equal(rewards, np.array([1.0, 2.0], dtype=np.float32))
        np.testing.assert_array_equal(dones, np.array([True, True]))
        np.testing.assert_array_equal(infos[0]["terminal_observation"], np.array([9.0, 9.0], dtype=np.float32))
        np.testing.assert_array_equal(infos[1]["terminal_observation"], np.array([8.0, 8.0], dtype=np.float32))
        self.assertFalse(infos[0]["TimeLimit.truncated"])
        self.assertTrue(infos[1]["TimeLimit.truncated"])
        np.testing.assert_array_equal(cached_after_curriculum, np.array([[0.0, 0.0], [0.0, 1.0]], dtype=np.float32))
        np.testing.assert_array_equal(cached_after_targets, np.array([[0.0, 0.0], [0.0, 1.0]], dtype=np.float32))
        self.assertEqual(client.curriculum_stage, "low_bumps_commands")
        self.assertEqual(client.targets, [[4.0, -2.0], [4.0, 2.0]])
        self.assertEqual(client.updated_targets, [[6.0, -3.0], [6.0, 3.0]])
        self.assertEqual(client.updated_commands, [[1.0, 0.0, 0.5], [1.0, 0.0, -0.5]])
        self.assertEqual(client.reset_all_calls, reset_calls_before_update)
        self.assertTrue(client.closed)


@contextlib.contextmanager
def fake_optional_modules():
    names = [
        "gymnasium",
        "gymnasium.spaces",
        "stable_baselines3",
        "stable_baselines3.common",
        "stable_baselines3.common.vec_env",
    ]
    saved = {name: sys.modules.get(name, _MISSING) for name in names}
    try:
        gymnasium = types.ModuleType("gymnasium")
        spaces = types.ModuleType("gymnasium.spaces")
        spaces.Box = FakeBox
        gymnasium.Env = FakeGymEnv
        gymnasium.spaces = spaces

        stable_baselines3 = types.ModuleType("stable_baselines3")
        common = types.ModuleType("stable_baselines3.common")
        vec_env = types.ModuleType("stable_baselines3.common.vec_env")
        vec_env.VecEnv = FakeVecEnv
        common.vec_env = vec_env
        stable_baselines3.common = common

        sys.modules["gymnasium"] = gymnasium
        sys.modules["gymnasium.spaces"] = spaces
        sys.modules["stable_baselines3"] = stable_baselines3
        sys.modules["stable_baselines3.common"] = common
        sys.modules["stable_baselines3.common.vec_env"] = vec_env
        yield
    finally:
        for name, module in saved.items():
            if module is _MISSING:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module
        for module_name in ("minecraft_machines_training.env", "minecraft_machines_training.vec_env"):
            if module_name in sys.modules:
                importlib.reload(sys.modules[module_name])


def _specs(slot_count: int):
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
        "slotCount": slot_count,
        "controlTicks": 1,
        "curriculumStage": "flat_commands",
        "seed": 42,
        "observationSchemaHash": "obs-hash",
        "actionSchemaHash": "act-hash",
    }


if __name__ == "__main__":
    unittest.main()
