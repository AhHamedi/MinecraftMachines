# Locomotion Training Design

Last updated: 2026-08-03

## Current Worm Architecture

Minecraft Machines currently has a one-servo worm built from a base Sable sublevel and one Simulated swivel-bearing child sublevel. The block pattern is spawned in `MinecraftMachinesWormSpawner`: a Robotic Servo Joint drives a side cog, the cog drives a Simulated Swivel Bearing, the bearing assembles its front iron row as the child body, and `SimAssemblyHelper.assembleFromSingleBlock(...)` assembles the glued servo/cog/bearing base as the parent body. The worm CEM trainer spawns a batch of worms, applies candidate sinusoidal servo targets every server tick, scores displacement along +X with a small sideways penalty, and updates a four-parameter CEM distribution.

The existing servo is already suitable as a morphology actuator. Its block entity exposes target angles, configured limits, actual angle, angular velocity, estimated torque/load, enable state, and persistent servo instance IDs for lookup after Sable moves the block into a sublevel.

Existing worm collision suppression is intentionally narrow: worm-tagged Sable sublevels do not collide with other worm-tagged sublevels, while worm-vs-world and worm-vs-non-worm contacts remain normal.

## Generic Environment Interfaces

The shared locomotion path is:

target point or sampled movement command -> deterministic command generator -> `LocomotionCommand` -> optimizer-independent locomotion environment -> policy -> normalized actuator action vector -> morphology-specific action decoder -> servo target angles -> physics -> observation/reward/termination.

Generic code owns schemas, episode runtime, reward breakdowns, vectorized slot stepping, curricula, terrain profile metadata, and CEM. It must not assume a Duopod, two actuators, or fixed vector sizes. A morphology adapter supplies spawn/control, observation encoding, action decoding, reward, health checks, reset, and cleanup.

The framework is servo-first. Discovered Robotic Servo Joints define both the action dimensions and the primary proprioceptive telemetry fields. Optional physical sensor blocks are future extensions, not required for the Duopod milestone.

## Duopod Body Ownership

The Duopod is a low, wide two-servo machine that reuses the proven worm side-drive mechanism on both sides:

- one central/base Sable sublevel;
- one left swivel-bearing child sublevel;
- one right swivel-bearing child sublevel;
- one left Robotic Servo Joint;
- one right Robotic Servo Joint.

The base body owns the central iron frame, both servos, both drive cogs, and both swivel bearings. Each side servo and bearing face outward from the body, then the bearing assembles a forward-extending paddle/limb child body before the base is assembled. The right servo points right and the left servo mirrors it, so the limbs move in a thrusting plane rather than a clapping plane. `DuopodInstance` records machine ID, batch ID, sublevel IDs, servo positions and instance IDs, spawn pose, and canonical model axes.

Duopod control uses semantic limb-frame actions and telemetry rather than raw actuator-local servo signs. The right servo is mounted facing the opposite direction from the left servo, so `MinecraftMachinesDuopodControl` reflects the right normalized action before writing the raw servo target, then reflects right-side target angle, actual angle, angle error, angular velocity, generated speed, and signed torque telemetry back before observation/reward code reads it. Joint load remains a magnitude and is not sign-reflected.

Robotic Servo Joints and Simulated swivel bearings that are moved into the assembled base sublevel do not advance through ordinary world block-entity ticks. The servo self-powered motor now advances during Sable sublevel physics ticks and through a guarded server-tick fallback, while the NeoForge module advances sublevel swivel-bearing block entities from `MinecraftMachinesSubLevelKineticTicker`. This keeps the existing servo -> cog -> Simulated swivel-bearing mechanism moving after assembly without editing the vendored Simulated source.

Canonical model axes are:

- forward: the spawn/model `Direction` selected for the Duopod;
- right: `forward.getClockWise()`;
- up: Minecraft +Y.

Projected-gravity observations use the full rotated body axes. Horizontal velocity and target math use a normalized orthogonal frame on world X/Z derived from the rotated heading. Pitch and roll therefore cannot shorten a requested horizontal target or skew its bearing; if body-forward is nearly vertical, body-right supplies a stable fallback heading. Walk-forward reward and displacement diagnostics use the fixed spawn heading so turning cannot manufacture forward progress.

## Observation Schema

`minecraft_machines:duopod_locomotion` version 6 is a normalized servo-first vector of length 45. Version 6 invalidates earlier policies because horizontal-frame semantics and servo telemetry normalization changed during the locomotion repair. Version 5 added aggregate center-of-mass height/drift signals, version 4 added center-of-mass support errors, version 3 reflected right-side servo telemetry into the mirrored semantic limb frame, and version 2 added target-relative standing height error for balance recovery.

Base fields:

0. `phase_sin`
1. `phase_cos`
2. `desired_forward_velocity`
3. `desired_lateral_velocity`
4. `desired_yaw_rate`
5. `local_forward_velocity`
6. `local_lateral_velocity`
7. `local_vertical_velocity`
8. `local_roll_rate`
9. `local_pitch_rate`
10. `local_yaw_rate`
11. `projected_gravity_forward`
12. `projected_gravity_right`
13. `standing_height_error`
14. `center_of_mass_height_delta`
15. `center_of_mass_forward_drift`
16. `center_of_mass_lateral_drift`
17. `support_com_forward_error`
18. `support_com_lateral_error`

Per-servo fields are repeated for `left` then `right`:

- `<servo>_target_angle`
- `<servo>_actual_angle`
- `<servo>_angle_error`
- `<servo>_angular_velocity`
- `<servo>_generated_speed`
- `<servo>_estimated_torque`
- `<servo>_joint_load`
- `<servo>_minimum_angle_limit`
- `<servo>_maximum_angle_limit`
- `<servo>_enabled`
- `<servo>_attached`
- `<servo>_valid_constraint`
- `<servo>_previous_action`

All fields are finite and clamped to `[-1, 1]`. Sensor repair is recorded in episode diagnostics when non-finite recoverable values are replaced by zero.

## Standing Balance Update

Previous standing setup, before the 2026-06-25 stabilization change:

- Observations already exposed projected local gravity, local roll/pitch/yaw angular rates, local body linear velocity, and servo telemetry.
- Observations did not expose normalized base height relative to the configured standing target height.
- The balance reward mixed physical posture terms with static pose/contact shaping such as honey-ground contact, servo-down pose, and sprawled/body-clearance terms.
- Balance success was effectively surviving to the episode time limit, while fall detection was morphology-specific and tied to low servo/body contact.
- CEM had antithetic sampling and standard-deviation floors, but no exploration-expansion trigger based on stagnant fitness and collapsed action diversity.
- The PPO bridge consumed the same Java environment, so any Java environment change affects PPO rewards, observations, terminal flags, and diagnostics.

Current standing setup keeps the policy discovery problem open-ended, but uses simple physical objectives. It adds deterministic symmetric disturbances, target-relative height observation, aggregate center-of-mass height/drift/support observations, immediate recovery-progress reward, held success conditions, CEM exploration diagnostics, and adaptive CEM standard-deviation expansion. It does not add terrain generation, scripted recovery, prescribed leg pairings, hardcoded lean-back actions, or task-level fall detection.

## Action Schema

`minecraft_machines:duopod_servo_targets` version 3 is a normalized continuous vector of length 2:

0. `left_target`
1. `right_target`

Each value is clamped to `[-1, 1]` and mapped to the configured -60 to +60 degree range in the Duopod semantic limb frame. Version 3 records this narrower physical action contract. The left raw servo target uses the same sign. The right raw servo target is inverted because the right servo faces the opposite direction, so equal positive `left_target` and `right_target` commands represent the same semantic limb rotation. Create RPM remains an implementation detail inside the servo block entity.

The current live Duopod config limits servo motion to 90 degrees/second, uses stiffness 800 and damping 350, and caps estimated torque at 75,000. The default gait frequency is 0.35 Hz. These values are part of the current physical training envelope; older checkpoints produced under different limits are not evidence for this contract.

## Reward Terms

Command tracking is stationary-baseline-centered. Matching a nonzero command can earn positive tracking reward, while producing zero motion for that command contributes zero tracking reward rather than a positive survival shortcut:

```text
centeredTracking(actual, desired, scale) =
  exp(-square((actual - desired) / scale)) - exp(-square(desired / scale))

forwardTracking = centeredTracking(actualForward, desiredForward, forwardTrackingScale)
lateralTracking = centeredTracking(actualLateral, desiredLateral, lateralTrackingScale)
yawTracking = centeredTracking(actualYaw, desiredYaw, yawTrackingScale)
upright = exp(-uprightScale * (projectedGravityForward^2 + projectedGravityRight^2))
actionRate = mean(square(currentAction - previousAction))
directedForwardProgress =
  2 * dt * clamp(sign(desiredForward) * actualForward, -3, 3)
  * clamp(abs(desiredForward) / 0.50, 0, 1)
  * clamp(bodyUpDotWorldUp, 0, 1)

reward = directedForwardProgress
  + dt * (0.35 * forwardTracking + 0.20 * lateralTracking + 0.30 * yawTracking + 0.10 * (upright - 1) - 0.01)
  - 0.002 * actionRate
  - launchPenalty
  - machineFailurePenaltyWhenFailed
```

For `walk_forward`, `actualForward` is measured in the spawn heading frame. Launch penalty is time-scaled and applies only to upward velocity above 0.75 blocks/second or height above 1.25 blocks from spawn.

Point-goal reward adds distance progress and a success bonus:

```text
progress = previousDistanceToTarget - currentDistanceToTarget
reward = 4.0 * progress
  + dt * (0.25 * forwardTracking + 0.10 * lateralTracking + 0.15 * yawTracking + 0.02 * (upright - 1) - 0.01)
  - 0.002 * actionRate
  - launchPenalty
success bonus = +25.0 on first entry into success radius
machine failure penalty = -15.0
```

No sideways displacement penalty is used in the shared environment because point-goal steering can require lateral drift.

Standing balance reward is a physical-result objective:

```text
orientationError = 0.5 * (1 - clamp(bodyUpDotWorldUp, -1, 1))
angularError = square(localRollRate / 1.0) + square(localPitchRate / 1.0)
supportError = square(centerOfMassSupportDistance / 0.45)
honeyGroundError = 0.5 * (square(leftHoneyGroundClearance / 0.22) + square(rightHoneyGroundClearance / 0.22))
supportReward = 1 / (1 + 4.0 * sqrt(supportError))
honeyContactReward = 1 / (1 + 2.5 * sqrt(honeyGroundError))
centerOfMassHeightScore = clamp(1 + centerOfMassHeightDelta, 0, 1)
balanceError = 2.0 * orientationError + 0.75 * clamp(angularError, 0, 4) + 1.50 * clamp(supportError, 0, 9) + 0.50 * clamp(honeyGroundError, 0, 9)
recoveryProgress = clamp(previousBalanceError - balanceError, -1, 1)

reward =
  dt * (2.50 * uprightReward + 1.75 * supportReward + 0.75 * honeyContactReward + 1.25 * centerOfMassHeightScore + 0.50 * settledReward + 0.02)
  + 2.00 * recoveryProgress
  - 0.001 * meanSquaredActionDelta
  - 0.08 * angularError
  - 0.03 * linearMotionError
  - 0.75 * servoGroundRisk
  - 0.0001 * normalizedServoLoad
  + successBonusWhenHeld
  - machineFailurePenaltyWhenFailed
```

The balance reward does not reward a particular corrective motion, mirrored servo relationship, lean-back command, down-servo posture, or fall/reset shortcut. It rewards keeping the aggregate Sable center of mass over the support segment between the two honey blocks, keeping both honey blocks near the floor, staying upright, keeping aggregate COM from dropping below the calibrated standing reference, and settling after recovery. The COM-height score is capped at the reference height, so it punishes lying low without rewarding jumps above the starting stand. A swivel bearing/servo block nearly touching the floor is treated as continuous risk in reward only.

The alternate `balance_center_of_mass` reward is an A/B test for a more minimal hypothesis:

```text
heightScore = clamp(1 + centerOfMassHeightDelta / 1.0, 0, 2)
driftError = square(centerOfMassForwardDrift / 0.45) + square(centerOfMassLateralDrift / 0.45)
stillnessReward = exp(-driftError - 0.25 * angularMotionError - 0.25 * linearMotionError)
reward =
  dt * (3.0 * heightScore * stillnessReward + 0.02)
  + 2.00 * recoveryProgress
  - 0.001 * meanSquaredActionDelta
  - 0.08 * meanSquaredAction
  - 0.08 * square((leftAction - rightAction) / 2)
  - 0.0001 * normalizedServoLoad
  + successBonusWhenHeld
  - machineFailurePenaltyWhenFailed
```

This stage intentionally does not use support-segment reward, honey-contact reward, upright reward, servo-down pose, foot-count reward, fall detection, or a fall penalty. A fallen pose is punished only through low/drifting COM and body motion over the full episode. Balance CEM generations always include a zero-action baseline candidate, and balance curricula start from a fresh distribution rather than inheriting a previous best. It accepts the aliases `balance_com`, `balance_centerofmass`, `balance_centreofmass`, and `balance_centre_of_mass`.

## Reset Strategy

The environment exposes `SNAPSHOT`, `RESPAWN`, and `AUTO`. Sable exposes body teleport and velocity reset, so snapshot reset is possible, but the initial robust strategy is respawn: destroy the owned Duopod sublevels, clean collision registry entries, and spawn a fresh Duopod in the same slot. `AUTO` uses respawn until snapshot validation is proven reliable with the current swivel constraints.

Each reset clears previous actions, phase, accumulated reward, reward component totals, command/target state, terminal flags, and diagnostic counters.

## Vectorized Environment

The optimizer-independent vectorized API lives in `content/training/environment`.

Key types:

- `TrainableMorphology<M>`: generic morphology contract. It owns spawn, action application, observation, reward, health, reset, target distance, and destroy hooks.
- `LocomotionVectorEnvironment<M>`: owns a fixed slot batch, validates/clamps normalized action arrays, advances episode phase by `controlTicks`, calls the morphology once per step, tracks reward totals, and auto-resets terminal slots through an `EpisodeDefinitionProvider`.
- `EpisodeDefinition`: task mode, curriculum stage, command, max control steps, initial target distance, gait frequency, terrain profile, optional local point target, and optional standing disturbance for one episode.
- `EnvironmentBatchReset` / `EnvironmentBatchStep`: vectorized reset and step outputs shared by Java CEM and the current PPO bridge.

Current pure semantics:

1. `resetAll` or `resetMask` initializes slots and clears previous action, phase, reward totals, and terminal flags. If the returned reset observation itself needs repair, the new episode starts with `repaired_observation_count=1`; otherwise the repair counter is zero.
2. `beginStep` validates `[slot][action]` shape, rejects non-finite actions, clamps each action to `[-1, 1]`, and applies it to the morphology.
3. `finishStep` calculates reward from the same morphology/runtime state, observes the next state, inspects health, reports per-step `reward_components` and accumulated `reward_component_totals`, marks physical failures, point-goal success, and held balance success as `terminated`, and marks time limits as `truncated`. The first terminal state and episode identity are frozen before any automatic reset.
4. Finished slots are immediately reset with the episode provider. Their reset observations are returned separately so SB3-style vector environments can return reset observations while preserving `terminal_observation`; Python also exposes time limits through `TimeLimit.truncated`.
5. Non-finite or out-of-range observation values are repaired to normalized finite values and counted in `repaired_observation_count`; reset, step, and no-respawn episode-update infos all expose `observation_repaired`. Three repaired observations in one episode terminate the slot as `MACHINE_FAILURE`.
6. Point-goal distance is reported as `distance_to_target`; successful point-goal episodes terminate with `termination_reason=SUCCESS`.
7. Balance success uses a morphology-owned held condition. The live Duopod requires 20 consecutive successful control steps for balance success. Balance stages do not use a task-level fall detector; low or fallen poses are handled through continuous reward terms and time-limit truncation unless true machine health fails.
8. Morphologies can attach server-authoritative diagnostic fields to reset and step info through `diagnosticInfo`. The live Duopod uses this for pose, requested command, local velocity/yaw, servo load, joint velocity, standing disturbance, balance error, success conditions, and applied action telemetry.

## Terrain And Controlled Experiment Arenas

The broad command, point-goal, balance, replay, and Python-bridge paths use the ordinary Minecraft world at the requested spawn area. They do not generate artificial flat or low-bump terrain.

Key types:

- `TerrainProfile`: pure episode metadata containing a stable profile ID, footprint width/length placeholders, and an enabled flag.

`TerrainProfile.none()` is the only active terrain profile. It reports `minecraft_machines:no_training_terrain` with `enabled=false`, making reset/step info explicit about the absence of generated training terrain.

The live Duopod morphology searches the existing world for a clear support surface near the requested slot origin. It scans a deterministic square ring up to 16 horizontal blocks from the requested slot origin so a console/headless bridge or control sweep can start near a normal Minecraft spawn without requiring an exact clear footprint. If no suitable surface exists, spawn fails instead of modifying the world. Active training uses canonical stage names `minecraft_terrain_commands`, `walk_forward`, `minecraft_terrain_point_goals`, `balance_stand`, and `balance_center_of_mass`. The historical names `flat_commands`, `low_bumps_commands`, and `flat_point_goals` remain accepted only as compatibility aliases and normalize to the canonical stages.

Java `walk_forward` CEM and `benchmark_walk_forward` are a controlled-experiment exception. `MinecraftMachinesDuopodFlatArena` identifies the arena as `minecraft_machines:temporary_flat_duopod_lane_groups_v2` and its topology as `minecraft_machines:isolated_three_lane_groups_v1`. It snapshots a bounded corridor, rejects any target position containing a block entity, and transactionally installs identical smooth-stone lanes with clear headroom. Training groups each candidate's three speed lanes together and inserts one unused lane-width between candidate groups. The arena, chunk lease, and controlled morphology now share the exact right-offset function `(slot + floor(slot / 3)) * spacing`; the controlled spawn search must resolve to `requested.above(3)` and cannot silently move a slot within the ordinary 16-block terrain-search radius. Thus every candidate sees the benchmark's local three-lane topology instead of a different interior/edge position on one nearly continuous floor. This temporary arena removes the previous site-geometry confound; it is not a permanent terrain generator or evidence of uneven-terrain generalization.

Before the first mutation, arena preparation durably writes `minecraft_machines_duopod_flat_arena_recovery_v1` NBT under `<world>/minecraft_machines/duopod_arena_recovery/arena_<uuid>.nbt`. Normal close restores every changed block in reverse order and only then removes the journal. On server startup, outstanding journals are validated and recovered before work proceeds. Malformed data, duplicate positions, an unavailable dimension, an unsafe entry count, or a failed restore leaves the journal in place and fails closed; new training must not run over an unreconciled arena. This is crash/startup recovery, not a complete power-loss transaction: the code does not explicitly force the restored chunks to disk before deleting the forced journal, leaving a narrow P2 window in which abrupt host power loss could lose the in-memory restoration. Publication runs should retain a world backup.

Both the vectorized CEM coordinator and fixed benchmark acquire a task-scoped `MinecraftMachinesTrainingChunkLease` over the full spawn/travel corridor, synchronously resolve its chunks, wait for position-ticking readiness, and release only the runtime tickets they added. The lease never writes `ForcedChunksSavedData`, so it does not create persistent `/forceload` state. Training retains the lease and arena across all candidate batches; the benchmark retains them across all three sequential controller batches. Cleanup order is environment/body destruction, arena restoration, then chunk-ticket release.

## Evaluation Manifest and Contract

Current Java and Python point-goal evaluation uses contract `duopod_planar_evaluation_v1`, version 1. It defines world-horizontal X/Z target geometry, block distance units, and first-terminal-state capture. The held-out manifest is `duopod_held_out_point_goals_v3`, version 3: 21 ordered scenarios at bearings -75, -45, -15, 0, +15, +45, and +75 degrees and distances 6, 10, and 12 blocks. Its compatibility hash is `b3d9ccc73651bfe943aada11d30e9116b80cead6fc3270006c275520f06bd09f`. These bearing/distance pairs differ from the enumerated point-goal training set, which uses bearings -90/-60/-30/0/+30/+60/+90 degrees at distances 4 and 8 blocks.

Python `evaluate.py` uses the current contract and manifest by default, validates model metadata against the live bridge morphology and observation/action schema hashes, drives the bridge through `SET_TARGETS` manual point-goal episodes, records per-scenario return, success, final distance, action variation, distance travelled, path directness, time-to-target, mean/peak servo load, and mean forward/yaw command error, then writes policy provenance and the manifest into JSON/CSV output. Evaluation verifies a measured reset distance, finite non-negative distances and metrics, rates in `[0, 1]`, and target progress no greater than physical path travelled; a complete accepted summary is marked `evaluation_valid=true`.

`compare.py` accepts only the current evaluation contract, exact current manifest and episode order, complete finite coverage, valid aggregate recomputations, and `evaluation_valid=true` before reporting summary and per-scenario deltas. `evaluate.py` can promote an evaluated PPO model post-hoc because the live bridge supports one Duopod session at a time. Promotion compares success rate, then mean return, then final distance. A legacy, malformed, incompatible, incomplete, reordered, or non-finite previous evaluation is not treated as a valid incumbent and cannot block promotion of a complete current evaluation.

Python `validate_target_change.py` runs the PPO-side closed-loop target-change validation against a live bridge. It resets one manual point-goal episode to an ahead-left target, runs the saved SB3 policy deterministically, calls `UPDATE_TARGETS` at the configured control step to switch to ahead-right without respawning, feeds the updated observation into the next policy call, and writes JSON/CSV evidence with old/new target bearings, desired yaw before/after, observed yaw before/after, left/right actions before/after, episode id continuity, machine id continuity, and phase continuity.

## Java CEM Integration

The reusable CEM optimizer operates on arbitrary finite genome vectors. The default policy is linear tanh:

```text
action = tanh(W * observation + bias)
```

For observation dimension `O` and action dimension `A`, genome size is `A * (O + 1)`. Layout is row-major by actuator: all `O` weights for action row 0, then that row's bias, then row 1, and so on.

Duopod observation schema version 6 and action schema version 3 therefore have `O = 45`, `A = 2`, and linear-policy genome size `92`, but generic CEM classes derive this dynamically from the active policy and schemas. The focused `walk_forward` curriculum deliberately uses the smaller `duopod_phase_gait_v2` policy with a 7-number genome: common center, center differential, base amplitude, amplitude differential, right phase offset, common phase offset, and command-speed amplitude gain. V2 converts the normalized desired-forward observation back to blocks/second before dividing by the 1.10-block/second training maximum, then allows a speed scale from 0.40 to 1.60. The loader recognizes v1 as the same seven-value family only for a widened restart; v1 artifacts are not current publication evidence.

The live NeoForge Duopod path now adapts the physical machine to `TrainableMorphology<MinecraftMachinesLiveDuopod>` in `MinecraftMachinesDuopodTrainingMorphology`. It uses the shared Duopod encoder and reward calculator, applies actions through `MinecraftMachinesDuopodControl`, and respawns physical Duopods between episodes because snapshot reset is still unvalidated for the current swivel-bearing constraints.

`MinecraftMachinesDuopodCemTrainer` is the server-tick CEM coordinator. Each generation samples a population of genomes, then evaluates candidates in bounded vector-environment batches. `walk_forward` samples the 7-parameter phase-gait policy; the broader command, point-goal, and balance curricula keep the generic linear tanh policy. The default CEM run uses a 64-candidate population, 8 elites, 50 generations, 5 scenarios per candidate, and at most 32 concurrent slots, which becomes 30 active slots per batch for complete candidate/scenario groups. Population overrides scale elites toward one quarter of the population, capped at eight and with at least two for nontrivial runs. Operators can override the live slot cap with the `max_slots <maxConcurrentSlots>` suffix; values below scenarios-per-candidate normalize to that minimum so one complete candidate can always run. Player-positioned commands use the invoking player's level and facing, while `start_at`, `start_fresh_at`, `evaluate_best_at`, `benchmark_walk_forward_at`, and `validate_target_change_at` are console/headless variants that take an explicit origin and horizontal forward direction. Each batch calls `beginStep` to apply disturbances/actions, waits the configured physics ticks, then calls `finishStep` to collect the same observations/rewards/termination info that the PPO bridge uses.

`walk_forward` is the smallest visible locomotion sanity curriculum. It uses straight-forward commands at 0.50, 0.80, and 1.10 blocks/second, and the config rejects any walk-forward scenario count other than three. Every candidate in a generation receives the same speed-indexed scenario seeds, its own isolated three-lane group, and the same within-group topology, so random/environment effects and lane adjacency do not vary with candidate identity. Fitness contract `minecraft_machines:duopod_cem_fitness_v6` samples body-up, base-height excursion, raw displacement, and arena escape every server physics tick during each held-action interval; control-step endpoints can no longer hide a transient fall or launch, and missing/non-finite body-up data fails closed. Dense command-tracking return is augmented at the terminal snapshot: post-warmup displacement has weight 4, stable positive progress adds 4, instability costs 6 plus 10 times the upright shortfall, vertical excess and arena escape are penalized, and an unstable episode cannot receive positive terminal-forward credit. Training success is exactly `no machine failure && minimum body-up >= 0.60 && peak vertical excursion <= 3.0 && no arena escape && post-warmup forward > 0`; `ScoredGenome` also weights the worst speed by 0.25. Slot logs retain dense return and report selection return, post-warmup and raw spawn-frame displacement, per-tick extrema, arena escape, gate success, and the terminal adjustment.

The phase-gait optimizer begins fresh runs with standard deviation 0.85 and a 0.12 floor. Generation zero keeps the distribution-mean anchor and injects up to eight deterministic conservative gait probes. If action diversity collapses while unresolved, the phase policy can hard-expand standard deviations by 2x subject to its cooldown/cap conditions. An incompatible v1/fitness-protocol restart uses at least 0.60 standard deviation instead of treating an old narrow distribution as current evidence. The phase gait remains intentionally non-reactive; `evaluate_best` and `validate_target_change` are point-target tools and reject it, so use `replay_best` and the fixed benchmark for this curriculum.

`benchmark_walk_forward` is the fixed portfolio-facing comparison for a current walk-forward checkpoint. Before start, publication eligibility requires fitness `minecraft_machines:duopod_cem_fitness_v6`, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, slot layout `minecraft_machines:isolated_three_lane_groups_v1`, spacing 12, four ticks per action, a 20-tick warmup, a 200-control-step horizon, and exactly three scenarios. A requested horizon other than 200 is rejected. Benchmark format `minecraft_machines_duopod_walk_forward_benchmark_v3` remains version 3; the v6 slot-layout and checkpoint-training-arena fields are backward-compatible additive provenance, so consumers must require those fields and fitness v6 rather than equating all v3 artifacts. The benchmark prepares one three-speed lane group, then runs learned, neutral, and scripted alternating-sine controllers as sequential three-slot batches on those same lanes. Same-speed records must match episode id, seed, requested origin, actual spawn origin, and post-warmup baselines within `1e-6` before output is written. Pose extrema and escape are sampled every physics tick, and each episode must contain `control_steps * control_ticks` held-action pose samples. It records world seed/dimension, lane/arena/slot-layout geometry, pairing semantics, UTC generation time, explicit revision availability, checkpoint/genome provenance, post-warmup metrics, raw spawn-relative metrics, first terminal snapshot, and pose-sample count.

Every completed benchmark receives a UUID and publishes immutable `duopod_cem_walk_forward_benchmark_<benchmark_id>.json` / `.csv` files. Compatibility `latest.json` / `.csv` files are replaced before `duopod_cem_walk_forward_benchmark_latest_manifest.json`; the manifest is the atomic commit marker written last. It contains `commit_complete=true`, the common UUID, contract/provenance, byte counts, and SHA-256 for both immutable and compatibility pairs. JSON and manifest use canonical UTF-8 with a trailing LF included in those hashes. Readers must verify both hashes and a matching UUID in the JSON, every CSV row, and the manifest.

Acceptance rule `post_warmup_stability_adjusted_anti_ballistic_v2` requires exact finite coverage, positive learned forward displacement at every speed, no learned machine failures or arena escapes, learned minimum body-up of at least 0.60, learned post-warmup peak vertical excursion no greater than 3.0 blocks, and learned mean forward displacement at least 0.50 blocks beyond the stronger adjusted comparison baseline. A baseline that fails any stability gate retains raw displacement but has positive comparator credit capped at zero.

The final v6 checkpoint comes from fresh seeded CEM run `7bf2b216-3414-4030-88a8-3e6316823358` (seed `2026080301`), which evaluated population 32 with 8 elites for 12 generations. Selected generation 5 has aggregate `30.296754`, mean `16.582846`, worst score `14.855634`, success rate `1.0`, failure rate `0.0`, and genome SHA-256 `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`. Two fixed-world repeats passed all eight criteria: [run 01](../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.json), UUID `c647d862-f7e4-4d34-8b69-2fcd0591edaf`, and [run 02](../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.json), UUID `6b5506a5-e24b-4df2-b972-49cbe6ed1a51`. Both measured mean/margin `+3.0775210`, per-speed forward `3.659454 / 2.941055 / 2.632053`, minimum body-up `0.6082670`, peak vertical excursion `2.1274109`, and zero failures/escapes. Generation 8 had the highest training aggregate (`36.5139`) but was correctly rejected when held-out minimum body-up reached `0.59605 < 0.60`. These runs establish operational repeatability for this controller and fixed protocol, not multi-world, independent-seed, or statistical robustness.

For `balance_stand`, every candidate in a generation is evaluated against the same seeded disturbance set. The standing curriculum has eight deterministic scenario families: near-neutral, forward pitch, backward pitch, left roll, right roll, positive pitch velocity, negative pitch velocity, and delayed random horizontal impulse. Four disturbance stages scale tilt from 3 to 25 degrees, pitch/roll angular velocity from 0.10 to 1.00 rad/s, linear impulse from 0.00 to 0.80, angular impulse from 0.02 to 0.25, and joint action offsets from 0.02 to 0.12. Some stages include a delayed impulse at control step 4 or 5.

Balance CEM uses wider initial exploration for biases, explicit maximum standard-deviation caps, generation-level action/reward/tilt/recovery diagnostics, and adaptive exploration expansion. If recent best fitness stagnates, action diversity is too low, and the population is not simply saturated at action bounds, the trainer expands standard deviations up to their configured caps. The status payload and logs include compact exploration summaries.

The point-goal training curriculum covers bearings -90, -60, -30, 0, +30, +60, and +90 degrees at 4 and 8 blocks. When a CEM point-goal run uses fewer than all 14 scenarios per candidate, the trainer rolls the scenario window by generation, so low-concurrency runs still train across the broader bearing set instead of repeating the same local targets every generation. The v3 held-out manifest uses bearings -75/-45/-15/0/+15/+45/+75 degrees at 6/10/12 blocks, so every held-out pair contains a bearing, distance, or both that the enumerated training curriculum does not use.

Duopod CEM reward visualization is opt-in. `/mm train reward_viz on` follows one representative active slot and renders a simple overlay for active CEM training and `replay_best`: cyan arrow = goal command or target, yellow pole = point target, green/red arrow = actual local motion, green/red vertical bar = total reward sign, and red side bar = total negative reward. The actionbar reports `Goal F/L/Y`, `Actual F/L/Y`, net `Reward`, summed `Good`, summed `Bad`, and absolute `Err F/L/Y`. Held-out evaluation and target-change validation remain report/telemetry driven; inspect logs and JSON for target position, success radius, balance thresholds, and failure state.

Servo-control confidence and optimizer confidence are verified separately. `duopodRightServoReflectsOppositeFacingMount` proves a semantic same-direction left/right command becomes opposite raw servo targets on the two opposite-facing mounts and reflects back to matching semantic telemetry. `cemMeanDirectionFollowsEpisodeScores` proves the next CEM distribution mean moves toward whichever candidate receives the better episode score, and `cemDistributionMovesTowardElitesAndRespectsStdFloors` covers the smoothed multi-elite update. `trainRewardVisualizationRootCommandToggles` proves the root, generic CEM, and explicit Duopod reward-visualization commands all toggle the same inspection path.

`evaluate_best` runs a compatible current broad-policy CEM genome through the v3 held-out point-goal manifest using the same vector environment and writes `minecraft_machines/duopod_cem_evaluation_latest.json` in the active world directory. The JSON embeds the v1 evaluation contract and uses the same `scenario_manifest`, `summary`, and `episodes` structure and metric names as Python PPO evaluation output. It freezes first-terminal episode state and writes only after the evaluation sanity checks pass; `compare.py` independently revalidates the contract, manifest, coverage, episode geometry, and aggregates.

`validate_target_change` runs an automated closed-loop target-change validation for the current best CEM genome. It spawns one Duopod, starts with an ahead-left target, switches to ahead-right at a configured control step, keeps the same machine id and phase clock, then writes `minecraft_machines/duopod_cem_target_change_latest.json`. The report records old/new target bearings, desired yaw before/after the switch, observed yaw response, and left/right actions before/after. The Python `validate_target_change.py` command provides the equivalent PPO-side validation through the live bridge.

`save` (and the legacy `save_checkpoint` alias) writes `minecraft_machines/duopod_cem_best.json` under the active world directory using checkpoint format `minecraft_machines_duopod_cem_checkpoint_v4`; named saves use `duopod_cem_<name>.json`. A checkpoint saved by a dedicated/server run is not automatically visible to a different client save. The artifact includes policy type, morphology/schema identity, normalized curriculum, best genome and scores, failure rate, an immutable deep-copied training-config snapshot, CEM settings, distribution mean/std/bounds/minimum/maximum snapshot, evaluation metadata, and `fitnessContract=minecraft_machines:duopod_cem_fitness_v6` on current saves. `runSeed` is retained as the immutable champion-discovery seed for backward compatibility, `discoveryRunSeed` exposes that meaning explicitly, and `optimizerSeed` identifies the saved optimizer stream. A compatible mid-run resume restores the distribution but not the Java RNG's complete internal state, so it is not a bit-exact continuation of the future sample trajectory. The snapshot prevents a later save from dynamically relabelling an old candidate with the current arena or slot layout. Compatible v3/v2/v1 files remain readable for recovery/restart purposes, but their historical scores and provenance do not become v6 evidence.

Checkpoint publication is atomic and recoverable. The writer forces and parses a temporary file, preserves the previous valid primary as `<filename>.bak`, atomically replaces the primary, then validates the promoted file. Loading tries the primary and automatically falls back to a valid backup when the primary is absent or corrupt. A corrupt primary never replaces an older valid backup.

Exact optimizer resume requires a compatible policy family plus matching curriculum, fitness contract, episode ticks, control ticks, scenarios per candidate, spacing, warmup, and point-goal distance protocol. Publication eligibility separately validates the frozen current arena and slot-layout identity. An old or mismatched contract may provide only a compatible genome anchor: generation and best-fitness scale reset, and exploration widens. `start_fresh_at` bypasses the in-memory checkpoint entirely but deliberately leaves the checkpoint file unchanged. Best-checkpoint replacement is curriculum- and protocol-scoped because raw rewards and historical fitness contracts are not comparable.

Current in-game command root:

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
/mm train cem look_target [on|off]
/mm train cem reward_viz [on|off]
/mm train cem save [name]
/mm train cem load [name]
/mm train duopod cem start [population] [generations] [episodeTicks] [controlTicks] [scenarios] [spacing]
/mm train duopod cem start_at <x> <y> <z> <north|south|east|west> [population] [generations] [episodeTicks] [controlTicks] [scenarios] [spacing]
/mm train duopod cem start_at <x> <y> <z> <north|south|east|west> <population> <generations> <episodeTicks> <controlTicks> <scenarios> <spacing> max_slots <maxConcurrentSlots> [curriculum]
/mm train duopod cem start_fresh
/mm train duopod cem start_fresh_at <x> <y> <z> <north|south|east|west> <population> <generations> <episodeTicks> <controlTicks> <scenarios> <spacing> max_slots <maxConcurrentSlots> <curriculum>
/mm train duopod cem stop
/mm train duopod cem status
/mm train duopod cem replay_best
/mm train duopod cem evaluate_best [maxControlSteps]
/mm train duopod cem evaluate_best_at <x> <y> <z> <north|south|east|west> [maxControlSteps]
/mm train duopod cem benchmark_walk_forward [maxControlSteps]
/mm train duopod cem benchmark_walk_forward_at <x> <y> <z> <north|south|east|west> [maxControlSteps]
/mm train duopod cem validate_target_change [switchControlStep] [maxControlSteps]
/mm train duopod cem validate_target_change_at <x> <y> <z> <north|south|east|west> [switchControlStep] [maxControlSteps]
/mm train duopod cem replay_target <forwardBlocks> <rightBlocks>
/mm train duopod cem look_target [on|off]
/mm train duopod cem reward_viz [on|off]
/mm train duopod cem save_checkpoint
/mm train duopod cem load_checkpoint
/mm train duopod cem clear
```

The root training commands are generic aliases. `status` reports both the bridge and CEM trainer, `stop` stops active bridge/training/evaluation/target-change work, `clear` also clears CEM replay bodies, `target <x> <y> <z>` retargets the active CEM replay to an absolute world point without respawning it, `look_target [on|off]` retargets the active CEM replay to the block under the invoking player's crosshair, and `reward_viz [on|off]` toggles the same CEM reward overlay as the explicit Duopod command. The `train cem ...` subtree aliases the Duopod CEM trainer from the more explicit `train duopod cem ...` path. `start_fresh` uses the complete default config; the intentionally verbose `start_fresh_at` requires a full configuration and is the unambiguous long-run publication entry point. The replay policy still receives only local locomotion commands derived from that target.

Current controlled publication protocol:

```mcfunction
/mm train duopod cem start_fresh_at <x> <y> <z> <north|south|east|west> 32 12 800 4 3 12 max_slots 24 walk_forward seed <seed>
```

At 800 episode ticks and a four-tick control interval, training and the default benchmark both use 200 control steps. Population 32 uses eight elites; three scenarios cover all commanded speeds; 24 active slots evaluate eight candidates per batch. To resume an exactly compatible saved distribution, use the identical command with `start_at` instead of `start_fresh_at`.

The default live config is population 64, elite 8, 50 generations, 160 episode ticks, 4 control ticks, 5 deterministic scenarios per candidate, spacing 12 (the CLI value 0 is only a compatibility sentinel for 12), a 32-slot concurrency cap, and a 20-tick spawn warmup per CEM batch before policy scoring starts. The first 5 policy control steps are ramped from 20% to 100% servo action strength in CEM training, held-out evaluation, target-change validation, replay, and the learned benchmark controller. Replay first commands neutral for the checkpoint's recorded spawn-warmup ticks, captures a fresh handoff observation, and only then starts that action ramp on the checkpoint's control cadence and gait frequency. Scenarios cycle across their complete curriculum-specific sets instead of pinning a short run to the same initial subset. A lower `max_slots` cap is recommended on local development machines when server tick timing matters more than throughput.

Historical June Minecraft run notes are not acceptance evidence. The old default checkpoint has observation/action schemas v1/v1 and an 80-number balance genome, so it is incompatible with the current v6/v3 environment. The old point-goal artifact contains impossible geometry in which claimed target progress exceeds measured path length; its reported 4/21 result is invalid.

The walk-forward evidence lineage is historical unless explicitly identified as final v6:

- [Attempt 01](../portfolio/data/walk_forward_benchmark_attempt_01_failed.json) and [Attempt 02](../portfolio/data/walk_forward_benchmark_attempt_02_failed.json) are benchmark/policy-v1 failures from before physical pairing and the adjusted comparator.
- [V2 stage-1 run 01](../portfolio/data/walk_forward_benchmark_stage1_run_01_failed.json) and its exact repeat added controlled lanes and fitness v2, but failed comparator, failure, and posture criteria.
- [V3 stage-1 run 01](../portfolio/data/walk_forward_benchmark_stage1_v3_run_01_failed.json) and its exact repeat averaged `+6.2062302` learned blocks yet failed minimum body-up at `0.3424959`.
- The later [per-tick candidate-01 JSON](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.json), [CSV](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.csv), and [commit manifest](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json) preserve UUID `eb0b3949-159a-4ad3-a7ae-92f991977b07`. Per-tick sampling exposed one learned failure, minimum body-up `-0.0047745`, peak vertical excursion `5.4926834`, and one arena escape despite `+4.5291456` mean forward blocks. It used fitness v3 and arena v1, so it motivated rather than satisfies the v6 contract.
- The accepted pre-fix v5 benchmarks, UUIDs `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc`, are real benchmark observations but non-promotable. The arena used grouped offsets while the training morphology still used contiguous `slot * spacing`; the selected generation-2 candidate occupied local slots 3–5, the first mismatched triplet. V6 therefore treated the genome as an unranked candidate, corrected the topology, and re-certified it before publication.

Only the paired final-v6 artifacts above carry the current publication claim.

Replay converts a mutable point target into local desired locomotion commands every control step. The policy still receives only the command vector and proprioceptive state, not absolute world coordinates. `/mm train duopod cem replay_target ...` changes the replay target relative to the current body, `/mm train target ...` changes it by absolute world coordinates, and `/mm train duopod cem look_target [on|off]` makes the target follow the block under the player's crosshair; none of these commands respawn the Duopod. Replay is diagnostic and never promotes a checkpoint or establishes acceptance.

## Python PPO Bridge

The Python PPO bridge is a loopback-only, disabled-by-default server started by an operator command. Socket threads speak length-prefixed UTF-8 JSON and never touch Minecraft/Sable state directly; all world work is queued to the server tick coordinator. The protocol exposes specs, resets, steps, curriculum/target updates, metrics, ping, and session close. Message types, request IDs, object payloads, message-size limits, schema hashes, finite action values, action dimensions, and step-response shapes/field bounds are validated before data crosses the bridge boundary.

Python uses the Java environment as authoritative for observations, rewards, terminations, resets, curricula, and terrain. Python constructs Gymnasium/SB3 spaces dynamically from advertised specs only after validating protocol version, morphology, slot/control counts, schema hashes, schema ids, schema versions, unique field names, and finite ordered field bounds. It locally rejects malformed action vectors/batches that are the wrong shape, non-finite, or outside the advertised action bounds, then sends normalized actions to the bridge. The Python bridge client also validates outgoing reset masks, manual target matrices, and manual command matrices, plus reset/update/step response slot counts, observation dimensions, observation bounds, finite rewards, boolean terminal flags, and info object shapes before wrappers convert data to NumPy arrays. It wraps socket timeouts, disconnects, malformed protocol frames, request-id mismatches, and other transport/protocol failures in `BridgeError` so callers get one clear failure type while stale socket/session state is cleared.

Current bridge command root:

```mcfunction
/mm train bridge start duopod <slotCount> [port]
/mm train bridge start duopod_at <x> <y> <z> <north|south|east|west> <slotCount> [port]
/mm train bridge status
/mm train bridge stop
```

Bridge start binds explicitly to `127.0.0.1`, generates a one-time token, prints the exact Python `train_ppo` command, and does not log the token. `start duopod` is player-positioned; `start duopod_at` is the console/headless variant that uses the command source's level plus an explicit origin and horizontal forward direction. The active server supports one Duopod session at a time. During a session, the bridge pauses Sable physics in the training dimension while Python computes, then each `STEP` applies actions and advances exactly the advertised `controlTicks`; step info and bridge metrics expose `actual_action_ticks` for verification. The dimension is therefore exclusive to that training session. Disconnect, timeout, or stop resumes physics and destroys owned bodies. A NeoForge GameTest starts the real loopback bridge in its own batch, drives `HELLO`, `CREATE_SESSION`, a neutral `STEP`, pending-step `UPDATE_TARGETS` rejection, and `CLOSE_SESSION` over length-prefixed JSON sockets, validates returned dimensions, and asserts exact action-tick accounting.

`SET_CURRICULUM` currently supports `minecraft_terrain_commands`, `walk_forward`, `minecraft_terrain_point_goals`, `balance_stand`, `balance_center_of_mass`, and `manual_commands`; all named training curricula use existing Minecraft terrain. Legacy aliases `flat_commands`, `low_bumps_commands`, and `flat_point_goals` are accepted but normalize before specs, reset/step info, checkpoints, or metadata are written. `SET_TARGETS` accepts either fixed command vectors or local target offsets. Fixed commands reset slots in `manual_commands`; target offsets reset slots in `manual_point_goals`, where each episode owns a local point target and the live Java morphology recomputes the command from current body pose each step.

`UPDATE_TARGETS` accepts the same fixed-command or local-target payloads but does not respawn or reset the active slots. The shared vector environment swaps each slot's current `EpisodeDefinition`, preserves machine identity, oscillator phase, previous action, control-step counters, and reward totals, then re-baselines `previousDistanceToTarget` against the new target before the next reward calculation. This gives the PPO bridge the same closed-loop retargeting primitive used by Java CEM replay/target-change validation while keeping `SET_TARGETS` available for reset-based evaluation episodes.

Python tooling lives in `training/python` as the `minecraft_machines_training` package. It includes:

- length-prefixed JSON protocol client;
- single-slot Gymnasium debug environment;
- Stable-Baselines3 `VecEnv`;
- PPO train/evaluate/target-change validation CLIs;
- PPO metadata/checkpoint helpers;
- PPO best-evaluation checkpoint promotion helpers;
- comparison and metadata export helpers;
- mock-protocol unit tests that do not require Minecraft.

`train_ppo.py` supports fresh SB3 PPO training and resume from `model.zip` or periodic checkpoint `.zip` files. Training artifacts include `metadata.json`, `metadata.training.json`, `metadata.final.json`, a `model.metadata.json` sidecar, TensorBoard logs, and optional checkpoint model/metadata sidecars under `runs/<run-name>/checkpoints`. The metadata records morphology id, curriculum stage, random seed, PPO config, bridge dimensions/control ticks, observation/action specs and hashes, repository commit hash when available, total/trained timesteps, normalization statistics when present, and evaluation metrics when attached. Resume rejects morphology or schema-hash mismatches when previous metadata can be found beside the model or in its run directory. Evaluation requires compatible metadata by default, has an explicit `--allow-missing-metadata` flag only for legacy model artifacts, and can write post-hoc best-evaluation model/checkpoint artifacts only from a complete current-contract held-out run.

The normal system Python currently lacks Gymnasium and Stable-Baselines3, so the stdlib tests use mock bridge servers, fake optional modules, and fake SB3/Torch modules to verify protocol, timeout/disconnect error handling, Gymnasium wrapper behavior, SB3 VecEnv behavior, local action validation, VecEnv `seed`/`set_options` storage-and-clear behavior, `train_ppo` fresh/resume CLI behavior, metadata/checkpoint sidecar logic, and evaluation metadata compatibility checks. The bridge reset protocol does not currently consume Python-side seeds/options, so the VecEnv wrapper stores them for API compatibility and clears them on reset. The test suite also includes optional real-dependency checks that run only when Gymnasium/SB3 are installed: Gymnasium's environment checker against `MinecraftMachinesEnv` with a mock bridge, and a real SB3 smoke test that trains a tiny PPO model against a deterministic continuous Gymnasium environment, saves it, reloads it, and evaluates it deterministically. Those optional tests passed in a temporary virtual environment with Gymnasium 1.0.0 and Stable-Baselines3 2.7.1. Any pre-inspection live bridge notes are debugging context only and are not acceptance evidence; fresh user-authorized live validation is required after the physical Duopod build is accepted.

Latest verification for the v6 implementation is 93 passing common Java tests, 75 passing Python tests with 7 optional skips, and 29 passing NeoForge GameTests. This verifies scheduling, lane grouping, recovery, serialization, pairing, and invariants; the two final-v6 live benchmark-v3 artifacts with `acceptance.accepted=true` provide the locomotion acceptance evidence.

## Future Morphology Extension

A future morphology provides a new `TrainableMorphology` adapter with its own spawn implementation, observation/action specs, action decoder, observation encoder, reward terms, health checks, and terrain requirements. The generic environment, bridge, CEM, protocol, and Python client use the advertised vector dimensions and schema hashes, so they do not need Duopod-specific assumptions.
