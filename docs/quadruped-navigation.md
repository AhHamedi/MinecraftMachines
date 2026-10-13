# Trained point navigation on flat ground

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The quadruped accepts a destination in Minecraft world X/Z coordinates, turns and walks toward it, brakes near the point, and holds its position after arrival. It can change destination while moving. Nearby points behind the body are approached with reverse steps. Its eight physical joints, nine rigid bodies, torque limits, and freely moving chassis are unchanged.

## Measured results

The trained model passed **80 of 80 destination trials**. Loading it from the packaged resource and repeating those same 80 cases also passed; these are 80 unique cases, not 160 unique cases.

| Measure | Result |
|---|---:|
| Average final position error | **0.082 blocks** |
| Worst final position error | **0.159 blocks** |
| Average arrival time | 24.43 simulated seconds |
| Longest arrival time | 40.1 simulated seconds |
| Greatest measured torso tilt | Approximately 3.8° |
| Required stationary hold after arrival | 5 seconds |
| Route scenarios | **4/4 passed**, 16 reached waypoints |
| Stop/resume test: stopping drift | 0.052 blocks |

The baseline with the same controller structure and zero navigation parameters also passed all 80 cases. Training reduced mean arrival error from 0.140 to 0.082 blocks, approximately **41.5%**, while average arrival time increased from 23.83 to 24.43 seconds. The improvement is primarily precision. The learned gait and engineered feedback structure provide the underlying locomotion capability.

The playable Minecraft client completed two square demonstrations, with waypoint errors from 0.005 to 0.137 blocks. It then reached X=100, Z=0 with 0.139 blocks of error while the player was at approximately X=400, Z=400, well away from the robot. This verifies the actual commands, model loading, and independent chunk loading in the client.

- Actual Minecraft navigation video (local evidence: `artifacts/quadruped-navigation/minecraft-navigation.mp4`)
- Destination arrival screenshot (local evidence: `artifacts/quadruped-navigation/at-destination.png`)
- [Measured paths and all arrival errors](evidence/quadruped-navigation/navigation-results.png)
- [Continuous route plots](evidence/quadruped-navigation/routes.png)
- [Packaged-model qualification and traces](evidence/quadruped-navigation/packaged-qualification.json)
- [Route qualification and traces](evidence/quadruped-navigation/packaged-routes.json)
- [Training history and parameters](evidence/quadruped-navigation/training.json)

The video uses 320 native Minecraft screenshots sampled at approximately eight frames per second. Encoded playback repeats frames; it does not synthesize or interpolate robot motion. The simulation itself advances at 20 ticks per second.

## Controls

The lab starts in creative mode with a **Walker Targeting Stick**. Hold it and right-click clear ground within 128 blocks to set a destination; Shift-right-click stops the walker. Green particles mark the chosen point. You can select a new point while it is moving. Aim at a flat, unobstructed route: obstacle avoidance is not implemented. Use `/mm quadruped stick` for a replacement. Ordinary sticks do not control the robot.

```text
/mm quadruped spawn
/mm quadruped stick
/mm quadruped goto 20 -20
/mm quadruped demo
/mm quadruped stop
/mm quadruped walk
/mm quadruped watch
/mm quadruped unwatch
/mm quadruped status
/mm quadruped remove
```

`goto` accepts absolute X/Z coordinates and Minecraft `~` coordinates relative to the player. Integer coordinates are used exactly, without adding half a block. A new `goto` replaces the current destination. `demo` visits four points around a 20-block square and returns to its starting point. `stop` clears the destination and route. `walk` walks straight in the robot's current direction; a newly spawned robot faces north.

The destination is marked with green particles. The game shows remaining distance and reports the measured position error on arrival. `watch` follows with the player's camera. The robot owns a temporary chunk-loading ticket, so navigation also works while the camera is elsewhere. Tickets move with the robot and are released when it is removed, its owner disconnects, or the server stops. They do not change saved `/forceload` settings. Robots remain session-scoped rather than persistent survival machines.

Use a clear flat area at least nine blocks wide. The command accepts destinations within 512 blocks. There is no obstacle avoidance or route finding around walls, gaps, water, or other robots. The existing ice and half-block obstacle failures remain relevant.

Nearby point sequences can require extra alignment and position corrections. The tight two-block-square test took 91.5 simulated seconds; the 20-block-square test took 120.6 seconds. This is precise point navigation, with a conservative final approach. Independent sideways velocity control, arbitrary terrain, different robot payloads, and distant world-coordinate precision are not qualified.

## Controller and training

The controller has two layers:

1. A goal controller observes actual chassis position and heading, estimates linear and turning velocities from their changes, and requests signed forward and turning motion. It slows for turns and final approach, limits changes in commands, and uses a braking/holding state with separate entry and exit thresholds. Small final steps overcome contact friction. It only declares arrival after position and motion have settled.
2. The directional gait converts those requests into different signed strides for the left and right legs. Foot trajectories and inverse kinematics produce eight bounded joint targets. Attitude feedback is transformed into the body's heading frame, so it remains valid after turning. Actuation uses only Sable joint motors.

The original gait parameters remain learned from the earlier physical training. Navigation adds ten learned parameters for heading gain, turning damping, maximum turning command, heading-dependent forward speed, approach slowdown distance, cruising command, braking radius, velocity lookahead, and forward/turn command rate limits.

Navigation version 2 was optimized with cross-entropy search: seed `2026090602`, 16 candidates, four elites, 28 generations, and five fixed training destinations per candidate. That is 2,240 physical destination trials. Training rewards distance reduction and successful settling, penalizes time and unnecessary travel, and rejects instability or arena escape. Each successful arrival is observed for five additional seconds. The packaged parameters are frozen before qualification.

The mechanism is a trained hybrid feedback controller, not an end-to-end neural policy. A relevant research reference for separating navigation from physical locomotion is the [Learning Agile Navigation project](https://sites.google.com/leggedrobotics.com/agile-navigation); this implementation does not reproduce that project's neural architecture or claim its research results.

## Qualification protocol

The qualification set contains eight compass directions, 64 independently randomized destinations and initial headings, and eight nearby destinations. Randomized cases use seed `2026090702`, distinct from training. The surfaces are smooth stone, grass, oak planks, and cobblestone. Sixteen cases receive a physical lateral/angular disturbance. Robots are freshly assembled for each trial.

Acceptance requires arrival within 90 simulated seconds, position error no greater than 0.75 blocks, speed no greater than 0.15 blocks/second after settling, five seconds of holding within tolerance, no fall, and no arena escape. Version 2's internal holding threshold is tighter than that acceptance limit. Final qualification and the saved model must contain identical parameter arrays; the in-game model loader checks that association.

Additional route checks cover a 20-block square, a six-point zigzag, a two-block square, and changes of destination during motion followed by stop/resume. These checks never reset the robot between destinations.

The first qualified navigation controller and its measurements are retained under `artifacts/quadruped-navigation/v1/`. Its largest stopping errors exposed motion away from the target when the feet returned abruptly to their rest pose. Version 2 adds progressive braking, stricter position correction, and short reverse approaches. The initial failed contact-friction baseline is also retained.

## Reproduce

Use Java 21 and the project's pinned Minecraft/Sable dependencies.

```bash
./gradlew :minecraft_machines:common:test
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped_navigation \
  -PquadrupedNavigation=packaged
```

To retrain and evaluate a candidate without replacing the installed model:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped_navigation \
  -PquadrupedNavigation=train
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped_navigation \
  -PquadrupedNavigation=evaluate
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped_navigation \
  -PquadrupedNavigation=routes
```

Other diagnostic modes are `probe`, `baseline`, and `evaluate-baseline`. Reports are written under `minecraft_machines/neoforge/build/quadruped-gametest/quadruped_navigation/`. Only the disposable test world is recreated between runs; reports and player worlds are retained. Evaluation traces record elapsed simulated time, actual relative position, heading, destination error, and estimated speed. Native physics may introduce small run-to-run differences even with the optimizer's random seed fixed.
