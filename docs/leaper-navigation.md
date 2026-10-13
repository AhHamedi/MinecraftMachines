# Leaper navigation engineering record

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The Leaper has its own learned foot-space gait and holonomic point controller. The original plank quadruped keeps its separate policy and targeting stick. This work preserves the Leaper's blocky exterior, 13 rigid bodies, 12 native limited motor joints, 0.60 rad/s target slew, and 150,000-unit torque caps.

## Control

`LeaperGait` solves the radial three-joint leg geometry analytically. Its actions are finite, limited joint targets. The learned parameters determine stride, foot lift, cycle duration, stance fraction, stance radius and height, relative leg phases, and body-tilt feedback. Forward, sideways and turning requests are combined in foot space. `LeaperPointController` converts a world-space destination and observed displacement into those three requests, with bounded acceleration, braking, near-goal holding, and a settling requirement.

The chassis remains free in all six degrees of freedom. Locomotion does not teleport bodies, pin feet, attach the chassis to the world, or apply navigation forces directly to it. Teleports in training initialize independent episodes only. The optional camera follows by moving the player.

## Grip and throughput experiments

Sable's `PhysicsBlockPropertyHelper.getFriction` confirms the foot-pad block state receives the intended coefficient. The native coefficient is 1.65 on `leaper_part[part=43]`, the terminal foot collision cell; the remaining cells retain their defaults. This is finite contact friction, not adhesion.

Matched 60-second diagonal-gait trials compared coefficients 1.0 and 1.65. Total near-ground foot displacement in the forward trial fell from 73.40 to 30.61 blocks. Turning accumulated 3.80 versus 5.04 radians, respectively. The corresponding forward distances were 19.60 and 20.56 blocks. The earlier crawl-like candidates dragged feet, and increasing their friction did not solve that problem. The diagonal pattern and sufficient swing clearance were necessary.

Eight robots completed 65 simulation seconds each in 26.61 seconds of wall time. Sixteen completed the same duration in 54.11 seconds. Aggregate throughput was effectively unchanged, so training uses eight robots per population. The simulator runs unthrottled in a GameTest server while retaining its normal physical timestep and 0.05-second control updates; the production tick rate is unchanged. Full build/startup time is excluded from these measurements.

Foot-sliding and clearance metrics use the known flat-floor height and toe collision extent, not force-sensor readings. Sable itself resolves all collisions and friction. The archived probe reports contain the observed positions, joint tracking, and floor-relative toe measurements.

## Learning and qualification

Training uses seeded cross-entropy search over interpretable controller parameters, not a neural network. This matches the compact flat-ground task and permits direct inspection of every actuator command.

The gait search uses 24 generations of eight candidates. Every candidate runs forward, reverse, sideways, and turning episodes, each with settling, 25 seconds of movement, and a five-second stop. The objective balances progress, posture, sliding, joint tracking and stop drift. An explicit penalty rejects insufficient clearance by any foot. An earlier objective with only a small clearance bonus favored a candidate that dragged a rear foot; that rejected run is archived rather than used for deployment.

A second seeded search tunes ten destination-controller parameters against four fixed training tasks. Qualification uses separate goal distances and initial headings, four floor materials, and selected physical pushes. A successful destination requires measured settling followed by five additional seconds within the acceptance radius. Continuous route tests cover repeated waypoints, close corrections, stopping and restarting, and retargeting in motion. Gait qualification checks both command signs, diagonal travel, half-amplitude requests, repeated lift/plant cycles, joint limits, anchor separation and posture.

The deployable model is loaded only when its qualification flag, morphology version, controller versions and native foot friction match. Raw training candidates are not loaded by the player's targeting controls.

## Reproduce

Use Java 21. All fixtures are disposable test worlds; player saves are separate. Reports are retained when a fixture world is rebuilt.

```sh
./gradlew :minecraft_machines:common:test
./gradlew :minecraft_machines:neoforge:runGameTest -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperMode=train -PleaperPopulation=8
./gradlew :minecraft_machines:neoforge:runGameTest -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperMode=qualify-gait
./gradlew :minecraft_machines:neoforge:runGameTest -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperMode=train-navigation
./gradlew :minecraft_machines:neoforge:runGameTest -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperMode=points-trained
./gradlew :minecraft_machines:neoforge:runGameTest -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperMode=points-packaged
./gradlew :minecraft_machines:neoforge:runGameTest -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperMode=routes
```

`-PleaperScratch=true` selects a second disposable fixture directory for independent preliminary tests. `-PleaperMode=probe-grid` runs the comparative gait seeds; `-PleaperMode=points-seed` runs the untuned eight-direction baseline. Final numeric qualification results and the release provenance are recorded in `artifacts/leaper-navigation/`.

## Player controls

- `/mm leaper spawn` and `/mm leaper stick` create a Leaper and give its targeting stick.
- Right-click clear flat ground with the Leaper stick to select a destination. Shift-right-click stops it.
- `/mm leaper goto <x> <z>` selects a point directly; `/mm leaper stop` stops movement.
- `/mm leaper watch` follows the robot; `/mm leaper unwatch` releases the camera.
- `/mm leaper status` reports its mode, target distance, body tilt and joint-anchor separation.
- `stand`, `crouch` and `articulate` remain available as inspection poses.

Qualification is for clear flat ground. Terrain traversal, obstacle planning and leaping are separate tasks.

## Qualified results

The packaged model passed 96 point trials across the comparison and fresh target sets. Mean final error was 0.148 blocks and maximum error 0.273 blocks. The trained controller reduced mean error on the paired 48-case comparison from 0.205 to 0.134 blocks (34.7%), with mean arrival time decreasing from 37.98 to 37.23 seconds. The identical packaged replay reproduced the comparison results.

All 16 motor-primitive trials passed. The four continuous routes produced 15 confirmed waypoint arrivals, including an in-motion retarget; emergency stop drift was 0.299 blocks. A separate 96-block approach with a physical push arrived in 209.85 seconds with 0.0763 blocks error, then held position.

## Native client verification

The qualified model was loaded in the actual Minecraft client. A real mouse right-click with the marked Leaper stick selected a raycast destination, and a real Shift-right-click stopped the robot. Following the final release restart, a 9.6-block stick-selected approach at the normal 20 ticks per second arrived with a displayed error of 0.08 blocks.

The user's exploratory session produced another 16 announced arrivals, with displayed errors up to 0.24 blocks. That session also included manual tick-rate changes and physics-gun manipulation; it is not counted as part of the controlled 96-case qualification. At a requested 400 ticks per second the integrated server reported falling behind. Production startup retains the normal tick rate.

Live testing exposed an arrival-timer issue: the original travel budget could expire after a successful arrival and display a misleading stop message. Settled arrivals now renew the timer while preserving a bounded recovery budget after a disturbance. This correction changes the live lifecycle only; the qualified gait, destination feedback parameters and physical servo limits are unchanged.

The corrected live client completed a 12-block approach and then held through 4,000 additional simulation ticks (200 simulation seconds), exceeding the old journey timeout without a false stop. Final distance was 0.182 blocks, tilt 0.23°, and anchor separation 0.0007 blocks. The client was returned to normal 20-tick operation; its measured mean tick time was 3.0 ms, with P99 3.4 ms in the final 100-tick sample.

Watch the native navigation recording (local evidence: `artifacts/leaper-navigation/leaper-navigation.mp4`). This 32-second excerpt shows the latter part of that approach, braking and holding, at normal game speed. The unedited 42-second window recording and encoding provenance are retained alongside it. No motion interpolation, visual retouching or synthetic frames were used.
