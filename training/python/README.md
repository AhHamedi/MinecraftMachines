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

Comparison requires identical embedded manifest identity and episode order. It preserves policy provenance and reports summary plus per-scenario deltas. Do not compare the repaired evaluator to old June artifacts even if their manifest name matches; their geometry and terminal accounting were different.

## Java Walk-Forward Publication Gate

The Java straight-crawl publication path is separate from the Python point-goal evaluator/comparator. Its current artifacts use:

```text
benchmark format:  minecraft_machines_duopod_walk_forward_benchmark_v3
policy type:       duopod_phase_gait_v2
fitness contract:  minecraft_machines:duopod_cem_fitness_v6
checkpoint format: minecraft_machines_duopod_cem_checkpoint_v4
training arena:    minecraft_machines:temporary_flat_duopod_lane_groups_v2
slot layout:       minecraft_machines:isolated_three_lane_groups_v1
acceptance rule:   post_warmup_stability_adjusted_anti_ballistic_v2
```

Benchmark v3 runs learned, neutral, and scripted controllers sequentially over the same three speed-indexed temporary lanes. Training places each candidate's three speeds in an isolated lane group separated from neighboring candidates by one unused lane-width. The arena, controlled morphology, and chunk lease share `(slot + floor(slot / 3)) * spacing`, and controlled spawn search must resolve exactly to `requested.above(3)` rather than silently choosing a nearby surface. Fitness v6 and the benchmark sample body-up, base-height excursion, displacement, and escape on every physics tick during each held-action interval, so a transient fall or launch cannot hide between control-step observations; missing/non-finite body-up data fails closed. Benchmark pairing still requires matching same-speed episode ids, seeds, spawn origins, and post-warmup baselines. Displacement and vertical excursion use the post-warmup handoff frame. A baseline that fails, escapes, falls below the posture gate, or exceeds the anti-ballistic vertical gate retains raw evidence but receives at most zero positive displacement in the comparison mean.

Publication eligibility is intentionally exact: checkpoint fitness v6, arena v2, slot layout v1, spacing 12, control interval 4, spawn warmup 20, horizon 200, and exactly three scenarios. Java rejects a different requested benchmark horizon or a checkpoint whose frozen training metadata does not match. Benchmark format remains v3; slot-layout and checkpoint-training-arena fields are backward-compatible additive provenance, so consumers must require them and fitness v6 rather than accepting every older v3 file as equivalent. Before arena mutation, Java forces a recovery journal under `<world>/minecraft_machines/duopod_arena_recovery/`; startup restores outstanding journals and fails closed if one cannot be reconciled. Residual P2 limitation: restored blocks are not explicitly forced to chunk storage before the forced journal is deleted, so an abrupt power loss in that narrow interval remains outside the durability guarantee. Keep a world backup for publication runs.

Each benchmark publishes UUID-addressed immutable JSON/CSV files plus compatibility `latest` files and `duopod_cem_walk_forward_benchmark_latest_manifest.json`. The manifest is written last as the atomic commit marker and hashes the canonical UTF-8 files including their trailing LF. Consumers must verify `commit_complete=true`, both SHA-256 values/byte counts, and one matching `benchmark_id` in the JSON, every CSV row, and the manifest.

Java checkpoint-format-v4 saves freeze a deep copy of the training config, including arena and slot-layout identity, so a later save cannot relabel an older candidate. Saves are forced, parsed, atomically promoted, and keep the previous valid primary as `<checkpoint>.bak`; loading recovers that backup when the primary is missing or corrupt. Compatible v3/v2/v1 files may still load as recovery/restart inputs, but loading or resaving cannot promote their historical score/provenance to v6. `replay_best` commands neutral for the checkpoint's recorded spawn-warmup ticks, captures a fresh handoff observation, and then follows the stored control cadence/gait frequency and initial action ramp. Replay is diagnostic, not acceptance evidence.

Do not pass a walk-forward benchmark JSON to `minecraft_machines_training.compare`. That Python command validates the 21-episode point-goal `duopod_planar_evaluation_v1` contract, not the Java 3x3 walk-forward gate. Follow the [live walk-forward checklist](../../docs/duopod-live-inspection-checklist.md) and the [training design](../../docs/locomotion-training-design.md) instead.

The tracked [attempt-01](../../portfolio/data/walk_forward_benchmark_attempt_01_failed.json) and [attempt-02](../../portfolio/data/walk_forward_benchmark_attempt_02_failed.json) artifacts are historical benchmark-v1 failures. The [v2 stage-1 failure](../../portfolio/data/walk_forward_benchmark_stage1_run_01_failed.json) and [v3 stage-1 failure](../../portfolio/data/walk_forward_benchmark_stage1_v3_run_01_failed.json) preserve the controlled-lane repair lineage. The later [per-tick candidate-01 JSON](../../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.json), [CSV](../../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.csv), and [manifest](../../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json) preserve UUID `eb0b3949-159a-4ad3-a7ae-92f991977b07`: `+4.5291456` learned mean blocks, but one failure, `-0.0047745` minimum body-up, `5.4926834`-block vertical excursion, and one escape. It used fitness v3 and arena v1.

The two accepted [pre-fix v5 run-01](../../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_01_accepted.json) and [run-02](../../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_02_accepted.json) artifacts are also historical and non-promotable. The arena grouped candidate triplets, but the morphology requested contiguous `slot * spacing` positions; the selected generation-2 candidate used slots 3–5, the first divergent triplet. The benchmark passes were real, but the claimed matched-training-topology provenance was false. V6 corrected the shared offsets/exact-center rule, treated the same genome as an unranked candidate, and re-certified it under the corrected topology.

The final v6 checkpoint comes from fresh CEM run `7bf2b216-3414-4030-88a8-3e6316823358`, seed `2026080301`, with population 32, 8 elites, and 12 generations. Selected generation 5 records aggregate `30.296754`, mean `16.582846`, worst `14.855634`, success rate `1.0`, failure rate `0.0`, and genome SHA-256 `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`. [Final run 01](../../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.json), UUID `c647d862-f7e4-4d34-8b69-2fcd0591edaf`, and [final run 02](../../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.json), UUID `6b5506a5-e24b-4df2-b972-49cbe6ed1a51`, both passed all eight criteria with learned mean/margin `+3.0775210`, minimum body-up `0.6082670`, peak vertical excursion `2.1274109`, and zero learned failures/escapes. Generation 8 had the higher training aggregate `36.5139` but was rejected because held-out minimum body-up `0.59605` missed the unchanged `0.60` gate. Both accepted runs used the same `minecraft:overworld` world with seed `-3368720904110701394`. This is fixed-world, fixed-protocol operational repeatability, not a multi-seed robustness study.

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
- The Java nine-episode paired `benchmark_walk_forward` command using benchmark format v3 and acceptance rule v2, not a Python smoke test or training curve, is the current publication gate for a learned straight-crawl controller. The final pair repeats one deterministic episode per controller/speed cell in one world; it is operational repeatability evidence, not a statistical robustness study.
