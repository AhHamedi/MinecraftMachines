from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import numpy as np


try:
    import gymnasium as gym
    from gymnasium import spaces
    from stable_baselines3 import PPO
except ImportError:  # pragma: no cover - exercised in the lightweight stdlib suite
    gym = None
    spaces = None
    PPO = None

_GymEnvBase = gym.Env if gym is not None else object


@unittest.skipIf(PPO is None, "gymnasium and stable-baselines3 are required for the optional PPO smoke test")
class OptionalPpoSmokeTest(unittest.TestCase):
    def test_ppo_tiny_train_save_reload_and_evaluate_on_deterministic_continuous_env(self):
        assert gym is not None
        assert spaces is not None
        assert PPO is not None
        with tempfile.TemporaryDirectory() as directory:
            train_env = _DeterministicContinuousEnv()
            model = PPO(
                "MlpPolicy",
                train_env,
                learning_rate=3.0e-4,
                n_steps=4,
                batch_size=4,
                n_epochs=1,
                gamma=0.9,
                gae_lambda=0.9,
                ent_coef=0.0,
                policy_kwargs={"net_arch": [8]},
                seed=123,
                device="cpu",
                verbose=0,
            )
            model.learn(total_timesteps=16)
            model_path = Path(directory) / "ppo_smoke.zip"
            model.save(model_path)

            eval_env = _DeterministicContinuousEnv()
            reloaded = PPO.load(model_path, env=eval_env, device="cpu")
            observation, _ = eval_env.reset(seed=321)
            total_reward = 0.0
            for _ in range(eval_env.max_steps):
                action, _ = reloaded.predict(observation, deterministic=True)
                observation, reward, terminated, truncated, _ = eval_env.step(action)
                total_reward += reward
                if terminated or truncated:
                    break

            self.assertTrue(model_path.exists())
            self.assertTrue(np.isfinite(total_reward))
            self.assertGreater(eval_env.steps, 0)


class _DeterministicContinuousEnv(_GymEnvBase):
    metadata = {"render_modes": []}

    def __init__(self):
        self.observation_space = spaces.Box(low=-1.0, high=1.0, shape=(2,), dtype=np.float32)  # type: ignore[union-attr]
        self.action_space = spaces.Box(low=-1.0, high=1.0, shape=(1,), dtype=np.float32)  # type: ignore[union-attr]
        self.max_steps = 4
        self.steps = 0

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        self.steps = 0
        return np.array([0.0, 1.0], dtype=np.float32), {"reset_seed": seed}

    def step(self, action):
        self.steps += 1
        action_value = float(np.asarray(action, dtype=np.float64)[0])
        target = 0.25
        reward = 1.0 - abs(action_value - target)
        terminated = self.steps >= self.max_steps
        observation = np.array([self.steps / self.max_steps, target], dtype=np.float32)
        return observation, float(reward), terminated, False, {"target": target}


if __name__ == "__main__":
    unittest.main()
