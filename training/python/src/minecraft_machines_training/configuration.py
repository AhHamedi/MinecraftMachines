from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class BridgeConnectionConfig:
    host: str = "127.0.0.1"
    port: int = 0
    token: str = ""
    morphology: str = "minecraft_machines:duopod"
    timeout_seconds: float = 180.0

    def validate(self) -> None:
        if not self.host:
            raise ValueError("host must not be blank")
        if not 0 < self.port <= 65535:
            raise ValueError("port must be in 1..65535")
        if not self.token:
            raise ValueError("token must not be blank")
        if not self.morphology:
            raise ValueError("morphology must not be blank")
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")


@dataclass(frozen=True)
class PPOTrainingConfig:
    total_timesteps: int = 1_000_000
    n_steps: int = 256
    batch_size: int = 256
    n_epochs: int = 10
    learning_rate: float = 3.0e-4
    gamma: float = 0.99
    gae_lambda: float = 0.95
    clip_range: float = 0.2
    ent_coef: float = 0.005
    vf_coef: float = 0.5
    max_grad_norm: float = 0.5
    use_sde: bool = False
    sde_sample_freq: int = -1

    def validate(self, num_envs: int) -> None:
        if num_envs < 1:
            raise ValueError("num_envs must be positive")
        rollout = self.n_steps * num_envs
        if rollout <= 1:
            raise ValueError("n_steps * num_envs must be greater than 1")
        if self.batch_size < 1 or self.batch_size > rollout:
            raise ValueError("batch_size must be in 1..n_steps*num_envs")
        if rollout % self.batch_size != 0:
            raise ValueError("batch_size must divide n_steps*num_envs")
        if self.total_timesteps < 1:
            raise ValueError("total_timesteps must be positive")
        if self.n_epochs < 1:
            raise ValueError("n_epochs must be positive")
