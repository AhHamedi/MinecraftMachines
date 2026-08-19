from __future__ import annotations

import argparse
from pathlib import Path

from .callbacks import MetadataCheckpointCallback
from .configuration import BridgeConnectionConfig, PPOTrainingConfig
from .metrics import write_json
from .training_metadata import (
    build_training_metadata,
    load_resume_metadata,
    update_training_metadata,
    validate_metadata_compatibility,
    write_model_metadata,
)
from .vec_env import MinecraftMachinesVecEnv


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--morphology", default="minecraft_machines:duopod")
    parser.add_argument("--envs", type=int, required=True)
    parser.add_argument("--timesteps", type=int, default=1_000_000)
    parser.add_argument("--curriculum", default="minecraft_terrain_commands")
    parser.add_argument("--run-name", default="duopod_ppo")
    parser.add_argument("--output-dir", type=Path, default=Path("runs"))
    parser.add_argument("--resume-from", type=Path)
    parser.add_argument("--checkpoint-interval", type=int, default=100_000, help="training steps between model checkpoints; 0 disables periodic checkpoints")
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--n-steps", type=int, default=256)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--n-epochs", type=int, default=10)
    parser.add_argument("--learning-rate", type=float, default=3.0e-4)
    parser.add_argument("--gamma", type=float, default=0.99)
    parser.add_argument("--gae-lambda", type=float, default=0.95)
    parser.add_argument("--clip-range", type=float, default=0.2)
    parser.add_argument("--ent-coef", type=float, default=0.005)
    parser.add_argument("--vf-coef", type=float, default=0.5)
    parser.add_argument("--max-grad-norm", type=float, default=0.5)
    parser.add_argument("--use-sde", action="store_true")
    parser.add_argument("--sde-sample-freq", type=int, default=-1)
    args = parser.parse_args(argv)

    ppo_config = PPOTrainingConfig(
        total_timesteps=args.timesteps,
        n_steps=args.n_steps,
        batch_size=args.batch_size,
        n_epochs=args.n_epochs,
        learning_rate=args.learning_rate,
        gamma=args.gamma,
        gae_lambda=args.gae_lambda,
        clip_range=args.clip_range,
        ent_coef=args.ent_coef,
        vf_coef=args.vf_coef,
        max_grad_norm=args.max_grad_norm,
        use_sde=args.use_sde,
        sde_sample_freq=args.sde_sample_freq,
    )
    ppo_config.validate(args.envs)
    if args.checkpoint_interval < 0:
        raise ValueError("checkpoint_interval must be non-negative")

    from stable_baselines3 import PPO
    import torch

    env = MinecraftMachinesVecEnv(BridgeConnectionConfig(args.host, args.port, args.token, args.morphology))
    try:
        if env.num_envs != args.envs:
            raise ValueError(f"bridge slot count {env.num_envs} does not match --envs {args.envs}")
        env.set_curriculum(args.curriculum)
        active_curriculum = getattr(env, "curriculum_stage", None) or args.curriculum
        run_dir = args.output_dir / args.run_name
        run_dir.mkdir(parents=True, exist_ok=True)
        model_path = run_dir / "model.zip"
        resume_metadata, resume_metadata_path = (None, None)
        if args.resume_from is not None:
            resume_metadata, resume_metadata_path = load_resume_metadata(args.resume_from)
            validate_metadata_compatibility(resume_metadata, morphology_id=args.morphology, specs=env.client.specs or {})
        metadata = build_training_metadata(
            morphology_id=args.morphology,
            curriculum_stage=active_curriculum,
            specs=env.client.specs or {},
            ppo_config=ppo_config,
            num_envs=env.num_envs,
            random_seed=args.seed,
            total_timesteps=ppo_config.total_timesteps,
            run_name=args.run_name,
            model_path=model_path,
            resume_from=args.resume_from,
        )
        metadata["resume_metadata_path"] = str(resume_metadata_path) if resume_metadata_path is not None else None
        write_json(run_dir / "metadata.json", metadata)

        if args.resume_from is not None:
            model = PPO.load(
                args.resume_from,
                env=env,
                tensorboard_log=str(run_dir / "tensorboard"),
                verbose=1,
            )
            reset_num_timesteps = False
        else:
            model = PPO(
                "MlpPolicy",
                env,
                learning_rate=ppo_config.learning_rate,
                n_steps=ppo_config.n_steps,
                batch_size=ppo_config.batch_size,
                n_epochs=ppo_config.n_epochs,
                gamma=ppo_config.gamma,
                gae_lambda=ppo_config.gae_lambda,
                clip_range=ppo_config.clip_range,
                ent_coef=ppo_config.ent_coef,
                vf_coef=ppo_config.vf_coef,
                max_grad_norm=ppo_config.max_grad_norm,
                use_sde=ppo_config.use_sde,
                sde_sample_freq=ppo_config.sde_sample_freq,
                policy_kwargs={"net_arch": [128, 128], "activation_fn": torch.nn.Tanh},
                tensorboard_log=str(run_dir / "tensorboard"),
                seed=args.seed,
                verbose=1,
            )
            reset_num_timesteps = True

        callback = MetadataCheckpointCallback(
            run_dir / "metadata.training.json",
            metadata,
            final_metadata_path=run_dir / "metadata.final.json",
            checkpoint_dir=run_dir / "checkpoints",
            checkpoint_interval_steps=args.checkpoint_interval,
            checkpoint_prefix=args.run_name,
        )
        model.learn(
            total_timesteps=ppo_config.total_timesteps,
            callback=callback,
            tb_log_name=args.run_name,
            reset_num_timesteps=reset_num_timesteps,
        )
        model.save(model_path)
        final_metadata = update_training_metadata(
            metadata,
            num_timesteps=getattr(model, "num_timesteps", None),
            model_path=model_path,
            latest_checkpoint_path=callback.latest_checkpoint_path,
            training_complete=True,
        )
        write_json(run_dir / "metadata.json", final_metadata)
        write_json(run_dir / "metadata.final.json", final_metadata)
        write_model_metadata(model_path, final_metadata)
    finally:
        env.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
