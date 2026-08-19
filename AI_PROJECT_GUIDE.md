# AI Project Guide: Minecraft Machines

This is the handoff document for agents working on this repository. Update it in the same change whenever architecture, commands, schemas, physics, rewards, training, evaluation, telemetry, dependencies, or verification behavior changes.

Last updated: 2026-08-03

## Current Truth

Minecraft Machines is a Minecraft 1.21.1 NeoForge robotics/embodied-AI experiment built on Create Aeronautics, Create Simulated, and Sable. It is not a VR addon and must not acquire VR dependencies.

The current portfolio target is deliberately narrow: train a two-servo Duopod to make measurable forward progress, then prove it with the fixed learned-vs-baselines walk-forward benchmark. The active publication contract is policy `duopod_phase_gait_v2`, fitness `minecraft_machines:duopod_cem_fitness_v6`, checkpoint format `minecraft_machines_duopod_cem_checkpoint_v4`, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, slot layout `minecraft_machines:isolated_three_lane_groups_v1`, benchmark format `minecraft_machines_duopod_walk_forward_benchmark_v3`, and acceptance rule `post_warmup_stability_adjusted_anti_ballistic_v2`. Fitness v6 samples body-up, base-height excursion, displacement, and arena escape on every server physics tick during each held-action interval. For controlled walk-forward work, the arena and morphology share the grouped right-offset function `(slot + floor(slot / 3)) * spacing`; the spawn search must resolve exactly to the prepared center `requested.above(3)` instead of silently shifting a machine to a nearby lane.

A fresh CEM search with explicit seed `2026080301` (run `7bf2b216-3414-4030-88a8-3e6316823358`) evaluated population 32 with 8 elites for all 12 generations. The promoted generation-5 [checkpoint](artifacts/duopod_walk_forward_v6/checkpoint/duopod_cem_fresh_v6_seed2026080301_accepted.json) records aggregate `30.296754`, mean `16.582846`, worst-speed score `14.855634`, success rate `1.0`, failure rate `0.0`, and genome SHA-256 `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`. Two fixed-world, fixed-protocol benchmark repeats then passed all eight criteria: [run 01](artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.json), UUID `c647d862-f7e4-4d34-8b69-2fcd0591edaf`, and [run 02](artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.json), UUID `6b5506a5-e24b-4df2-b972-49cbe6ed1a51`. Both measured learned mean/margin `+3.0775210`, minimum body-up `0.6082670`, peak vertical excursion `2.1274109`, per-speed forward displacement `3.659454 / 2.941055 / 2.632053`, and zero failures/escapes. This establishes a repeatable straight-crawl result in world seed `-3368720904110701394` under one exact protocol; it is not evidence of multi-world, multi-seed, uneven-terrain, turning, navigation robustness, or optimizer reliability across seeds. Code, tests, reward, or a healthy training generation alone are not locomotion acceptance evidence.

The evidence lineage is intentionally preserved rather than rewritten. [Attempt 01](portfolio/data/walk_forward_benchmark_attempt_01_failed.json) and [Attempt 02](portfolio/data/walk_forward_benchmark_attempt_02_failed.json) are benchmark-v1 failures. The v2 stage-1 repeats added controlled lanes and gate-aligned scoring but still failed stability and comparator criteria. The v3 stage-1 repeats produced `+6.2062302` learned mean blocks but failed minimum body-up at `0.3424959`. The later [per-tick candidate-01 artifact](portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.json), paired with its [commit manifest](portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json), exposed a hidden failure, `-0.0047745` minimum body-up, `5.4926834`-block peak vertical excursion, and an arena escape despite `+4.5291456` learned mean blocks. Its UUID is `eb0b3949-159a-4ad3-a7ae-92f991977b07`; it remains v3-fitness/v1-arena forensic evidence.

The two accepted pre-fix v5 benchmarks—UUIDs `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc`—also remain tracked, but they are historical and non-promotable. The v5 arena inserted a blank lane-width between three-speed candidate groups while the training morphology still requested contiguous `slot * spacing` origins. The selected generation-2 candidate was evaluated in local slots 3–5, the first triplet where those definitions diverged, while the benchmark used the correct first three lanes. Its benchmark pass was physically real, but its claimed matched-training-topology provenance was false. V6 fixed the shared offsets and exact-center requirement, and the earlier one-candidate generation-1 v6 checkpoint re-certified that legacy genome as an intermediate audit artifact. The current publication result supersedes it with the fresh seeded 12-generation search above. The highest-training-aggregate fresh candidate, generation 8 at `36.5139`, was not promoted because its held-out minimum body-up was `0.59605 < 0.60`; the unmodified gate selected generation 5 instead.

The June 2026 point-goal evidence, including the reported `4/21` held-out result, is invalid as current acceptance evidence. It predates the planar-target fix, evaluation invariants, current reward, current actuator limits, schema versions, failure-aware CEM fitness, and lockstep bridge. The tracked attempt checkpoints (`duopod_walk_forward_checkpoint_attempt_01.json`, the attempt-01 compatibility copy `duopod_walk_forward_checkpoint.json`, and `duopod_walk_forward_checkpoint_candidate_02.json`) preserve later forensic history, but their `duopod_phase_gait_v1` policy and old/unknown fitness contract make them ineligible for exact v6 optimizer resume or current benchmark-v3 evidence. The loader may use a compatible seven-value genome only as a widened restart anchor; its historical generation and fitness ranking are not retained as comparable v6 evidence.

## Repository Layout

Important paths:

- `settings.gradle.kts`: includes vendored Simulated/Aeronautics and the local modules.
- `vendor/Simulated-Project`: upstream source submodule. Do not modify it unless explicitly asked.
- `minecraft_machines/common`: shared mod code, schemas, rewards, environment, CEM primitives, and unit tests.
- `minecraft_machines/neoforge`: server integration, physical spawners, commands, live CEM, bridge, benchmark, and GameTests.
- `training/python`: Gymnasium/Stable-Baselines3 client package and tests.
- `docs`: design/history documents. Treat historical result claims as stale unless updated to the repaired benchmark.
- `README.md`: human-facing project overview.
- `AI_PROJECT_GUIDE.md`: this handoff document.

Gradle projects:

- `:simulated:common`
- `:simulated:neoforge`
- `:aeronautics:common`
- `:aeronautics:neoforge`
- `:minecraft_machines:common`
- `:minecraft_machines:neoforge`

Override the vendored Simulated checkout with:

```bash
./gradlew -PsimulatedProjectDir=/path/to/Simulated-Project projects
```

If the submodule is missing:

```bash
git submodule update --init --recursive
```

## Development Rules

1. Keep this guide synchronized with meaningful changes.
2. Preserve the servo-first architecture and use Sable/Simulated APIs instead of inventing a parallel physics layer.
3. Do not modify `vendor/Simulated-Project` or add VR dependencies without explicit user direction.
4. Treat training behavior as physics-sensitive. Compile and test after changes; use a real live run for claims about locomotion.
5. Never replay a checkpoint when its morphology, policy type, schema hashes, or curriculum do not match the active code. Optimizer resume additionally requires a matching fitness contract and fitness protocol; an unknown/incompatible score may seed a genome but must not be ranked against current fitness.
6. Do not infer success from training reward alone. The fixed benchmark is the publication gate for walk-forward locomotion.
7. Live bridge sessions pause the entire Sable physics system for their dimension between actions. Use a dimension dedicated to the bridge session.

## Build And Test Commands

```bash
./gradlew :minecraft_machines:neoforge:compileJava
./gradlew :minecraft_machines:common:test
./gradlew :minecraft_machines:neoforge:runGameTest
```

Development runs:

```bash
./gradlew :minecraft_machines:neoforge:runClient
./gradlew :minecraft_machines:neoforge:runClientAuth
./gradlew :minecraft_machines:neoforge:runServer
./gradlew :minecraft_machines:neoforge:runData
```

Python tests from the repository root:

```bash
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

Optional Gymnasium/SB3 tests run when those packages are installed; live bridge tests remain environment-gated.

Latest verified evidence for this contract is 93 passing common Java tests, 75 passing Python tests with 7 optional tests skipped, and 29 passing NeoForge GameTests. These counts verify implementation behavior, not benchmark acceptance.

## Runtime Entry Points

Common module, under `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines`:

- `MinecraftMachines.java`: mod id, registrate setup, blocks, and block entities.
- `content/servo`: Robotic Servo Joint implementation and telemetry.
- `content/worm`: worm collision tagging/filtering.
- `content/training/api`: specs, servo-first observation encoder, policies, and rewards.
- `content/training/environment`: vector environment, episodes, collision registry, and terminal semantics.
- `content/training/cem`: generic CEM distribution and failure-aware scoring.
- `content/training/morphology/duopod`: Duopod schemas, scenarios, rewards, planar math, phase gait, evaluation sanity checks, and benchmark acceptance rules.

NeoForge module, under `minecraft_machines/neoforge/src/main/java/dev/ahmedhamedi/minecraft_machines/neoforge`:

- `MinecraftMachinesNeoForge`: registers command and server-tick listeners with explicit priority.
- `MinecraftMachinesCommands`: `/mm` and `/minecraft_machines` command trees.
- `MinecraftMachinesSubLevelKineticTicker`: explicitly ticks servos and swivel bearings inside Sable sublevels; skips a dimension while its physics system is paused.
- `MinecraftMachinesWormSpawner` / `MinecraftMachinesWormCemTrainer`: legacy one-servo worm experiment.
- `MinecraftMachinesDuopodSpawner`: constructs the three-body physical Duopod and applies the training servo profile.
- `MinecraftMachinesDuopodControl`: resolves mirrored left/right servos and translates semantic policy actions to raw servo signs.
- `MinecraftMachinesDuopodTrainingMorphology`: live `TrainableMorphology` adapter, spawn/reset, observation, reward input, failures, and diagnostics.
- `MinecraftMachinesDuopodCemTrainer`: live Java CEM, checkpoints, evaluation, replay, and target-change validation.
- `MinecraftMachinesDuopodWalkForwardBenchmark`: fixed nine-episode learned-vs-baselines live benchmark and JSON/CSV writer.
- `MinecraftMachinesTrainingBridge`: loopback JSON socket bridge with dimension-level physics lockstep.

## Robotic Servo Joint

Public block id:

```text
minecraft_machines:robotic_servo_joint
```

The block extends Create's `DirectionalKineticBlock`, exposes one shaft connection on `FACING`, and behaves as a self-powered kinetic source rather than a passive shaft. It persists a `servoInstanceId`, can be resolved after assembly into a Sable sublevel, and can drive a side cog connected to a Simulated swivel bearing.

Important API on `RoboticServoJointBlockEntity`:

- `setTargetAngleDegrees`
- `setAngleLimitsDegrees`
- `setMaxAngularSpeedDegreesPerSecond`
- `setServoGains`
- `setMaxTorque`
- `setEnabled`
- `getTelemetry`

Telemetry includes requested/effective/actual angle, angular velocity, angle error, generated speed, estimated torque, joint load, enabled/assembled/constraint flags, angle limits, and link/sublevel ids.

Generic block defaults in `ServoJointDefaults` remain:

- limits: -90 to +90 degrees
- max speed: 180 degrees/second
- max torque: 10,000,000
- stiffness/damping/passive damping: 13,000 / 1,000 / 2

The physical Duopod overrides those defaults in `MinecraftMachinesDuopodSpawner`. These are the active training constants:

- semantic and raw target range: -60 to +60 degrees
- max angular speed: 90 degrees/second
- stiffness: 800
- damping: 350
- max torque: 75,000
- neutral target: 0 degrees

Do not restore the old +/-360-degree training range or the old 240-degree/second profile. Those settings allowed violent launch behavior and made the observation/action scaling physically meaningless.

Internal block `minecraft_machines:servo_joint_link` supports assembly and is not a public actuator.

## Sable And Simulated Integration

A Sable `ServerSubLevel` is a physics body. `SimAssemblyHelper.assembleFromSingleBlock(...)` moves assembled blocks into plot coordinates, so servos must usually be resolved by persistent instance or sublevel id rather than their original world block position. `MinecraftMachinesServoLocator` owns that lookup.

Servos implement the Sable assembly/actor callbacks. Ordinary world block-entity ticks are not sufficient after assembly, so `MinecraftMachinesSubLevelKineticTicker` advances Robotic Servo Joints and Simulated swivel bearings inside every active server sublevel. It must not tick them while `SubLevelPhysicsSystem.getPaused()` is true; that pause check is part of bridge lockstep correctness.

## Worm Architecture

The legacy worm remains useful as a servo/assembly diagnostic. It has two Sable bodies:

- Base: three iron blocks, one Robotic Servo Joint, a cogwheel, and a side-driven Simulated swivel bearing.
- Child: three iron blocks assembled by the bearing.

Requested layout:

```text
empty, empty, empty, empty, iron, iron, iron
empty, empty, empty, cogwheel, swivel bearing, empty, empty
iron, iron, iron, servo, empty, empty, empty
```

`MinecraftMachinesWormSpawner` places/glues the bearing child first and then assembles the base. Both sublevels are tagged as worm bodies. `WormCollisionCallback` suppresses only worm-vs-worm contact; worm-vs-world and worm-vs-unrelated-body contact remain enabled. Population spacing defaults to zero because same-batch bodies can overlap without colliding.

The legacy worm CEM still optimizes a four-parameter sinusoid (`amplitudeDeg`, `frequencyHz`, `phaseRad`, `biasDeg`) for +X displacement minus `0.15 * abs(sideways displacement)`. It is separate from the Duopod publication path.

## Training Collision And Terrain

`TrainingMachineCollisionRegistry` maps each owned sublevel to machine id, batch id, and morphology. Same-batch registered training machines do not collide with each other. Terrain, players, unrelated physics bodies, and other batches retain normal collision. Cleanup must unregister all owned sublevels.

The broad command, point-goal, balance, replay, and Python-bridge paths use ordinary Minecraft terrain. `TerrainProfile.none()` reports `minecraft_machines:no_training_terrain`, and the live morphology searches a deterministic square ring up to 16 horizontal blocks around a requested slot origin.

The Java `walk_forward` CEM and fixed walk-forward benchmark are the deliberate exception. `MinecraftMachinesDuopodFlatArena` identifies the arena as `minecraft_machines:temporary_flat_duopod_lane_groups_v2` and the slot topology as `minecraft_machines:isolated_three_lane_groups_v1`. It creates bounded identical smooth-stone lanes and inserts one unused lane-width between each three-speed candidate group. Arena preparation, controlled morphology spawn requests, and the chunk lease all use the same grouped slot-offset function. A controlled spawn must resolve to the exact prepared lane center; the ordinary 16-block terrain search remains available only to non-controlled paths. It snapshots every changed block, rejects block-entity positions before mutation, rolls back partial preparation, and restores the original world when the task closes. This is temporary experiment infrastructure, not permanent terrain generation. The trainer/benchmark also hold transient runtime chunk tickets for the full corridor and release only tickets they added; they never mutate persistent `/forceload` saved data.

Arena mutation has a forced pre-mutation recovery journal. Before changing a block, preparation durably writes an NBT journal under `<world>/minecraft_machines/duopod_arena_recovery/arena_<uuid>.nbt`. Normal close restores every original state before deleting the journal. At server startup, outstanding journals are validated and replayed in reverse mutation order; an unavailable dimension, malformed/duplicate entry, unsafe entry count, or failed restore keeps the journal and fails startup closed rather than allowing new training over an unreconciled corridor. Residual P2 limitation: restoration updates blocks in memory and removes the forced journal without explicitly forcing the affected chunks to disk first, so abrupt host power loss in that narrow interval could lose the restored state. Treat the mechanism as crash/startup recovery, not an absolute power-loss guarantee, and keep a world backup for publication runs.

Legacy curriculum names are compatibility aliases only:

- `flat_commands` and `low_bumps_commands` -> `minecraft_terrain_commands`
- `flat_point_goals` -> `minecraft_terrain_point_goals`

## Duopod Physical Design

The Duopod is low, wide, and symmetric around its spawn-forward axis. It owns:

- one central base Sable sublevel;
- one left bearing-child sublevel;
- one right bearing-child sublevel;
- one left and one right Robotic Servo Joint.

Each side is:

```text
outward-facing servo -> cogwheel -> outward-facing swivel bearing -> downward iron limb -> honey tip
```

The two bearing children are assembled before the central base. The tips start one block above the selected support surface. Physical reset respawns the bodies; snapshot restoration is intentionally disabled until swivel-constraint restore is proven safe.

Policy actions and servo observations are expressed in a mirrored semantic limb frame. Because the right servo faces the opposite direction, `MinecraftMachinesDuopodControl` inverts the right raw target and reflects right-side signed telemetry back before it reaches policy observations or diagnostics. Equal semantic left/right actions therefore mean the same limb-frame rotation, not the same raw motor sign.

For non-balance locomotion, a fall is held rather than triggered by a single tilted sample: `bodyUp < 0.25` and center of mass more than `0.50` blocks below its calibrated reference must persist for three control steps. Falling more than eight blocks below spawn or invalid control remains an immediate machine failure.

## Current Duopod Schemas

Schema identity is part of every checkpoint/model contract. Current values:

```text
observation id:      minecraft_machines:duopod_locomotion
observation version: 6
observation length:  45

action id:           minecraft_machines:duopod_servo_targets
action version:      3
action length:       2
action fields:       left_target, right_target
```

The 19 base observation fields are:

```text
phase_sin, phase_cos,
desired_forward_velocity, desired_lateral_velocity, desired_yaw_rate,
local_forward_velocity, local_lateral_velocity, local_vertical_velocity,
local_roll_rate, local_pitch_rate, local_yaw_rate,
projected_gravity_forward, projected_gravity_right,
standing_height_error,
center_of_mass_height_delta,
center_of_mass_forward_drift, center_of_mass_lateral_drift,
support_com_forward_error, support_com_lateral_error
```

The following 13 fields repeat for `left`, then `right`:

```text
target_angle, actual_angle, angle_error, angular_velocity, generated_speed,
estimated_torque, joint_load, minimum_angle_limit, maximum_angle_limit,
enabled, attached, valid_constraint, previous_action
```

All values are finite and clamped to `[-1, 1]`. Recoverable bad telemetry is replaced with zero and counted; three repaired observations in one episode cause `MACHINE_FAILURE`.

Version 6 / action version 3 reflect the current actuator range and normalization contract. The normalization constants now include joint angular velocity `120 deg/s`, generated speed `20 RPM`, and absolute servo-limit normalization `120 degrees`. Version 5 previously introduced aggregate COM fields but is no longer compatible. Old models and checkpoints must be rejected by schema hash.

The generic linear policy is:

```text
action = tanh(W * observation + bias)
genome size = actionSize * (observationSize + 1) = 92
```

The focused `walk_forward` curriculum instead uses `duopod_phase_gait_v2`, a seven-value sinusoidal gait genome. Its episode phase runs at the current `0.35 Hz` gait frequency. V2 decodes the normalized desired-forward observation back to blocks/second before calculating its speed fraction, allows a wider speed-amplitude gain, and is intentionally incompatible with v1 publication evidence.

## Vector Environment Semantics

`LocomotionVectorEnvironment<M>` is shared by Java CEM and the Python bridge. It:

- validates/clamps action batches;
- tracks phase, previous action, reward components, repaired observations, and task hold counters;
- distinguishes `terminated` task/physical endings from `truncated` time limits;
- returns the terminal observation/info and a separate post-reset observation;
- supports reset-all, masked reset, synchronous/asynchronous step, and no-respawn episode/target updates.

Terminal identity is frozen before auto-reset. A terminal info record retains the completed episode id, phase, distance, counters, return, and length; `resetObservations` and `auto_reset_episode_id` describe the next episode separately. Python's SB3 adapter sets `TimeLimit.truncated` only when Java says `truncated && !terminated` and attaches the terminal observation before substituting the reset observation.

## Locomotion Reward Repair

Command and point-goal tracking terms are baseline-centered against the stationary response. An upright, motionless policy no longer earns positive command-tracking or upright reward simply by waiting; it pays the small time/alive cost.

For `walk_forward`, reward motion is measured in the fixed spawn frame so the controller cannot rotate its body and redefine "forward." The direct progress term is signed forward velocity integrated over `deltaSeconds`, scaled by requested command magnitude, clamped, and multiplied by `bodyUpDotWorldUp` in `[0, 1]`. Forward motion while inverted or collapsed therefore does not earn the progress credit. Observations still use the current body frame.

Launch cost is time-scaled and applies only above the allowed upward speed (`0.75 blocks/s`) or allowed height above spawn (`1.25 blocks`). It no longer penalizes every small upward motion. Action-rate, failure, point-goal progress, and success terms remain explicit in reward telemetry.

## Java CEM

Raw `Config.DEFAULT` values:

- population: 64
- elites: 8
- generations: 50
- episode length: 160 server ticks
- control interval: 4 server ticks
- scenarios per candidate: 5
- max concurrent slots: 32
- spacing: 12
- spawn warmup: 20 server ticks
- default curriculum: `minecraft_terrain_commands`

With five scenarios and a 32-slot cap, the default active batch is 30 slots (six candidates). When the untouched default config switches curriculum, scenario count is specialized to three for `walk_forward` and eight for the two balance curricula; explicit user scenario counts are preserved. The Duopod CLI keeps `spacing=0` only as a compatibility sentinel that normalizes to 12; values 1–11 are rejected and 12–32 are explicit. Smaller population overrides scale elites toward approximately one quarter of population, capped at eight, while retaining at least two elites whenever population is greater than one. A publication-sized population of 32 therefore uses eight elites instead of a one-elite, zero-variance update.

Every curriculum rolls its scenario window, not only point goals. The generation/stage index advances through the complete scenario set for `minecraft_terrain_commands`, `walk_forward`, point goals, and both balance curricula. Bridge-created episodes also rotate scenario coverage across slots and subsequent episode cycles.

CEM applies the next action in the same `ServerTickEvent.Pre` that finishes the previous control step. This prevents one unaccounted physics tick under the old action. Spawned CEM batches are not scored during the configured 20-tick warmup; after it, the trainer refreshes observations, captures the post-warmup displacement/base-height baseline, and recalibrates standing references before scoring. Locomotion resets command neutral during that interval, while balance episodes may carry their configured disturbance/joint offsets. Under fitness v6, walk-forward body-up minimum, peak vertical excursion, displacement, and arena escape are sampled on every server physics tick of the held-action interval, not only at control-step boundaries; a transient fall or launch between observations therefore remains part of fitness. Missing or non-finite body-up samples fail closed.

Candidate fitness is failure-aware. `ScoredGenome` validates success/failure rates in `[0,1]` and ranks by:

```text
aggregate = meanScore + 0.25 * worstScore + 10 * successRate - 50 * failureRate
```

The 50x failure penalty prevents short, violent launch/fall policies from winning on transient displacement or reward. For `walk_forward`, each scenario return is first shaped by `DuopodWalkForwardFitness`: terminal forward displacement is measured from the post-warmup handoff and has weight 4; stable positive progress adds 4; failure or minimum body-up below 0.60 adds a 6-point instability penalty plus a 10x upright shortfall; peak vertical excursion above 3.0 blocks and arena escape are explicitly penalized; and unstable positive displacement is capped to zero credit. The success rate used above is therefore the same `no failure && minimumBodyUp >= 0.60 && peakVerticalExcursion <= 3.0 && !arenaEscape && postWarmupForward > 0` gate used to select useful publication candidates. Slot logs report dense return, selection return, post-warmup and raw spawn-frame displacement, minimum body-up, peak vertical excursion, arena escape, gate success, and the terminal adjustment.

New saves use:

```text
minecraft_machines_duopod_cem_checkpoint_v4
```

Checkpoint format v4 contains policy type, schema hashes/specs, normalized curriculum, best genome and scores, `failureRate`, an immutable deep-copied training-config snapshot, distribution snapshot, evaluation-manifest metadata, and—on current saves—`fitnessContract=minecraft_machines:duopod_cem_fitness_v6`. The snapshot preserves the arena and slot-layout identities that actually produced the candidate; saving later cannot relabel an old genome with current settings. `runSeed` remains the backward-compatible immutable champion-discovery seed, `discoveryRunSeed` names that meaning explicitly, and `optimizerSeed` records the optimizer stream represented by the saved distribution. These fields make provenance truthful, but a mid-run load does not restore the Java RNG's complete internal state and therefore does not reproduce the exact future sample trajectory. Protocol-compatible resume preserves distribution state, not bit-exact stochastic continuation. Exact optimizer resume requires matching policy family, curriculum, fitness contract, episode/control ticks, scenario count, warmup, spacing, and point-goal distances. Publication eligibility is stricter and separately requires the frozen current arena and slot-layout identities. A mismatch or unknown legacy contract anchors a compatible genome, resets generation and best-fitness scale, and widens exploration; a phase-gait protocol restart uses at least `0.60` standard deviation with a `0.12` floor. `/mm train duopod cem start_fresh_at ...` bypasses the in-memory checkpoint without deleting or rewriting the checkpoint file. The loader remains backward-readable for compatible v3/v2/v1 files, but those formats do not become v6 publication evidence by loading or resaving. Older schema-incompatible June checkpoints remain unusable.

Checkpoint writes are recoverable. The writer creates and forces a temporary UTF-8 file, parses it back through all checkpoint invariants, preserves the previous valid primary as `<checkpoint>.bak`, atomically replaces the primary, and validates the promoted file. Loading tries the primary first and automatically recovers a valid `.bak` if the primary is missing or corrupt; it logs the recovery source. Never delete a backup merely because the primary failed validation.

Vectorized CEM batches and the fixed benchmark acquire transient, ticking chunk tickets for their complete spawn/search/travel corridor before assembly, synchronously load those chunks, wait until they are position-ticking, and retain the lease across all CEM batches or all three benchmark controller batches. They release only tickets acquired by that task on every close path; these tickets are not persistent `/forceload` state. Spawn assembly is transactional so a failed limb/base/servo initialization cannot leave unowned Sable bodies behind. The walk-forward arena is likewise held for the complete task, then restored after all environments are destroyed.

Checkpoints are world-local under `<world>/minecraft_machines/`. Named saves use `duopod_cem_<name>.json`; recovery copies append `.bak` to the complete filename.

Supported curricula:

- `minecraft_terrain_commands`: five straight/turn/rotate commands.
- `walk_forward`: three straight commands at 0.50, 0.80, and 1.10 blocks/second with the seven-value phase gait.
- `minecraft_terrain_point_goals`: fourteen local targets (seven bearings, two distances).
- `balance_stand`: disturbance recovery with support, honey-contact, uprightness, COM, and settled-motion terms.
- `balance_center_of_mass`: simpler COM-height/stillness A/B objective.

## Training And Debug Commands

Commands are registered under `/mm` and `/minecraft_machines`; use `/mm` normally.

Physical/debug commands:

```mcfunction
/mm spawn_test_block
/mm spawn_servo_test
/mm spawn_servo_swing_test
/mm spawn_servo_diagnostic_test
/mm spawn_worm
/mm duopod spawn
/mm duopod remove_nearest
/mm duopod control_sweep
/mm duopod control_sweep_at <x> <y> <z> <north|south|east|west>
/mm duopod telemetry on
/mm duopod telemetry off
/mm stop_servo_demos
```

Servo telemetry:

```mcfunction
/mm servo_telemetry_start
/mm servo_telemetry_stop
/mm servo_telemetry_once
```

Generic training control:

```mcfunction
/mm train status
/mm train stop
/mm train clear
/mm train target <x> <y> <z>
/mm train look_target [on|off]
/mm train reward_viz [on|off]
/mm train cem start duopod [population] [generations] [episodeTicks] [controlTicks] [scenarios] [spacing]
/mm train cem stop
/mm train cem status
/mm train cem replay_best
/mm train cem save [name]
/mm train cem load [name]
```

Canonical Duopod CEM commands:

```mcfunction
/mm train duopod cem start [population] [generations] [episodeTicks] [controlTicks] [scenarios] [spacing] [curriculum]
/mm train duopod cem start <...> max_slots <maxConcurrentSlots> [curriculum]
/mm train duopod cem start <...> <curriculum> targets <nearBlocks> <farBlocks>
/mm train duopod cem start_at <x> <y> <z> <north|south|east|west> [population] [generations] [episodeTicks] [controlTicks] [scenarios] [spacing]
/mm train duopod cem start_at <...> max_slots <maxConcurrentSlots> [curriculum]
/mm train duopod cem start_fresh
/mm train duopod cem start_fresh_at <x> <y> <z> <north|south|east|west> <population> <generations> <episodeTicks> <controlTicks> <scenarios> <spacing> max_slots <maxConcurrentSlots> <curriculum>
/mm train duopod cem stop
/mm train duopod cem status
/mm train duopod cem replay_best
/mm train duopod cem replay_target <forwardBlocks> <rightBlocks>
/mm train duopod cem evaluate_best [maxControlSteps]
/mm train duopod cem evaluate_best_at <x> <y> <z> <north|south|east|west> [maxControlSteps]
/mm train duopod cem benchmark_walk_forward [maxControlSteps]
/mm train duopod cem benchmark_walk_forward_at <x> <y> <z> <north|south|east|west> [maxControlSteps]
/mm train duopod cem validate_target_change [switchControlStep] [maxControlSteps]
/mm train duopod cem validate_target_change_at <x> <y> <z> <north|south|east|west> [switchControlStep] [maxControlSteps]
/mm train duopod cem look_target [on|off]
/mm train duopod cem reward_viz [on|off]
/mm train duopod cem save [name]
/mm train duopod cem load [name]
/mm train duopod cem save_checkpoint  # legacy no-name alias
/mm train duopod cem load_checkpoint  # legacy no-name alias
/mm train duopod cem clear
```

The detailed argument tree is positional. `start_fresh` uses the unmodified default config; the fully specified `start_fresh_at` form is the publication-run entry point. It never initializes from the loaded checkpoint and leaves the checkpoint file unchanged. The current controlled walk-forward protocol is:

```mcfunction
/mm train duopod cem start_fresh_at <x> <y> <z> <north|south|east|west> 32 12 800 4 3 12 max_slots 24 walk_forward seed <seed>
```

This gives each candidate the same three speed scenarios in an isolated three-lane group and matches the benchmark's 200-control-step horizon. A protocol-compatible v6 resume uses the identical arguments with `start_at` instead of `start_fresh_at`. Choose a bounded area without block entities. The temporary arena restores normal blocks on close, while its forced pre-mutation journal supports startup recovery after a process crash; any unrecovered journal blocks training fail-closed. Because chunk restoration is not explicitly forced before journal deletion, retain a world backup against the narrow abrupt-power-loss window described above.

`replay_best` uses the checkpoint's recorded control cadence and gait frequency. It commands neutral for the recorded spawn-warmup ticks, captures a fresh handoff observation after warmup, then begins the same five-step action ramp used by training. Replay remains a visual diagnostic and cannot substitute for the fixed benchmark.

Legacy worm CEM commands remain under `/mm worm_cem`, including `start`, `stop`, `status`, `replay_best`, `clear`, and `servo status|set|reset`.

## Fixed Walk-Forward Benchmark

The portfolio-facing benchmark requires an in-memory or loaded current best with:

- curriculum `walk_forward`;
- policy `duopod_phase_gait_v2`;
- fitness contract `minecraft_machines:duopod_cem_fitness_v6`;
- training arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`;
- slot layout `minecraft_machines:isolated_three_lane_groups_v1`;
- spacing 12, control interval 4, warmup 20, horizon 200, and exactly three scenarios per candidate;
- current schema hashes.

Run as a player:

```mcfunction
/mm train duopod cem benchmark_walk_forward
/mm train duopod cem benchmark_walk_forward <maxControlSteps>
```

Run from console/headless:

```mcfunction
/mm train duopod cem benchmark_walk_forward_at <x> <y> <z> <north|south|east|west>
/mm train duopod cem benchmark_walk_forward_at <x> <y> <z> <north|south|east|west> <maxControlSteps>
```

The only publication horizon is 200 control steps; an explicitly supplied different value is rejected. Benchmark format v3 is a fixed 3x3 comparison: learned, neutral, and `scripted_alternating_sine` controllers at requested speeds 0.50, 0.80, and 1.10 blocks/second. The v6 slot-layout and checkpoint-training-arena fields are backward-compatible additive provenance inside format v3; consumers must require them and fitness v6 instead of treating every older v3 artifact as equivalent. The benchmark prepares three speed-indexed flat lanes once, runs the three controllers as sequential batches over those same lanes, and destroys each controller environment before spawning the next. Same-speed episodes must have identical episode ids, seeds, requested origins, actual spawn origins, and post-warmup baselines within `1e-6`, or artifact generation fails. The scripted baseline is `left=0.65*sin(phase), right=-left`.

Each run receives a UUID `benchmark_id` and publishes an immutable JSON/CSV pair under the active world:

```text
minecraft_machines/duopod_cem_walk_forward_benchmark_<benchmark_id>.json
minecraft_machines/duopod_cem_walk_forward_benchmark_<benchmark_id>.csv
minecraft_machines/duopod_cem_walk_forward_benchmark_latest.json
minecraft_machines/duopod_cem_walk_forward_benchmark_latest.csv
minecraft_machines/duopod_cem_walk_forward_benchmark_latest_manifest.json
```

The UUID is embedded in the JSON and every CSV row. The compatibility `latest` files are replaced before the manifest; the manifest is the atomic commit marker written last, contains `commit_complete=true`, and records SHA-256 hashes, byte counts, contract identity, and provenance for both immutable and compatibility files. JSON and manifest serialize as canonical UTF-8 with a trailing LF, which is included in their hashes. Readers must require one matching UUID across JSON, CSV, and manifest and verify both manifest hashes. A crash may leave an uncommitted immutable half-pair or stale compatibility file, but it cannot advance the manifest to an incomplete pair.

Acceptance requires all of the following:

- exact nine-episode coverage and finite/bounded metrics;
- learned mean forward displacement at least 0.50 blocks above the stronger stability-adjusted baseline;
- zero learned machine failures;
- positive learned forward displacement at each of the three speeds;
- learned minimum `body_up_dot_world_up >= 0.60`;
- learned maximum post-warmup vertical excursion `<= 3.0` blocks;
- zero learned arena escapes.

The acceptance rule is `post_warmup_stability_adjusted_anti_ballistic_v2`: a baseline episode that fails, escapes, drops below 0.60 body-up, or exceeds 3.0 blocks of post-warmup vertical excursion keeps its raw evidence but has positive comparator credit capped at zero. This prevents the ballistic attempt-02 scripted controller from setting a false `+10.332`-block comparator while preserving the physical failure.

The artifact freezes the first terminal/truncation state and reports return, post-warmup forward/lateral displacement, raw spawn-frame displacement, per-physics-tick post-warmup vertical excursion/body-up/escape extrema, pose-sample count, action total variation, action saturation, checkpoint/genome provenance, world/arena/lane provenance, thresholds, every criterion, and failed criteria. It writes a UTC `generated_at`; when the runtime cannot resolve a revision, `code_revision` is explicitly null with a status rather than invented. Training reward is not a substitute for this gate. The final v6 artifacts are the accepted fresh-search run-01/run-02 pair listed under Current Truth; their manifests report `commit_complete=true` and are transitively hash-bound by [the bundle evidence manifest](artifacts/duopod_walk_forward_v6/evidence_manifest.json).

## Point-Goal Geometry And Evaluation Invariants

World target placement and target-relative transforms now use a normalized horizontal body frame. `DuopodPlanarMath` projects the quaternion-derived forward vector into XZ, normalizes it, derives an orthonormal right axis, and falls back to the projected right vector only when forward is vertically degenerate. World Y remains vertical. This prevents pitch/roll from shrinking, stretching, or lifting local planar targets.

Java and Python evaluation enforce:

- reset `distance_to_target` must match the manifest distance within `1e-4`;
- initial, final, and travelled distances must be finite and nonnegative;
- progress toward target cannot exceed measured planar path length by more than `1e-4`;
- success and failure rates must remain within `[0,1]`;
- each slot freezes its first terminal result, so auto-reset episodes cannot add reward/success repeatedly.

Java rejects the evaluation instead of writing a normal artifact when these invariants fail. Python emits `summary.evaluation_valid=true` only after the same geometry checks pass. Python best-model promotion additionally requires `--episodes 0` and exactly the complete held-out manifest; partial/lucky evaluations cannot replace the best model.

The shared held-out manifest is `duopod_held_out_point_goals_v3`, with 21 genuinely unseen training-grid pairs: bearings -75, -45, -15, 0, +15, +45, and +75 degrees at distances 6, 10, and 12 blocks. Evaluation artifacts embed the `duopod_planar_evaluation_v1` contract (`world_horizontal_xz` geometry and first-terminal sampling); comparison rejects missing, different, or unsupported contracts. Historical v2 artifacts remain invalid and are intentionally uncomparable.

## PPO Bridge Lockstep

Bridge commands:

```mcfunction
/mm train bridge start duopod <slotCount> [port]
/mm train bridge start duopod_at <x> <y> <z> <north|south|east|west> <slotCount> [port]
/mm train bridge status
/mm train bridge stop
```

Transport and safety:

- binds only to `127.0.0.1`;
- uses length-prefixed UTF-8 JSON with bounded message size;
- prints a one-time token only to the command source;
- requires authenticated `CREATE_SESSION` and matching protocol/schema hashes;
- queues every world mutation onto the server thread;
- permits only one session and one outstanding `STEP`;
- closes and destroys owned bodies on disconnect, timeout, close, or stop.

Temporal behavior is now exact lockstep:

1. `CREATE_SESSION` acquires the Sable `SubLevelPhysicsSystem` for the bridge dimension. It rejects an already-paused system, pauses it before returning, and advertises `lockstep=true`, `physicsPaused=true`.
2. While waiting for a client action, the entire dimension's Sable physics and sublevel kinetic ticker remain paused.
3. `STEP` validates/applies the action, unpauses the dimension, holds that action for exactly `controlTicks` server-tick intervals, then pauses before collecting/returning the observation.
4. Every step info reports `bridge_lockstep=true` and `actual_action_ticks`; `GET_METRICS` also reports those fields plus `physics_paused`.
5. An unexpected pause-state change fails the step and closes the session. Close restores the physics system's previous pause state.

This is global to the dimension, not isolated per Duopod. Do not run unrelated Sable machines, CEM, replay, or another physics controller in that dimension during a bridge session. The Minecraft server can keep running, but the dimension's Sable simulation intentionally freezes while Python is thinking.

Protocol messages:

```text
HELLO, CREATE_SESSION, GET_SPECS, RESET_ALL, RESET_MASK, STEP,
SET_CURRICULUM, SET_TARGETS, UPDATE_TARGETS, GET_METRICS,
CLOSE_SESSION, PING, ERROR
```

`SET_TARGETS` resets into manual command/point-goal episodes. `UPDATE_TARGETS` changes the active definitions without respawn, phase reset, previous-action reset, or accumulated-reward reset, and immediately re-baselines target distance. Updates are rejected during a pending step.

Bridge scenario assignment rotates all supported training curricula over slots and successive episode cycles. Seeds/options stored by the Python wrappers are not currently consumed by the Java reset protocol.

## Python Training Package

Install:

```bash
cd training/python
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e ".[dev]"
```

Train:

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

The trainer writes metadata, final model sidecars, TensorBoard logs, and periodic checkpoints. Resume validates morphology and schema hashes before loading. `evaluation.py` owns the deterministic manifest; `evaluate.py` validates model metadata and live schemas, evaluates deterministic actions, checks geometry invariants, and writes JSON/CSV. `compare.py` rejects mismatched manifests/order. `validate_target_change.py` tests no-respawn retargeting and continuity.

Honest limitations:

- There is no meaningful, current, bridge-trained PPO locomotion model in this repository.
- Lockstep correctness is implemented and covered by mock/unit/GameTest code, but it still needs a new sustained live PPO training/evaluation run before any performance claim.
- Bridge resets respawn physical bodies and do not provide CEM's scored 20-tick spawn warmup; reset transients remain a PPO experiment concern.
- One bridge session owns the whole dimension's Sable clock, so parallel independent physics training in that dimension is unsupported.
- Servo telemetry is the project's proprioceptive interface, but load/constraint signals depend on the current Sable/Create integration and should be validated live before being used as a portfolio result.

See `training/python/README.md` for the Python runbook.

## Telemetry And Logs

Useful prefixes in `latest.log`:

```text
MM_SERVO_TELEMETRY
MM_WORM_CEM
MM_DUOPOD_CONTROL_SWEEP
MM_DUOPOD_CEM
MM_DUOPOD_WALK_FORWARD_BENCHMARK
MM_TRAINING_BRIDGE
```

Duopod diagnostics include base position, current-body and spawn-frame velocities, spawn-frame forward/lateral displacement, desired command, projected gravity, body-up, standing/COM/support metrics, target position/distance for point goals, left/right servo angle/load/velocity, and applied semantic actions.

`reward_viz` is optional and useful for debugging, not evidence. It renders command/target, actual motion, and reward sign for one representative CEM slot or replay.

## Verification Coverage To Preserve

The latest verified run contains 93 passing common Java tests, 75 passing Python tests with 7 optional skips, and 29 passing NeoForge GameTests.

Common unit tests cover servo math; schema field order/version/size; observation bounds/repair; target transforms; planar geometry; reward stationary-vs-forward ordering; deterministic scenarios and rolling coverage; vector reset/step/terminal identity; CEM distribution and failure-aware fitness; v2 phase-gait decoding and structured probes; gate-aligned walk-forward terminal fitness; evaluation invariants and terminal freezing; and the stability-adjusted benchmark acceptance rule.

Python tests cover framing/client validation, mock Gym/SB3 wrappers, correct `TimeLimit.truncated`, metadata/schema compatibility, train/resume output, target updates, deterministic evaluation metrics and invariants, full-manifest promotion, comparison, target-change validation, optional dependency checks, and opt-in live bridge scaffolding.

NeoForge GameTests cover servo/bearing assembly, sublevel servo ticking, Duopod physical ownership/control/cleanup/collision, transactional spawn rollback, vector-environment live reset/step, terrain invariants, task-scoped chunk activation/release, lane-group geometry, crash-journal recovery, controlled-arena restoration, per-tick extrema, immutable paired benchmark provenance/manifest publication, atomic checkpoint backup recovery, replay warmup/cadence, CEM/evaluation/target-change command paths, and bridge socket lockstep. The bridge test checks that idle physics is paused, a step reports exactly `controlTicks`, and metrics report paused lockstep state.

Do not claim the fixed live benchmark passed from unit or GameTest coverage. Only a real benchmark JSON with `accepted=true` supports that claim.

## Historical Evidence Policy

The following are forensic history, not current acceptance:

- June run ids and reward curves;
- the June `4/21` point-goal success report;
- the old no-respawn yaw/action delta;
- any success rate outside `[0,1]` or produced after an auto-reset in the same evaluation slot;
- v1/v2 checkpoints created before observation v6/action v3, regardless of filename;
- the tracked attempt-01 and attempt-02 walk-forward v1 checkpoints/benchmarks as evidence for the v2 policy, fitness contract, arena, or comparator;
- the v2 controlled-lane stage-1 checkpoint and repeated benchmark-v2 failures as v6 evidence;
- the v3 stage-1/final checkpoints and benchmark-v3 failures as v6 evidence, including UUID `eb0b3949-159a-4ad3-a7ae-92f991977b07`, which used fitness v3 and arena v1 even though its benchmark sampled pose metrics per physics tick;
- the accepted pre-fix v5 benchmark UUIDs `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc`; they are non-promotable because their grouped arena and contiguous morphology offsets diverged for the selected slots 3–5;
- old evaluation JSON/CSV, videos, and screenshots produced with the previous target frame or actuator range.

Reasons for invalidation are concrete: tilted 3D body axes were used as planar target axes, terminal slots could continue contributing after auto-reset, reset distance/path plausibility was not enforced, stationary reward was attractive, violent failures were not part of aggregate CEM fitness, PPO steps were not dimension-lockstep, and the physical/schema contract has since changed. For the two August v1 walk-forward attempts specifically, controller/candidate identity was also confounded with arbitrary spawn-site geometry; neutral displacement varied by several blocks across sites, the training success/failure threshold did not match the publication body-up gate, the desired-speed gene decoded the normalized observation on the wrong scale, and exploration collapsed toward nearly identical actions.

New training evidence must record code/schema identity, use checkpoint format v4 with fitness `minecraft_machines:duopod_cem_fitness_v6`, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, and slot layout `minecraft_machines:isolated_three_lane_groups_v1`, pass the applicable invariants, and—when claiming walk-forward learning—pass benchmark format v3 under acceptance rule v2. Publication eligibility is exact: spacing 12, control interval 4, warmup 20, horizon 200, and three speed scenarios. Repeating the benchmark creates another immutable UUID pair and improves operational confidence, but these two repeats still contain one deterministic episode per controller/speed cell in one fixed world and are not a statistical robustness claim.

## Known Design Decisions And Limitations

- The servo is a self-powered Create kinetic source with one output shaft.
- Duopod locomotion is servo-first; optional sensor blocks are future work.
- Normal Minecraft terrain remains the broad target; Java walk-forward CEM/benchmark deliberately use temporary, reversible lane groups to isolate controller causality.
- Duopod physical reset is respawn-based.
- Same-batch collision is suppressed; world collision is preserved.
- Walk-forward progress is spawn-frame and upright-gated.
- CEM failures and per-physics-tick gate-aligned walk-forward extrema are first-class fitness data and identified by fitness v6 in checkpoint-format-v4 saves.
- Point-goal placement is planar and evaluation is invariant-checked.
- The Python bridge is exact dimension-level lockstep, not per-machine asynchronous simulation.
- Current publication scope is a controlled straight-crawl benchmark. Turning, uneven-terrain generalization, robust navigation, balance recovery, and meaningful PPO learning remain unproven. Two accepted fixed-world 3x3 repeats are operational repeatability evidence, not a multi-seed confidence interval.

## Update Checklist

Update this file when any of these change:

- command syntax or artifact paths;
- servo limits, speed, gains, torque, or telemetry semantics;
- Duopod morphology, axes, reset, collision, or failure logic;
- observation/action schema versions, fields, or normalization;
- reward terms, scenario schedule, CEM defaults, scoring, or checkpoint format;
- bridge scheduling, pause ownership, or protocol telemetry;
- evaluation/benchmark scenarios, invariants, thresholds, or promotion rules;
- dependencies, Gradle layout, test commands, or verified live evidence.
