# Minecraft Machines Python Training

This package connects Gymnasium and Stable-Baselines3 PPO to the live Minecraft Machines Duopod environment. The Java bridge now executes each action in dimension-level physics lockstep, but this repository does not yet contain a meaningful post-repair PPO locomotion model. Treat the package as a reproducible training/evaluation client, not as evidence that PPO has solved the task.

## Current Contract

The live server advertises specs and compatibility hashes during `HELLO` / `CREATE_SESSION`. Current Duopod values are:

```text
morphology:          minecraft_machines:duopod
observation schema:  minecraft_machines:duopod_locomotion v6, length 45
action schema:       minecraft_machines:duopod_servo_targets v3, length 2
action fields:       left_target, right_target
```

Never reuse a model whose metadata does not match the live morphology and both schema hashes. Historical June models/checkpoints predate this contract and are not valid performance evidence. Java CEM walk-forward checkpoints additionally have their own policy and fitness-protocol contract; a seven-value phase-gait JSON is not an SB3/PPO model and cannot be resumed by this package.

## Install And Test

```bash
cd training/python
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e ".[dev]"
```

Run the stdlib test suite from the repository root:

```bash
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

When Gymnasium and Stable-Baselines3 are installed, the same command also runs the optional real Gymnasium checker and a small local SB3 smoke test. Those tests validate wrapper compatibility; they do not prove live Minecraft locomotion.

Latest verified result is 75 passing Python tests with 7 optional tests skipped. The repository-level companion evidence is 93 passing common Java tests and 29 passing NeoForge GameTests. Test counts establish implementation coverage, not locomotion acceptance.

## Start A Live Bridge

Player-positioned:

```mcfunction
/mm train bridge start duopod <slotCount>
/mm train bridge start duopod <slotCount> <port>
```

Dedicated-server/console:

```mcfunction
/mm train bridge start duopod_at <x> <y> <z> <north|south|east|west> <slotCount>
/mm train bridge start duopod_at <x> <y> <z> <north|south|east|west> <slotCount> <port>
```

The requested origin only needs to be near clear supported Minecraft terrain. The Duopod spawn search checks a deterministic 16-block horizontal radius and does not generate or flatten terrain. The temporary identical flat lanes used by Java `walk_forward` CEM and its fixed benchmark are not part of bridge/PPO sessions; Python bridge curricula continue to use the existing world terrain.

The bridge binds to `127.0.0.1` and prints its random token only to the command source. Keep that token private.

### Lockstep behavior

`CREATE_SESSION` pauses the Sable physics system for the bridge's entire dimension. While Python is deciding on an action, all Sable physics and assembled servo/bearing kinetic ticks in that dimension remain frozen. A `STEP` applies the submitted action, advances exactly the advertised `controlTicks`, pauses physics again, and then returns the observation.

Successful step info contains:

```text
bridge_lockstep = true
actual_action_ticks = controlTicks
```

`GET_METRICS` also exposes those values and `physics_paused`. If the pause clock is disturbed, the bridge fails the pending step and closes the session. Closing restores the previous physics pause state.

This lock is global to the dimension, not private to the spawned Duopods. Use a dedicated dimension/session: do not run CEM, replay, unrelated Sable machines, or another physics controller there concurrently.

## Preflight

Print the live runbook and check environment variables, dependencies, model metadata, and (with `--connect`) live schema compatibility:

```bash
PYTHONPATH=training/python/src \
python3 -m minecraft_machines_training.live_runbook --mode all --check
```

Add `--connect` only after the in-game bridge is running.

## Train PPO

Use the host, port, token, and slot count printed by Minecraft:

```bash
python -m minecraft_machines_training.train_ppo \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --envs <slotCount> \
  --timesteps 1000000 \
  --curriculum minecraft_terrain_commands \
  --run-name duopod_ppo
```

Supported live training curricula include:

- `minecraft_terrain_commands`
- `walk_forward`
- `minecraft_terrain_point_goals`
- `balance_stand`
- `balance_center_of_mass`
- `manual_commands` through target APIs

Compatibility aliases `flat_commands`, `low_bumps_commands`, and `flat_point_goals` normalize to the corresponding Minecraft-terrain stage. They do not generate flat terrain or bumps.

The trainer writes under `runs/<run-name>`:

- `metadata.json`
- `metadata.training.json`
- `metadata.final.json`
- `model.metadata.json`
- `model.zip`
- TensorBoard logs
- periodic models and metadata sidecars in `checkpoints/`

The default checkpoint interval is 100,000 training steps. Pass `--checkpoint-interval 0` to disable periodic checkpoints.

Resume from a saved SB3 model or periodic checkpoint:

```bash
python -m minecraft_machines_training.train_ppo \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --envs <slotCount> \
  --timesteps 250000 \
  --curriculum minecraft_terrain_commands \
  --run-name duopod_ppo_resume \
  --resume-from runs/duopod_ppo/model.zip
```

When metadata is present beside the model or in its run directory, resume rejects morphology or schema-hash mismatches before calling the SB3 loader.

## Terminal And Time-Limit Semantics

Java returns separate `terminated` and `truncated` arrays plus terminal observations and post-reset observations. `MinecraftMachinesVecEnv` adapts those to SB3's single `done` without losing the distinction:

- `TimeLimit.truncated` is true only for `truncated && !terminated`;
- `terminal_observation` belongs to the completed episode;
- the observation returned for the next step is the separate auto-reset observation.

Terminal Java info is captured before auto-reset, so its episode id, phase, counters, return, and length are not accidentally replaced by the next episode's identity.

## Manual Commands And No-Reset Targets

`SET_TARGETS` resets the bridge session into fixed manual commands or local point goals.

`UPDATE_TARGETS` updates the active episode without respawning, resetting phase, clearing previous action, or zeroing accumulated reward. The Java environment re-baselines target distance immediately so the first reward after retargeting cannot receive a fake progress jump. Updates are rejected while a step is pending.

Python helpers:

- `MinecraftMachinesBridgeClient.update_targets` / `update_commands`
- `MinecraftMachinesVecEnv.update_targets` / `update_commands`
- `MinecraftMachinesEnv.update_target` / `update_command`

## Evaluate A Saved PPO Model

Evaluate the deterministic held-out point-goal manifest:

```bash
python -m minecraft_machines_training.evaluate \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --model runs/duopod_ppo/model.zip \
  --output runs/duopod_ppo/evaluation.json
```

The evaluator validates model metadata against live bridge specs before loading PPO. Use `--allow-missing-metadata` only to inspect a legacy model; it does not make that model valid acceptance evidence.

The default manifest is `duopod_held_out_point_goals_v3`: seven bearings (-75, -45, -15, 0, +15, +45, and +75 degrees) at 6, 10, and 12 blocks, for 21 scenarios. These bearing/distance pairs are not present in the enumerated training grid. Evaluation embeds the `duopod_planar_evaluation_v1` contract and checks that:

- measured reset distance matches the manifest within `1e-4`;
- initial/final distances and planar path length are finite and nonnegative;
- target progress cannot exceed measured path length by more than `1e-4`.

An invariant failure raises an error instead of producing a normal result. A completed valid payload includes `summary.evaluation_valid=true` and JSON/CSV per-episode metrics.

### Promote a best model

```bash
python -m minecraft_machines_training.evaluate \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --model runs/duopod_ppo/model.zip \
  --output runs/duopod_ppo/evaluation.json \
  --best-model-output runs/duopod_ppo/best_model.zip \
  --best-evaluation-output runs/duopod_ppo/best_evaluation.json
```

Best-model promotion is allowed only when `--episodes` is omitted/zero and exactly the full 21-scenario manifest was evaluated. A partial or repeated subset cannot be promoted. Among compatible full evaluations, promotion prefers higher success rate, then higher mean return, then lower mean final distance, and writes the model, metadata sidecar, and paired evaluation together.

## Validate A Target Change

```bash
python -m minecraft_machines_training.validate_target_change \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --model runs/duopod_ppo/model.zip \
  --output runs/duopod_ppo/target_change_validation.json \
  --switch-step 32 \
  --max-steps 96
```

The validator starts with an ahead-left local target, switches to ahead-right through `UPDATE_TARGETS`, feeds the returned updated observation into the next policy call, and records desired/observed yaw, actions, and episode/machine/phase continuity. Start the bridge with one slot for a simple operator demonstration.

## Compare Evaluation Artifacts

```bash
python -m minecraft_machines_training.compare \
  --left cem_eval.json \
  --right ppo_eval.json \
  --left-label cem \
  --right-label ppo \
  --output comparison.json
```

Comparison requires identical embedded manifest identity and episode order. It preserves policy provenance and reports summary plus per-scenario deltas.

## Java Walk-Forward Benchmark

The Java straight-crawl benchmark is separate from the Python point-goal evaluator and comparator. Its current contracts are:

```text
benchmark format:  minecraft_machines_duopod_walk_forward_benchmark_v3
policy type:       duopod_phase_gait_v2
fitness contract:  minecraft_machines:duopod_cem_fitness_v6
checkpoint format: minecraft_machines_duopod_cem_checkpoint_v4
training arena:    minecraft_machines:temporary_flat_duopod_lane_groups_v2
slot layout:       minecraft_machines:isolated_three_lane_groups_v1
acceptance rule:   post_warmup_stability_adjusted_anti_ballistic_v2
```

Benchmark v3 runs learned, neutral, and scripted controllers over the same three speed-indexed temporary lanes. It samples posture, height, displacement, and lane escape on every physics tick during held actions. Pairing requires matching episode IDs, seeds, spawn origins, and post-warmup baselines.

Before changing the world, the benchmark writes a recovery journal under `<world>/minecraft_machines/duopod_arena_recovery/`. Use a disposable development world and keep a backup. Do not pass walk-forward output to `minecraft_machines_training.compare`; that command validates the point-goal evaluation contract.

## Opt-In Live Tests

Basic socket/reset/step/target-update integration:

```bash
MM_TRAINING_BRIDGE_LIVE=1 \
MM_TRAINING_BRIDGE_HOST=127.0.0.1 \
MM_TRAINING_BRIDGE_PORT=<port> \
MM_TRAINING_BRIDGE_TOKEN=<token> \
PYTHONPATH=training/python/src \
python3 -m unittest training/python/tests/test_live_bridge.py
```

Enable the tiny live PPO smoke path with:

```bash
MM_TRAINING_BRIDGE_LIVE=1 \
MM_TRAINING_BRIDGE_LIVE_PPO=1 \
MM_TRAINING_BRIDGE_PORT=<port> \
MM_TRAINING_BRIDGE_TOKEN=<token> \
PYTHONPATH=training/python/src \
python3 -m unittest training/python/tests/test_live_bridge.py
```

Evaluate an existing live-compatible model in the gated test with:

```bash
MM_TRAINING_BRIDGE_LIVE=1 \
MM_TRAINING_BRIDGE_LIVE_EVAL=1 \
MM_TRAINING_BRIDGE_LIVE_MODEL=runs/duopod_ppo/model.zip \
MM_TRAINING_BRIDGE_PORT=<port> \
MM_TRAINING_BRIDGE_TOKEN=<token> \
PYTHONPATH=training/python/src \
python3 -m unittest training/python/tests/test_live_bridge.py
```

With pytest, the integration file is marked `live` and can be selected with `pytest -m live`.

## Honest Limitations

- No current sustained live PPO run demonstrates meaningful locomotion learning.
- Lockstep prevents action leakage, but bridge resets still respawn immediately and do not apply Java CEM's 20-tick scored warmup; reset transients need live PPO study.
- The bridge owns the entire dimension's Sable clock and supports one active session, so independent parallel physics workloads in that dimension are unsupported.
- Python wrappers store `seed` and `options` for Gym/SB3 API compatibility, but the current Java reset protocol does not consume them.
- Servo/load telemetry should be checked against live physics before using it as scientific evidence.
- The Java walk-forward benchmark is a controlled diagnostic, not a statistical robustness study.
