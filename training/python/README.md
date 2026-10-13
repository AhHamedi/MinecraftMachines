# Duopod training

Gymnasium and Stable-Baselines3 PPO tools for training the Duopod in Minecraft.

## Install

Use Python 3.9 or later. From the repository root:

```sh
cd training/python
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e .
```

Run the remaining terminal commands from this directory with the environment active.

## Start the bridge

In Minecraft, stand near clear supported ground and run:

```text
/mm train bridge start duopod 4
```

The bridge listens on `127.0.0.1` and prints its port and token. Replace `PORT`
and `TOKEN` below with those values. Keep `--envs` equal to the bridge's slot count.
For a server console, use `/mm train bridge start duopod_at <x> <y> <z> north 4`.

Training pauses Sable physics throughout its dimension between actions. Use a
separate test world or dimension without other active physics experiments.
Stop the bridge with `/mm train bridge stop` when finished.

## Train

```sh
python -m minecraft_machines_training.train_ppo \
  --port PORT --token TOKEN --envs 4 \
  --timesteps 1000000 --run-name duopod_ppo
```

Models, checkpoints and logs are saved in `runs/duopod_ppo/`.
Add `--resume-from runs/duopod_ppo/model.zip` to resume a saved model.

## Evaluate

```sh
python -m minecraft_machines_training.evaluate \
  --port PORT --token TOKEN \
  --model runs/duopod_ppo/model.zip \
  --output runs/duopod_ppo/evaluation.json
```

Keep the model's metadata files beside it; evaluation checks them against the
running bridge. Both commands support `--help` for additional options.
