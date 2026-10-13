# Trained Sable quadruped

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The quadruped is implemented, trained, and verified in Minecraft 1.21.1 with Sable 2.0.0. It uses nine rigid bodies and eight directly actuated hip/knee joints. Its chassis is free to move and rotate in all six degrees of freedom. No world constraint, locomotion teleportation, or artificial supporting force is used during walking.

The new [point navigation controller](quadruped-navigation.md) adds destinations, turns, reverse approaches, and multi-point routes. The measurements below document the original forward gait.

## Play

On this development machine, open **Trained Quadruped Lab** in the development client. The world is already prepared, and its datapack starts the point-navigation demonstration automatically. `tools/Run Quadruped Lab.command` opens that world.

In another clear, flat world with the required mods installed:

```text
/mm quadruped spawn
/mm quadruped walk
/mm quadruped stop
/mm quadruped watch
/mm quadruped unwatch
/mm quadruped status
/mm quadruped remove
```

Spawn places the robot twelve blocks north of the player. Allow at least nine blocks of width and a long clear route north. Watch follows the robot with the player's camera; unwatch releases it. Each player can own one active quadruped. Active robots are removed on a clean server shutdown; spawn another after reopening the world. This implementation is an automatically assembled machine, not a survival crafting recipe.

The built add-on is `release/MinecraftMachines-Trained-Quadruped.jar`. The Create, Simulated, Create Aeronautics, Sable, and platform dependencies listed in the main README remain required and are not bundled in that JAR.

## Measured results

The saved controller passed all six 60-second evaluation trials. The three stride settings (0.65, 1.0, 1.3) were tested both without disturbance and with a lateral/angular velocity disturbance applied to the physical chassis after 15 seconds.

| Trial | Trained distance | Initial gait distance | Final lateral error | Failed |
|---|---:|---:|---:|---|
| 0.65, undisturbed | 71.22 blocks | 25.04 blocks | 0.023 blocks | No |
| 1.00, undisturbed | 89.57 blocks | 34.67 blocks | 0.041 blocks | No |
| 1.30, undisturbed | 91.82 blocks | 37.46 blocks | 0.110 blocks | No |
| 0.65, disturbed | 71.39 blocks | 25.05 blocks | 0.262 blocks | No |
| 1.00, disturbed | 90.59 blocks | 34.63 blocks | 0.012 blocks | No |
| 1.30, disturbed | 91.69 blocks | 38.42 blocks | 0.034 blocks | No |

The mean improvement over the initial gait was **2.59×**. The stationary control moved less than 0.001 blocks per trial. The controller remained within approximately 2.9 degrees of upright throughout the six trials.

A separate test loaded the packaged model into a freshly assembled robot in a fresh world. It walked for three minutes, stopped for twenty seconds, resumed, and received a stronger disturbance (0.5 lateral velocity and 0.2 angular velocity). It travelled **274.07 blocks**, finished **0.068 blocks** off its initial line, and remained within approximately 2.5 degrees of upright. Stopping drift was **0.274 blocks**. Total test duration, including the initial settling period, was 204 simulated seconds.

The playable client was also exercised directly: more than 1,100 blocks of travel, including a stop and restart. The video and screenshot below were captured by Minecraft itself during that client run.

Ten additional fresh robots tested the unchanged packaged controller outside its training conditions. The acceptance criteria were fixed before running: at least 40 blocks in 60 seconds, final lateral error at most 2 blocks, and no fall or lane escape. **Seven of ten passed.** These results qualify the ordinary flat surfaces and disturbances below; the three failures remain recorded.

| Additional condition | Forward distance | Final lateral error | Accepted |
|---|---:|---:|---|
| Smooth stone, fresh start | 90.00 | 0.031 | Yes |
| Grass | 89.00 | 0.054 | Yes |
| Oak planks | 89.94 | 0.099 | Yes |
| Cobblestone | 90.01 | 0.031 | Yes |
| Initial heading +15° | 88.67 | 0.004 | Yes |
| Initial heading −15° | 89.34 | 0.196 | Yes |
| Stronger push: 1.0 lateral / 0.4 angular velocity | 89.79 | 0.023 | Yes |
| Blue ice | 23.15 | 10.553 | **No: left the lane** |
| Repeated half-block ridges | 12.22 | 0.243 | **No: insufficient progress** |
| Half-block platform | 15.79 | 1.649 | **No: insufficient progress** |

All seven accepted additional trials remained within approximately 2.8° of upright. The heading cases rotate the assembled robot once during initialization; walking then uses the same physical motors and controller as every other trial. No parameters were tuned on these results.

- Actual Minecraft video (local evidence: `artifacts/quadruped/minecraft-walking.mp4`)
- Actual Minecraft screenshot (local evidence: `artifacts/quadruped/minecraft-screenshot.png`)
- [Training and paired evaluation](evidence/quadruped/training-and-evaluation.json)
- [Independent saved-model replay](evidence/quadruped/saved-replay.json)
- [Complete held-out qualification, including failures](evidence/quadruped/held-out-qualification.json)

## What was learned

The policy combines inverse kinematics, periodic foot trajectories, and measured attitude/heading feedback. Cross-entropy optimization learned ten continuous parameters: stride, foot clearance, period, stance duty factor, standing height, foot offset, left/right phase, front/rear phase, pitch feedback gain, and roll feedback gain. It is an evolved feedback controller, not a PPO neural network or a claim of a research state-of-the-art result.

Training used seed `20260906`, sixteen physical robots per generation, four elites, and forty generations. Each candidate received sixty warmup ticks and four hundred scored ticks. Fitness uses measured center-of-mass displacement, penalizes lateral drift and heading error, and rejects falls, excessive height change, invalid physics state, or lane escape. Evaluation was performed after optimization without changing the saved parameters.

Sable advances 0.05 simulated seconds per server tick in the installed engine. The gait is updated every two ticks. Native motors use explicit force limits and wake their connected bodies when a command changes. Joint angles are measured from relative physical-body orientation rather than commanded Create gear rotation.

The model and its paired evaluation are bundled as `data/minecraft_machines/quadruped/trained.json`. In-game loading checks the morphology/policy contract, parameter validity, and all six acceptance trials.

## Reproduce

Use Java 21. On this machine it is installed under `~/.local/jdks/jdk-21.0.10+7/Contents/Home`.

```bash
./gradlew :minecraft_machines:common:test
./gradlew :minecraft_machines:neoforge:runGameTest
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped
```

Retrain and run the paired evaluation:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped \
  -PquadrupedTrain=true
```

The dedicated quadruped run uses `minecraft_machines/neoforge/build/quadruped-gametest/`. Its disposable world is recreated for each run; experiment JSON files remain under `quadruped_training/`. A training run does not automatically replace the packaged model. Review every acceptance trial before promoting a new candidate.

To reproduce the additional qualification and terrain boundary probes:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped_robustness
```

This diagnostic suite deliberately retains the original ten-condition acceptance test. It currently exits with a failed test because blue ice and the two half-block obstacle conditions are unsupported; the full per-condition report is written to `quadruped_training/held-out-qualification.json`. This is separate from the passing flat-ground locomotion and regression suites.

The original integration suite passed all thirty tests; the common Java suite passed all ninety-seven tests. The quadruped's physical standing/joint-tracking check and independent saved-policy replay passed separately. The Python baseline ran seventy-five tests: sixty-eight passed and seven optional/live tests were skipped. Python/PPO tooling is not used to run this controller.

## Scope and limits

The verified task is straight-line locomotion on flat smooth stone, grass, oak planks, and cobblestone, including stop/restart, initial heading correction, and the measured disturbances. **Blue ice and half-block obstacles failed qualification.** Arbitrary terrain, full-block stairs, turning to arbitrary goals, payload changes, alternate robot materials, and distant world coordinates have not been qualified. The paired trials and held-out conditions are engineering acceptance evidence, not a large statistical generalization study.

Near-origin arenas are intentional. An initial far-origin test produced velocity/displacement inconsistencies and was rejected; its output is retained under `artifacts/locomotion-audit/`. Persistent physics state from an interrupted test can contaminate later measurements, so quadruped checks now start with a fresh disposable world. Player worlds are not cleared by the test task.
