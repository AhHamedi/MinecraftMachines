from __future__ import annotations

from typing import Any

import numpy as np

from .client import MinecraftMachinesBridgeClient
from .configuration import BridgeConnectionConfig

try:
    import gymnasium as gym
    from gymnasium import spaces
except ImportError as exc:  # pragma: no cover - exercised when optional deps are absent
    gym = None
    spaces = None
    _GYM_IMPORT_ERROR = exc
else:
    _GYM_IMPORT_ERROR = None


class MinecraftMachinesEnv(gym.Env if gym is not None else object):
    metadata = {"render_modes": []}

    def __init__(self, config: BridgeConnectionConfig):
        if gym is None:
            raise ImportError("gymnasium is required for MinecraftMachinesEnv") from _GYM_IMPORT_ERROR
        self.client = MinecraftMachinesBridgeClient(config)
        created = self.client.create_session()
        specs = self.client.specs or created
        obs_size = len(specs["observationSpec"]["fields"])
        action_size = len(specs["actionSpec"]["fields"])
        self.observation_space = spaces.Box(low=-1.0, high=1.0, shape=(obs_size,), dtype=np.float32)
        self.action_space = spaces.Box(low=-1.0, high=1.0, shape=(action_size,), dtype=np.float32)
        self._last_reset = created["reset"]

    def reset(self, *, seed: int | None = None, options: dict[str, Any] | None = None):
        super().reset(seed=seed)
        reset = self.client.reset_all() if self._last_reset is None else self._last_reset
        self._last_reset = None
        observation = np.asarray(reset["observations"][0], dtype=np.float32)
        info = dict(reset["infos"][0])
        return observation, info

    def step(self, action):
        action_array = _validated_action_array(action, expected_shape=self.action_space.shape, label="action")
        response = self.client.step([action_array.tolist()])
        observation = np.asarray(response["observations"][0], dtype=np.float32)
        reward = float(response["rewards"][0])
        terminated = bool(response["terminated"][0])
        truncated = bool(response["truncated"][0])
        info = dict(response["infos"][0])
        return observation, reward, terminated, truncated, info

    def set_target(self, target: tuple[float, float] | list[float]) -> None:
        response = self.client.set_targets([list(target)])
        self._last_reset = response.get("reset")

    def update_target(self, target: tuple[float, float] | list[float]) -> dict[str, Any]:
        response = self.client.update_targets([list(target)])
        update = response.get("update") or {}
        infos = update.get("infos") or [{}]
        return dict(infos[0])

    def update_command(self, command: tuple[float, float, float] | list[float]) -> dict[str, Any]:
        response = self.client.update_commands([list(command)])
        update = response.get("update") or {}
        infos = update.get("infos") or [{}]
        return dict(infos[0])

    def close(self):
        self.client.close()


def _validated_action_array(action: Any, *, expected_shape: tuple[int, ...], label: str) -> np.ndarray:
    action_array = np.asarray(action, dtype=np.float64)
    if action_array.shape != expected_shape:
        raise ValueError(f"{label} shape must be {expected_shape}, got {action_array.shape}")
    if not np.all(np.isfinite(action_array)):
        raise ValueError(f"{label} values must be finite")
    if np.any(action_array < -1.0) or np.any(action_array > 1.0):
        raise ValueError(f"{label} values must be in [-1, 1]")
    return action_array
