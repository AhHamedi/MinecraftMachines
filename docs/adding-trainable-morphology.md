# Adding A Trainable Morphology

Last updated: 2026-08-03

This document is the extension checklist for adding another trainable machine to the shared locomotion framework. Add a morphology by implementing a new adapter around the generic training contracts; do not fork CEM, PPO, the bridge protocol, or the vector environment.

The current framework is servo-first. A discovered Robotic Servo Joint is both one normalized action dimension and one proprioceptive telemetry source. Optional sensor blocks may supplement observations later, but they are not the base requirement for a trainable machine.

## Core Contract

Implement `content/training/environment/TrainableMorphology<M>` for the new live machine type `M`.

The adapter owns these responsibilities:

- `id()`: stable morphology id such as `minecraft_machines:duopod`.
- `observationSpec()` and `actionSpec()`: stable schema metadata with deterministic compatibility hashes.
- `spawn(TrainingSpawnContext)`: create or discover one trainable machine for a slot.
- `applyAction(M, double[])`: decode normalized actions and apply them on the server thread.
- `observe(M, EpisodeDefinition, EpisodeRuntime)`: return a finite, normalized observation vector matching `observationSpec`.
- `calculateReward(...)`: return a `RewardBreakdown` with named components.
- `inspectHealth(...)`: distinguish valid machines from physical/task failures with `MachineHealth`.
- `distanceToTarget(...)`: report point-goal distance when the task uses local point targets.
- `diagnosticInfo(...)`: expose debugging metrics for bridge/CEM evaluation output.
- `reset(...)`: return a `ResetResult<M>` with the reset observation, previous distance to target, and whether the machine respawned.
- `destroy(M)`: remove owned bodies and cleanup registry entries.

Generic training code must not know actuator meanings. Names like `left`, `right`, `front_left`, or `tail` belong in the morphology adapter and schema metadata only.

## Implementation Steps

1. Define physical ownership.

   Record the machine id, batch id, owned Sable sublevel ids, servo instance ids or positions, spawn pose, and canonical model axes. Cleanup must be able to remove every owned sublevel and registry entry. Treat multi-body assembly transactionally: if any limb, base, servo, or constraint step fails, roll back every body and collision-registry entry already created before propagating the error.

2. Discover servos deterministically.

   Use a stable ordering that does not depend on hash iteration or transient block-entity lookup order. This order becomes the action order and per-servo observation order. Reject missing, duplicate, or invalid servos early.

3. Build schemas from servo telemetry.

   Prefer `ServoTelemetrySchemaBuilder.observationSpec(...)` and `ServoTelemetrySchemaBuilder.actionSpec(...)`. The base observation fields are local locomotion state and command fields; each servo contributes target angle, actual angle, angle error, angular velocity, generated speed, estimated torque, load, limits, enabled/attached/valid state, and previous action. If extra sensors are added later, append fields intentionally and bump the schema version.

4. Create a narrow control object.

   Decode each normalized action in `[-1, 1]` to a servo target angle using the real servo limits. Read telemetry from the real `RoboticServoJointBlockEntity` API. Do not expose raw RPM as the generic action semantics.

5. Encode observations in the local frame.

   Project base linear/angular velocity into the documented forward/right/up axes. Represent tilt through projected gravity, not absolute Euler angles. Include `LocomotionCommand`, phase, servo telemetry, and previous actions. Replace recoverable non-finite values with zero, clamp to `[-1, 1]`, and expose the repaired-observation flag through environment info.

6. Define task modes and command generation.

   Command-tracking tasks can use direct `LocomotionCommand` values. Point-goal tasks should convert local target offsets through `PointTargetCommandGenerator` or equivalent deterministic math. Policies must not observe absolute target/world coordinates.

7. Implement reward and termination.

   Keep reward components named and inspectable. Use `terminated` for success and machine failure, and `truncated` for time limits. Health checks should cover missing bodies/servos, invalid pose, excessive tilt, falling below a configured Y threshold, leaving the training radius, and repeated invalid observation state.

8. Choose reset behavior.

   Use `RESPAWN` until snapshot reset is proven for the machine's constraints. If snapshot reset is added, validate base and child body poses, zero velocities, neutral servo commands, phase, previous actions, reward totals, and point-target distance bookkeeping together.

9. Wire training entry points and runtime ownership.

   Java CEM should instantiate the same `LocomotionVectorEnvironment<M>` as the bridge. The Python bridge must expose the morphology id, observation/action specs, hashes, slot count, control ticks, curriculum, and seed through `HELLO`/`GET_SPECS`. If a vectorized task spans multiple chunks, acquire a task-scoped runtime chunk lease for the full spawn/search/travel corridor before assembly, wait until every chunk is position-ticking, retain it across batches, and release only tickets owned by the task on every close path. Do not use persistent `/forceload` saved data for an experiment lease.

10. Persist policy artifacts safely.

    Any Java checkpoint must include format version, policy type, morphology id, observation/action schema hashes, normalized curriculum stage, genome, training settings, distribution state if applicable, failure/terminal metrics, evaluation metadata, and an explicit fitness-contract identifier. Exact optimizer resume must additionally validate the complete fitness protocol (episode/control ticks, scenario count, warmup, and task-specific target/horizon settings). A schema-compatible artifact with an old or unknown fitness contract may be used only as a deliberately widened genome seed; reset generation and historical best-fitness ranking. Provide an explicit fresh-start path that bypasses in-memory state without silently deleting the saved artifact.

11. Align training selection with the acceptance gate.

    Dense reward can guide behavior inside an episode, but terminal candidate selection must include the physical metrics required for the eventual claim. Record the terminal snapshot before auto-reset, make failure/stability/success thresholds identical between training and evaluation, give explicit pressure to the weakest scenario, and log dense return separately from selection return. Never allow a failed policy to earn positive credit from ballistic displacement.

## Terrain Policy

The broad Duopod curricula and Python bridge train on ordinary Minecraft-generated terrain. The Java Duopod `walk_forward` publication path is intentionally controlled: it uses temporary identical flat lanes so candidate/controller identity is not confounded with arbitrary spawn-site geometry.

If a future morphology needs terrain metadata, use `TerrainProfile` to describe the episode and make generation/cleanup ownership explicit. A controlled arena must be bounded and reversible: preflight block entities and build-height limits, snapshot every changed block, roll back partial preparation, keep the arena for the whole paired experiment, and restore it only after spawned environments are destroyed. Never place permanent terrain as an undocumented side effect of training.

## Tests

Add pure common tests for:

- observation schema id/version/order/hash;
- action schema id/version/order/hash;
- dynamic size calculation from servo count;
- finite bounded observation normalization and telemetry repair;
- normalized action decoding into servo limits;
- local-frame target/velocity transforms;
- point-target command generation;
- reward progress, tracking, success bonus, and action/load penalties;
- success, failure, and time-limit termination distinctions;
- reset bookkeeping for previous actions, phase, distance, reward totals, and flags;
- protocol shape/schema-hash rejection for bridge requests;
- CEM genome size from `actionSize * (observationSize + 1)`.
- fitness-contract/protocol compatibility and old-contract restart behavior;
- acceptance-aligned terminal shaping, including unstable-positive displacement;
- common-random-number schedules across candidates where paired comparison requires them.

Add Python mock tests when the bridge surface changes:

- client handshake and schema capture;
- Gymnasium wrapper spaces;
- Stable-Baselines3 VecEnv reset/step shape behavior;
- terminal observations and reset observations;
- timeout and disconnect errors;
- training/evaluation metadata compatibility;
- comparison rejection for mismatched manifests.

Add GameTests only for live Sable/physics behavior:

- spawn creates the expected owned bodies and servos;
- independent servo actions can differ;
- cleanup removes owned sublevels and collision entries;
- same-batch training machines do not collide with each other;
- terrain/world collision is preserved;
- reset or respawn leaves no orphaned bodies;
- no-respawn target updates keep machine id and phase continuity.
- partial spawn failure rolls back every owned body and registry entry;
- runtime chunk tickets cover the complete corridor and are released on success/failure;
- a temporary arena restores the original world and paired lanes reuse identical origins/seeds.

Do not report GameTest or live behavior as validated unless those tests were actually run.

## Live Validation

A morphology is not physically proven by unit tests alone. Before calling it complete, run or have the operator run:

- a visible spawn/cleanup inspection;
- a control-authority sweep showing useful forward movement and both yaw directions where relevant;
- a short Java CEM smoke run through the shared vector environment;
- a bridge smoke session with neutral and non-neutral actions;
- held-out evaluation using the shared scenario manifest;
- no-respawn target-change validation.

Record the exact commands, code/schema/fitness identities, world provenance, and artifact paths for every live validation. Do not turn a compile, unit test, GameTest, training curve, or reward maximum into a physical-performance claim; that claim requires its declared live acceptance artifact.

## What Not To Do

- Do not build a quadruped as a placeholder.
- Do not hardcode action or observation sizes in generic code.
- Do not let the policy observe absolute target coordinates.
- Do not fork reward/reset/observation logic between Java CEM and Python PPO.
- Do not broaden collision suppression beyond scoped machine/batch ownership.
- Do not compare raw CEM fitness across incompatible curricula unless checkpoint selection is explicitly curriculum-scoped.
- Do not let candidate identity select different terrain or randomness in a paired experiment.
- Do not leave temporary arenas, runtime chunk tickets, or partial Sable assemblies behind after cleanup.

A quadruped can follow this process later with more servos, a larger schema, and its own physical validation. It should be added only after its morphology and schema are intentionally designed.
