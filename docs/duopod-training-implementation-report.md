# Duopod Training Implementation Report

> [!WARNING]
> **Superseded historical record — do not use anything below this banner as current acceptance evidence.** This report describes the June 2026 implementation era and is intentionally preserved unchanged for debugging history. Its schemas, reward semantics, trainer defaults, commands, validation counts, and saved artifacts predate the controlled walk-forward experiment. The cited 4/21 point-goal result is invalid, later v1/v2 attempts failed, and the fitness-v3 generation-6 checkpoint was rejected by per-tick benchmark `eb0b3949-159a-4ad3-a7ae-92f991977b07`. Fitness v5 then produced exact genome `be80280e365ebb756e2f9041655f11377e94e68674c68ab2db6b701c9abf2d3e`, which genuinely passed benchmarks `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc`. Those passes remain non-promotable: independent audit proved selected generation-2 candidate 25 trained in local slots 3–5 while morphology placement requested contiguous offsets and arena v2 inserted group gaps, invalidating the claimed training provenance.
>
> The current protocol is fitness `minecraft_machines:duopod_cem_fitness_v6`, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, slot layout `minecraft_machines:isolated_three_lane_groups_v1`, checkpoint format `minecraft_machines_duopod_cem_checkpoint_v4`, benchmark format v3, and acceptance rule v2. Fresh seeded run `7bf2b216-3414-4030-88a8-3e6316823358` (seed `2026080301`) evaluated population 32 with 8 elites for 12 generations and promoted generation 5, genome `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`. Final benchmarks `c647d862-f7e4-4d34-8b69-2fcd0591edaf` and `6b5506a5-e24b-4df2-b972-49cbe6ed1a51` each passed 8/8 at learned mean/margin `3.077521`, zero failures, and zero escapes. Generation 8's higher training aggregate was rejected because held-out minimum body-up `0.59605` missed the unchanged `0.60` gate.
>
> Current publication state is **accepted for level `world`, seed `-3368720904110701394`, dimension `minecraft:overworld`, and this fixed protocol**. It is not multi-seed or arbitrary-terrain evidence. Both runtime artifacts record `code_revision: null` because a source revision was unavailable to the game runtime. Arena recovery also has one P2 power-loss limitation: after an in-memory restore succeeds, the journal can be removed before affected chunks are explicitly forced to disk. Final verification passed with 93 common Java tests, 75 Python tests plus 7 environment-dependent skips, and 29/29 NeoForge GameTests. See the [current status report](duopod-training-status-report.md), [acceptance audit](duopod-training-acceptance-audit.md), and [engineering case study](engineering-case-study.md).

Current supersession banner updated: 2026-08-03. The historical body below retains its original 2026-06-25 date.

Last updated: 2026-06-25

Status: implementation and tooling are substantially in place, and the current Duopod handoff checkpoint is saved. The user inspected and accepted the physical build on 2026-06-23. Fresh authorized live validation proved control authority, produced nonzero held-out point-goal success, showed a modest no-respawn target-change response, and cleaned up live state.

This report consolidates the current Duopod/shared-training work so progress can be paused or resumed without relying on conversation history. It follows the original final-report checklist, adjusted for the later servo-first addendum and the user correction that training should use normal Minecraft-generated terrain, not generated training terrain.

## Scope Corrections

- The original plan asked for a fixed 19-field Duopod observation schema. The later addendum superseded that. Current Duopod V1 uses a stable servo-first schema derived from the ordered `left`, `right` Robotic Servo Joints. Observation schema version 5 has 45 fields and intentionally invalidates version-4 policies by adding aggregate center-of-mass height/drift signals for balance testing. Action schema version 2 treats right-side actions as semantic limb-frame targets instead of raw actuator-local signs.
- Historical curriculum names such as `flat_commands`, `low_bumps_commands`, and `flat_point_goals` remain compatibility labels only. They do not generate or flatten terrain.
- No quadruped implementation was added.
- This report claims only a decent learned CEM controller suitable for operator inspection, not a robust solved locomotion policy. Current post-inspection held-out point-goal success is `0.1905` (4/21).

## Architecture Summary

The implementation follows this runtime path:

```text
sampled command or local point target
-> deterministic target-to-command adapter
-> LocomotionCommand
-> shared LocomotionVectorEnvironment
-> linear CEM policy or PPO policy through bridge
-> normalized servo target vector
-> Duopod action decoder
-> left/right Robotic Servo Joint target angles
-> Sable/Create Simulated physics
-> servo-first observation, reward, termination, reset
```

Java CEM and Python PPO share the same authoritative Java observation encoding, action decoding, target-to-command conversion, reward calculation, termination logic, reset behavior, curriculum sampling, and no-generation terrain profile. Python treats Minecraft bridge rewards and terminal flags as authoritative.

## Standing Stabilization Update

Before the 2026-06-25 change, `balance_stand` already exposed local projected gravity, local roll/pitch/yaw rates, local body velocity, servo telemetry, and previous actions. It did not expose target-relative standing height error. Its reward favored a static planted/down-arm posture using honey-contact and servo-pose shaping, and balance success was surviving until the time limit. CEM had antithetic sampling and standard-deviation floors, but no action-diversity/stagnation diagnostics or adaptive expansion.

The current standing task is still not self-righting from the ground. It is standing stabilization from approximately upright states. Episodes now carry deterministic seeded standing disturbances, the observation includes `standing_height_error`, reward credits immediate reduction in balance error, success requires a held stable condition, failure requires a held fall condition after grace, and CEM logs/expands exploration when populations collapse without improving. No hardcoded lean-back, scripted recovery, leg pairing, control sweep, terrain generation, or manually authored controller was added.

## Current File Inventory

The repository currently appears untracked from Git's point of view, so Git cannot reliably distinguish files created before this task from files created during it. The current implementation surface is:

- Documentation:
  - `AI_PROJECT_GUIDE.md`
  - `docs/locomotion-training-design.md`
  - `docs/adding-trainable-morphology.md`
  - `docs/duopod-training-status-report.md`
  - `docs/duopod-training-acceptance-audit.md`
  - `docs/duopod-training-implementation-report.md`
- Generic Java training core:
  - `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines/content/training/api`
  - `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines/content/training/environment`
  - `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines/content/training/cem`
  - `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines/content/training/bridge`
  - `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines/content/training/terrain`
- Duopod Java common logic:
  - `minecraft_machines/common/src/main/java/dev/ahmedhamedi/minecraft_machines/content/training/morphology/duopod`
- NeoForge live integration:
  - `MinecraftMachinesCommands.java`
  - `MinecraftMachinesNeoForge.java`
  - `MinecraftMachinesSubLevelKineticTicker.java`
  - `MinecraftMachinesDuopodSpawner.java`
  - `MinecraftMachinesDuopodControl.java`
  - `MinecraftMachinesDuopodManager.java`
  - `MinecraftMachinesDuopodTrainingMorphology.java`
  - `MinecraftMachinesDuopodCemTrainer.java`
  - `MinecraftMachinesTrainingBridge.java`
  - `MinecraftMachinesDuopodGameTests.java`
- Python PPO tooling:
  - `training/python/pyproject.toml`
  - `training/python/README.md`
  - `training/python/src/minecraft_machines_training`
  - `training/python/tests`
- Java tests:
  - `TrainingCoreTest.java`
  - `LocomotionVectorEnvironmentTest.java`
  - existing servo and worm tests remain present.

## Generalized Code

- `TrainableMorphology<M>` defines morphology-owned spawn, action, observation, reward, health, reset, and destroy responsibilities without hardcoding Duopod dimensions.
- `ObservationSpec`, `ActionSpec`, and `VectorFieldSpec` carry schema metadata and compatibility hashes.
- `ServoTelemetrySchemaBuilder` builds action and observation specs from ordered discovered servo names.
- `LocomotionVectorEnvironment<M>` owns independent vectorized slots and is used by both Java CEM and the Python bridge.
- `LinearTanhPolicy` computes genome size from `actionSize * (observationSize + 1)`.
- `ContinuousCemDistribution` samples arbitrary finite genome vectors, supports antithetic sampling, smoothing, bounds, and standard-deviation floors.
- `TrainingMachineCollisionRegistry` scopes collision suppression by physics sublevel, machine id, batch id, and morphology type.

## Duopod Physical Layout

The Duopod is a low, wide two-servo morphology:

```text
central/base body
  left servo -> cogwheel -> Simulated swivel bearing -> left child limb
  right servo -> cogwheel -> Simulated swivel bearing -> right child limb
```

Physical ownership:

- one base Sable sublevel;
- one left child sublevel;
- one right child sublevel;
- one left Robotic Servo Joint;
- one right Robotic Servo Joint.

Canonical local axes:

- Forward: the selected horizontal spawn/model direction.
- Right: `forward.getClockWise()`.
- Up: Minecraft +Y.

Local velocities, target offsets, yaw calculations, projected gravity, replay targets, and point-goal commands use these axes.

## Terrain Behavior

Duopod training uses normal Minecraft world terrain. The active terrain profile is `TerrainProfile.none()` with generator id `minecraft_machines:no_training_terrain`.

The live Duopod morphology searches existing terrain near the requested origin for a clear supported spawn position. If none is found, spawning fails instead of placing, flattening, or clearing terrain.

## Observation Schema

Schema id: `minecraft_machines:duopod_locomotion`

Version: `2`

Length: `40`

Base fields:

```text
phase_sin
phase_cos
desired_forward_velocity
desired_lateral_velocity
desired_yaw_rate
local_forward_velocity
local_lateral_velocity
local_vertical_velocity
local_roll_rate
local_pitch_rate
local_yaw_rate
projected_gravity_forward
projected_gravity_right
standing_height_error
```

Per-servo fields, repeated for `left` then `right`:

```text
<servo>_target_angle
<servo>_actual_angle
<servo>_angle_error
<servo>_angular_velocity
<servo>_generated_speed
<servo>_estimated_torque
<servo>_joint_load
<servo>_minimum_angle_limit
<servo>_maximum_angle_limit
<servo>_enabled
<servo>_attached
<servo>_valid_constraint
<servo>_previous_action
```

All returned values are finite and clamped to `[-1, 1]`. Recoverable non-finite telemetry is replaced with zero and marks the observation as repaired.

## Action Schema

Schema id: `minecraft_machines:duopod_servo_targets`

Version: `1`

Length: `2`

Fields:

```text
left_target
right_target
```

Each normalized action is clamped to `[-1, 1]` and mapped to the corresponding servo's configured safe angle range. The environment action is target-angle based, not raw RPM.

## Reward Formulas

Command tracking:

```text
forwardTracking = exp(-square((actualForward - desiredForward) / forwardTrackingScale))
yawTracking = exp(-square((actualYaw - desiredYaw) / yawTrackingScale))
upright = exp(-uprightScale * (projectedGravityForward^2 + projectedGravityRight^2))
actionRate = sum(square(currentAction[i] - previousAction[i]))

reward =
  deltaSeconds * (1.00 * forwardTracking + 0.75 * yawTracking + 0.05 * upright - commandAlivePenalty)
  - commandActionRatePenalty * actionRate
  - machineFailurePenaltyWhenFailed
```

Point goal:

```text
progress = previousDistanceToTarget - currentDistanceToTarget

reward =
  4.0 * progress
  + deltaSeconds * (0.25 * forwardTracking + 0.15 * yawTracking + 0.02 * upright - pointAlivePenalty)
  - pointActionRatePenalty * actionRate
  + successBonusWhenReached
  - machineFailurePenaltyWhenFailed
```

Standing balance:

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
  deltaSeconds * (2.50 * uprightReward + 1.75 * supportReward + 0.75 * honeyContactReward + 1.25 * centerOfMassHeightScore + 0.50 * settledReward + 0.02)
  + 2.00 * recoveryProgress
  - 0.001 * meanSquaredActionDelta
  - 0.08 * angularError
  - 0.03 * linearMotionError
  - 0.75 * servoGroundRisk
  - 0.0001 * normalizedServoLoad
  + successBonusWhenHeld
  - machineFailurePenaltyWhenFailed
```

Standing support and honey-contact terms use dense inverse-distance scores rather than steep exponentials, so bad poses still remain rankable by CEM. `balance_stand` reward components now include `balance_error`, `recovery_progress`, `center_of_mass_height_reward`, `upright_reward`, `support_reward`, `honey_contact_reward`, `settled_reward`, `action_rate`, explicit angular/linear motion penalties, `servo_ground_risk`, `load_penalty`, and `success_bonus`. The previous balance-only pose shaping components such as `servo_down_pose`, uncapped height reward, foot-count reward, and fall penalty are no longer part of the standing objective. The alternate `balance_center_of_mass` stage intentionally uses high stationary aggregate COM reward plus conservative action-magnitude, left/right action-disagreement, action-rate, and load penalties, with no support, honey-contact, upright, fall-detection, or fall-penalty components. CEM always evaluates a zero-action baseline for balance curricula, balance curricula start from a fresh distribution instead of seeding from a previous best genome, and balance CEM keeps a higher minimum standard deviation plus unsolved-collapse exploration expansion while success count is zero.

Every environment step info exposes immediate `reward_components` plus accumulated `reward_component_totals`; terminal infos also include terminal observation, episode return, episode length, success, and health message.

## Termination And Reset

The environment distinguishes:

- `terminated`: target success or machine failure.
- `truncated`: episode time limit or external cutoff.

Health checks cover missing bodies/servos, invalid pose or velocity, excessive fall/tilt/radius, repeated invalid observations, and reset failures. Non-finite or out-of-range observations are repaired and reported on reset, step, and no-respawn episode updates; three repaired observations in one episode terminate the slot as `MACHINE_FAILURE`. Balance success now requires 20 consecutive control steps with `body_up_dot_world_up >= 0.95`, height error within 0.20 blocks, roll/pitch rates at or below 0.25 rad/s, and horizontal speed at or below 0.35 blocks/s. Balance failure waits through a 5-control-step grace period and then requires 3 consecutive low-height, severe-tilt, body-contact, or swivel-bearing/servo-floor-contact failure steps. Plain time limits are truncations, not balance successes. Current live reset strategy is respawn, not snapshot teleport, because restoring Sable swivel constraints from snapshots still needs validation.

`UPDATE_TARGETS` and CEM replay target changes do not respawn, reset phase, clear previous actions, or zero reward totals.

## CEM Details

Policy:

```text
action = tanh(W * observation + bias)
```

Genome layout is row-major by action row:

```text
for each action row:
  observationSize weights
  one bias
```

For current Duopod V1:

```text
observationSize = 45
actionSize = 2
genomeSize = 2 * (45 + 1) = 92
```

Default CEM settings:

```text
populationSize = 64
eliteCount = 8
maximumGenerations = 50
episodesPerCandidate = 3
smoothingOld = 0.35
smoothingElite = 0.65
episodeTicks = 160
controlTicks = 4
maxConcurrentSlots = 32
curriculum = flat_commands
```

For `balance_stand`, `withCurriculumStage` raises the default scenario count to 8 and ensures the live slot cap can evaluate at least one full eight-disturbance candidate. The eight deterministic standing scenario families are near-neutral, forward pitch, backward pitch, left roll, right roll, positive pitch velocity, negative pitch velocity, and delayed random horizontal impulse. Disturbance stages scale tilt, angular velocity, external impulses, and joint action offsets while keeping seeds common across candidates inside a generation.

Balance CEM tracks mean/min/max distribution standard deviation, separate weight/bias standard deviations, action mean/std/saturation, near-identical candidate-action fraction, survival-length variance, success/fall/repair counts, tilt extrema, recovery-progress statistics, and reward-component means. When recent best fitness stagnates, action diversity is below threshold, and saturation is not excessive, CEM expands standard deviations up to explicit max-std caps.

Checkpoint v2 stores policy type, morphology id, observation/action specs and hashes, curriculum stage, best genome and scores, training config, CEM settings, distribution snapshot, and evaluation metadata. Distribution snapshots now include maximum standard-deviation caps; loading remains backward-compatible with older snapshots that omitted caps. Best-checkpoint replacement is scoped by curriculum stage so command-tracking, point-goal, and standing scores are not compared across reward scales.

## PPO And Bridge Details

Protocol version: `1`

Max message size: `4 * 1024 * 1024` bytes.

Transport: loopback-only `127.0.0.1`, length-prefixed UTF-8 JSON, one-time token, one active session, one outstanding `STEP` request.

Message types:

```text
HELLO
CREATE_SESSION
GET_SPECS
RESET_ALL
RESET_MASK
STEP
SET_CURRICULUM
SET_TARGETS
UPDATE_TARGETS
GET_METRICS
CLOSE_SESSION
PING
ERROR
```

Default bridge control settings:

```text
controlTicks = 4
maximumControlSteps = 200
```

Default PPO settings:

```text
policy = MlpPolicy
net_arch = [128, 128]
activation = Tanh
total_timesteps = 1000000
n_steps = 256
batch_size = 256
n_epochs = 10
learning_rate = 3e-4
gamma = 0.99
gae_lambda = 0.95
clip_range = 0.2
ent_coef = 0.005
vf_coef = 0.5
max_grad_norm = 0.5
use_sde = false
sde_sample_freq = -1
checkpoint_interval = 100000
```

Python artifacts include SB3 model zips, metadata sidecars, periodic checkpoints, TensorBoard logs, evaluation JSON/CSV, target-change validation JSON/CSV, and comparison output.

The bridge protocol rejects envelopes without nonblank request IDs, non-object payloads, oversized frames, incompatible schema hashes, non-finite actions, malformed action matrices, and malformed/non-finite/out-of-bounds step responses before they are accepted by the session.

The Python client validates advertised bridge specs before use, including protocol version, morphology, slot/control counts, schema hashes, schema ids, schema versions, unique field names, and finite ordered field bounds. It rejects malformed outgoing reset masks, action batches, manual target matrices, and manual command matrices before sending them. It also repeats the critical response-contract checks on received reset/update/step payloads, so malformed slot counts, observation sizes, observation bounds, non-finite rewards, non-boolean terminal flags, and non-object infos fail as `BridgeError` before Gymnasium or SB3 wrappers see them. Transport timeouts, disconnects, malformed protocol frames, and request-id mismatches also clear stale client state and surface as `BridgeError`.

## Commands Run Or Safe To Run

Current non-live verification:

```bash
./gradlew :minecraft_machines:common:test :minecraft_machines:neoforge:compileJava
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

Do not run these for Duopod inspection without explicit user authorization:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest
./gradlew :minecraft_machines:neoforge:runServer
./gradlew :minecraft_machines:neoforge:runClient
```

## In-Game Operator Commands

Spawn and inspect:

```mcfunction
/mm duopod spawn
/mm duopod telemetry on
/mm duopod remove_nearest
```

Control authority:

```mcfunction
/mm duopod control_sweep
/mm duopod control_sweep_at <x> <y> <z> <north|south|east|west>
```

CEM:

```mcfunction
/mm train duopod cem start
/mm train duopod cem start_at <x> <y> <z> <north|south|east|west> 64 50 160 4 3 0 max_slots 32 flat_commands
/mm train duopod cem status
/mm train duopod cem evaluate_best
/mm train duopod cem validate_target_change
/mm train duopod cem replay_best
/mm train duopod cem replay_target 8 4
/mm train duopod cem replay_target 8 -4
/mm train duopod cem save_checkpoint
/mm train duopod cem load_checkpoint
/mm train duopod cem clear
```

Generic aliases:

```mcfunction
/mm train status
/mm train stop
/mm train clear
/mm train target <x> <y> <z>
/mm train cem start duopod
/mm train cem save <name>
/mm train cem load <name>
```

Bridge:

```mcfunction
/mm train bridge start duopod <slotCount>
/mm train bridge start duopod <slotCount> <port>
/mm train bridge start duopod_at <x> <y> <z> <north|south|east|west> <slotCount>
/mm train bridge start duopod_at <x> <y> <z> <north|south|east|west> <slotCount> <port>
/mm train bridge status
/mm train bridge stop
```

## Python Commands

Install:

```bash
cd training/python
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e ".[dev]"
```

Unit tests without Minecraft:

```bash
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

Train PPO against a live bridge:

```bash
python -m minecraft_machines_training.train_ppo \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --envs <slotCount> \
  --timesteps 1000000 \
  --curriculum flat_commands \
  --run-name duopod_ppo
```

Resume PPO:

```bash
python -m minecraft_machines_training.train_ppo \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --envs <slotCount> \
  --timesteps 250000 \
  --curriculum flat_commands \
  --run-name duopod_ppo_resume \
  --resume-from runs/duopod_ppo/model.zip
```

Evaluate PPO:

```bash
python -m minecraft_machines_training.evaluate \
  --host 127.0.0.1 \
  --port <port> \
  --token <token> \
  --morphology minecraft_machines:duopod \
  --model runs/duopod_ppo/model.zip \
  --output runs/duopod_ppo/evaluation.json
```

Validate PPO target changes:

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

Compare CEM and PPO evaluation outputs:

```bash
python -m minecraft_machines_training.compare \
  --left cem_eval.json \
  --right ppo_eval.json \
  --left-label cem \
  --right-label ppo \
  --output comparison.json
```

Print/preflight the live runbook:

```bash
python -m minecraft_machines_training.live_runbook --mode all --check
```

## Current Evidence

Latest non-live verification from this report pass:

```bash
./gradlew :minecraft_machines:common:test
./gradlew :minecraft_machines:neoforge:compileJava
```

Result:

```text
Both Gradle commands passed with BUILD SUCCESSFUL on 2026-06-25.
```

```bash
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

Previous documented Python result, not rerun for the standing-stabilization change:

```text
Ran 59 tests in 0.150s
OK (skipped=7)
```

Fresh user-authorized live validation from 2026-06-23:

```text
MM_DUOPOD_CONTROL_SWEEP_END passed=true bestForward=1.359688529401456 bestPositiveYaw=1.3686753416120012 bestNegativeYaw=-1.9000583695557915
MM_DUOPOD_CEM_END run=9922bd58-0f4b-408d-8eb5-f43a3d282066 reason=complete bestAggregate=398.2026
MM_DUOPOD_CEM_EVALUATION_END evaluation=2c7cb6e5-850b-4905-878b-5765f648be45 reason=complete successRate=0.1905 meanReturn=22.4633
MM_DUOPOD_CEM_TARGET_CHANGE_END validation=a0b16bf6-daa3-4904-a13e-687a0c485b90 reason=complete sameMachine=true yawDelta=0.0191
```

The evaluation artifact at `minecraft_machines/neoforge/runs/server/world/minecraft_machines/duopod_cem_evaluation_latest.json` reports 21 episodes, 4 successes, `mean_final_distance=4.442008420249272`, and `mean_distance_travelled_blocks=3.956405509800593`. The target-change artifact at `minecraft_machines/neoforge/runs/server/world/minecraft_machines/duopod_cem_target_change_latest.json` records `sameMachine=true`, `phase_continued=true`, a left-action change from `0.1495` to `-0.0957`, and observed yaw response delta `0.0191`.

The saved checkpoint for operator replay is:

```text
minecraft_machines/neoforge/runs/server/world/minecraft_machines/duopod_cem_best.json
```

Checkpoint load/save is world-local. If the operator is using a client save such as `minecraft_machines/neoforge/runs/client/saves/New World (3)`, copy the JSON into that save's `minecraft_machines/` directory before running `load_checkpoint`.

Duopod CEM reward visualization is opt-in with `/mm train reward_viz on` or `/mm train duopod cem reward_viz on`. Active CEM training and `replay_best` then follow one representative slot with a cyan goal arrow, yellow point-target pole, green/red actual-motion arrow, reward sign bar, negative-reward bar, and an actionbar comparing `Goal F/L/Y` to `Actual F/L/Y` with net `Reward`, summed `Good`, summed `Bad`, and `Err F/L/Y`. Held-out evaluation and target-change validation remain diagnostics/log/JSON driven.

To watch the learned policy in Minecraft, start a normal client/server run and use:

```mcfunction
/mm train duopod cem load_checkpoint
/mm train duopod cem replay_best
/mm train duopod cem replay_target 8 4
/mm train duopod cem replay_target 8 -4
/mm train clear
```

Follow-up implementation after the failed live gate:

- `flat_point_goals` training now covers seven bearings (-90 through +90 degrees) at 4 and 8 blocks.
- CEM point-goal runs that use fewer than all 14 training scenarios roll the scenario window by generation, so low-concurrency runs no longer repeat the same six targets every generation.
- This changes training coverage only; the held-out manifest remains `duopod_held_out_point_goals_v2` with the same compatibility hash and unseen 12-block distances.

Current safety/config checks:

```text
enable-rcon=false
rcon.password=
rcon.port=25575
server-ip=127.0.0.1
no matching Minecraft, NeoForge run task, GameTest, runServer, runClient, Gradle wrapper, or Gradle daemon process remained after verification
no listener remained on TCP port 25565 or 25575
```

The Python stdlib mock suite skips optional Gymnasium/SB3/live tests when the system Python lacks those dependencies or no live bridge is running.

Historical Minecraft run notes may exist in older sections of the guide, but they are debugging context only. The authoritative live evidence is the post-inspection validation above.

## Known Limitations

- The accepted result covers one Duopod, one world seed, a flat lane arena, and three commanded speeds; it is not evidence of arbitrary-terrain or cross-version generalization.
- One fresh optimizer seed establishes that this run can learn a valid gait, not a statistical optimizer-success rate.
- No-respawn target-change validation shows continuity and a modest yaw/action response, not a polished steering controller.
- Snapshot reset is not used for live Duopod physics yet; respawn reset remains the safe path.
- System Python does not currently include Gymnasium, Stable-Baselines3, or pytest. Optional real-dependency tests require a virtual environment.
- A compatible checkpoint resume restores distribution/generation state but not the Java RNG cursor, so its future sample trajectory is not bit-exact with an uninterrupted run.
- The runtime benchmark schema records `code_revision: null`; the packaged build is hashed separately but is not claimed as the exact benchmark binary.

## Follow-Up Opportunities

Useful next improvements are independent training seeds, rough-terrain held-out arenas, smoother no-respawn steering, exact RNG-state resume, and a runtime build-revision fingerprint. The current generation-5 checkpoint is promoted because it passed the unchanged fixed benchmark twice; replay remains a visual diagnostic only.
