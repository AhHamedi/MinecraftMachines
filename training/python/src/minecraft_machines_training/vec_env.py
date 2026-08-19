from __future__ import annotations

from copy import deepcopy
from typing import Any, Sequence

import numpy as np

from .client import MinecraftMachinesBridgeClient
from .configuration import BridgeConnectionConfig

try:
    from gymnasium import spaces
    from stable_baselines3.common.vec_env import VecEnv
except ImportError as exc:  # pragma: no cover
    spaces = None
    VecEnv = object
    _SB3_IMPORT_ERROR = exc
else:
    _SB3_IMPORT_ERROR = None


class MinecraftMachinesVecEnv(VecEnv):
    def __init__(self, config: BridgeConnectionConfig):
        if spaces is None:
            raise ImportError("gymnasium and stable-baselines3 are required for MinecraftMachinesVecEnv") from _SB3_IMPORT_ERROR
        self.client = MinecraftMachinesBridgeClient(config)
        created = self.client.create_session()
        specs = self.client.specs or created
        self.num_slots = int(specs["slotCount"])
        obs_size = len(specs["observationSpec"]["fields"])
        action_size = len(specs["actionSpec"]["fields"])
        observation_space = spaces.Box(low=-1.0, high=1.0, shape=(obs_size,), dtype=np.float32)
        action_space = spaces.Box(low=-1.0, high=1.0, shape=(action_size,), dtype=np.float32)
        super().__init__(self.num_slots, observation_space, action_space)
        self._seeds: list[int | None] = [None for _ in range(self.num_envs)]
        self._options: list[dict[str, Any]] = [{} for _ in range(self.num_envs)]
        self._pending_actions: np.ndarray | None = None
        self._last_reset = created["reset"]
        self.curriculum_stage = str(specs.get("curriculumStage", ""))

    def reset(self):
        reset = self.client.reset_all() if self._last_reset is None else self._last_reset
        self._last_reset = None
        self.reset_infos = [dict(info) for info in reset["infos"]]
        self._reset_seeds()
        self._reset_options()
        return np.asarray(reset["observations"], dtype=np.float32)

    def step_async(self, actions: Sequence[Sequence[float]]) -> None:
        if self._pending_actions is not None:
            raise RuntimeError("step_async called while another step is pending")
        self._pending_actions = _validated_action_batch(
            actions,
            expected_shape=(self.num_envs,) + tuple(self.action_space.shape),
        )

    def step_wait(self):
        if self._pending_actions is None:
            raise RuntimeError("step_async must be called before step_wait")
        response = self.client.step(self._pending_actions.tolist())
        self._pending_actions = None
        observations = np.asarray(response["observations"], dtype=np.float32)
        rewards = np.asarray(response["rewards"], dtype=np.float32)
        terminated = np.asarray(response["terminated"], dtype=bool)
        truncated = np.asarray(response["truncated"], dtype=bool)
        dones = np.logical_or(terminated, truncated)
        infos = [dict(info) for info in response["infos"]]
        reset_observations = response.get("resetObservations", [])
        for index, done in enumerate(dones):
            if done:
                # Stable-Baselines3 uses this flag to bootstrap the value of a
                # time-limit observation instead of treating every horizon as
                # a true terminal state.  Keep Java's terminated/truncated
                # distinction intact when adapting to VecEnv's single `done`.
                infos[index]["TimeLimit.truncated"] = bool(truncated[index] and not terminated[index])
                infos[index]["terminal_observation"] = np.asarray(infos[index].get("terminal_observation", observations[index]), dtype=np.float32)
                if index < len(reset_observations) and len(reset_observations[index]):
                    observations[index] = np.asarray(reset_observations[index], dtype=np.float32)
        return observations, rewards, dones, infos

    def close(self) -> None:
        self.client.close()

    def set_curriculum(self, stage: str) -> None:
        response = self.client.set_curriculum(stage)
        self.curriculum_stage = str(response.get("curriculumStage", stage))
        self._last_reset = response.get("reset")

    def set_targets(self, targets: Sequence[Sequence[float]]) -> None:
        response = self.client.set_targets(targets)
        self._last_reset = response.get("reset")

    def update_targets(self, targets: Sequence[Sequence[float]]) -> tuple[np.ndarray | None, list[dict[str, Any]]]:
        response = self.client.update_targets(targets)
        update = response.get("update") or {}
        if "infos" in update:
            self.reset_infos = [dict(info) for info in update["infos"]]
        observations = np.asarray(update["observations"], dtype=np.float32) if "observations" in update else None
        return observations, [dict(info) for info in self.reset_infos]

    def update_commands(self, commands: Sequence[Sequence[float]]) -> tuple[np.ndarray | None, list[dict[str, Any]]]:
        response = self.client.update_commands(commands)
        update = response.get("update") or {}
        if "infos" in update:
            self.reset_infos = [dict(info) for info in update["infos"]]
        observations = np.asarray(update["observations"], dtype=np.float32) if "observations" in update else None
        return observations, [dict(info) for info in self.reset_infos]

    def seed(self, seed: int | None = None) -> list[int]:
        if seed is None:
            seed = int(np.random.randint(0, np.iinfo(np.uint32).max, dtype=np.uint32))
        self._seeds = [int(seed) + index for index in range(self.num_envs)]
        return list(self._seeds)

    def set_options(self, options: list[dict[str, Any]] | dict[str, Any] | None = None) -> None:
        if options is None:
            options = {}
        if isinstance(options, dict):
            self._options = deepcopy([options] * self.num_envs)
            return
        if len(options) != self.num_envs:
            raise ValueError(f"options length must match num_envs: {len(options)} != {self.num_envs}")
        self._options = deepcopy(options)

    def get_attr(self, attr_name: str, indices=None) -> list[Any]:
        if attr_name == "client":
            return [self.client for _ in self._indices(indices)]
        raise AttributeError(attr_name)

    def set_attr(self, attr_name: str, value: Any, indices=None) -> None:
        raise AttributeError(f"set_attr is not supported for {attr_name}")

    def env_method(self, method_name: str, *method_args, indices=None, **method_kwargs) -> list[Any]:
        raise AttributeError(f"env_method is not supported for {method_name}")

    def env_is_wrapped(self, wrapper_class, indices=None) -> list[bool]:
        return [False for _ in self._indices(indices)]

    def _indices(self, indices):
        if indices is None:
            return range(self.num_envs)
        if isinstance(indices, int):
            return [indices]
        return indices

    def _reset_seeds(self) -> None:
        self._seeds = [None for _ in range(self.num_envs)]

    def _reset_options(self) -> None:
        self._options = [{} for _ in range(self.num_envs)]


def _validated_action_batch(actions: Sequence[Sequence[float]], *, expected_shape: tuple[int, ...]) -> np.ndarray:
    action_array = np.asarray(actions, dtype=np.float64)
    if action_array.shape != expected_shape:
        raise ValueError(f"action batch shape must be {expected_shape}, got {action_array.shape}")
    if not np.all(np.isfinite(action_array)):
        raise ValueError("action batch values must be finite")
    if np.any(action_array < -1.0) or np.any(action_array > 1.0):
        raise ValueError("action batch values must be in [-1, 1]")
    return action_array
