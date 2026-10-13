# Perceptive Leaper terrain navigation

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The terrain controller adds collision-surface sensing, individual foot-height adaptation and route planning around the three saved flat-ground gaits. Careful, Standard and Horrific are speed preferences. Difficult ground selects a slower climbing gait automatically; clear ground permits the selected pace. The original flat controller remains available with `/mm leaper terrain flat`; `/mm leaper terrain auto` restores terrain adaptation.

## Physical design

Every movement still comes from the 12 native, angle-limited Sable motors connecting 13 free rigid bodies. Foot friction remains 1.65. The terrain controller retains double motor power while climbing, with finite 300,000-unit torque limits and 2.2-radian/second target slew. It never moves or anchors the chassis to a terrain surface. Episode resets and the spectator camera are separate from locomotion.

Surface sensing reads actual Minecraft collision shapes, including half slabs. Fluids and missing support are rejected by route planning. At swing initiation, each leg predicts its landing position, searches nearby supported pad placements and retains its landing height through stance. Swing clearance increases for height changes. The body-height reference follows the support terrain while allowing additional core clearance on steps. A forward terrain scan selects the climbing gait before the body reaches a ledge and keeps it active through the transition.

Route planning uses bounded A* on a two-block grid. Edges sample height changes at sub-block intervals, and each node checks the robot's approximately twelve-block support footprint. The route follower retains the exact selected surface elevation and periodically checks its immediate route against current collision geometry. It replans or stops when that route becomes unsupported. This is a ground-surface representation; it does not implement jumping, swimming, vertical wall climbing or general multi-level cave navigation.

The architecture draws on established elevation-map and foothold-planning approaches, adapted here to directly available Minecraft collision geometry. See [Miki et al., Elevation Mapping for Locomotion and Navigation using GPU](https://arxiv.org/abs/2204.12876). The project does not reproduce that paper's GPU mapping implementation.

## Qualification and model selection

The shipped policy is `leaper_perceptive_terrain_v5`. It reuses the original trained Careful, Standard and Horrific gait checkpoints, with a collision-aware terrain controller and route follower. The terrain layer itself is a perceptive controller with bounded parameters; it is not a newly trained end-to-end terrain neural network.

Seeded cross-entropy search completed 192 native episodes (six generations, eight candidates, four scenarios). Its best training-score candidates did not generalize more reliably than the reference parameters, so they were not promoted. All search results and failed development experiments are retained. The successful improvement was to settle before entering rough terrain, reset the gait phase after settling, and replant when measured body tilt begins developing. Native motor limits remain enforced throughout.

The final controller passed **72/72 destination trials**: 24 development courses, 16 qualification courses, 24 additional courses generated with seed 202609075179, and eight route tests. Three of these are flat-ground regressions; the others cover terrain or obstacle detours. The final additional set was not used for parameter selection. It includes all three pace preferences. The development set was repeatedly used to find and fix failures and is not described as an unseen holdout.

Every successful trial reached the selected X/Y/Z surface, settled, and held for another five seconds. Mean final horizontal error was **0.194 blocks**, maximum **0.451 blocks**. Maximum measured tilt was **27.56 degrees**, maximum joint-anchor separation **0.0179 blocks**, and maximum physical joint-limit overshoot **0.0227 radians** (solver tolerance; commanded targets remain inside limits). No trial crossed the rejection limits of upright component 0.85, anchor separation 0.04 blocks or joint overshoot 0.04 radians.

Four additional native interaction tests passed: moving retargets, rejection of an unsupported elevated target without replacing the valid route, and stopping/changing pace/resuming on stepped terrain. Braking displacement in the two stop cases was 0.20 and 1.73 blocks. Collision-surface checks cover half slabs, full blocks, pad-edge rejection and water rejection. All 114 common unit tests passed.

Qualification uses the same route follower as the live game, not a fixture-aware controller. Surface heights come from actual Minecraft collision shapes. Test fixtures supply starting conditions and selected destinations only. Eight independent rigs run concurrently, with fixed 0.05-second motor-control updates and 0.1-second navigation updates. Faster headless execution does not alter simulated time steps. Foot-plant counts and near-ground foot motion are recorded as geometric diagnostics; they are not contact-force or slip sensors.

## Supported terrain and pace

The tested envelope includes one-block risers on broad treads (roughly 3–6 blocks), total elevation changes up to six blocks, half slabs, mixed-height pads, cross-slopes and routes around six-block walls and pits. The wide robot needs roughly twelve blocks of surrounding support. A reachable-looking point on a narrow pillar or near a cliff may be rejected because its legs cannot be supported.

Careful, Standard and Horrific are preferences on clear ground. Rough terrain selects the slower climbing gait with retained motor strength. The independent flat regressions reached peak speeds of 1.80, 3.32 and 5.49 blocks/second respectively; these are navigation peaks on different headings, not a controlled comparison of straight-line gait speed. The original straight-line sprint qualification remains in `docs/leaper-horrific.md`.

This release does not establish arbitrary-terrain reliability. It does not jump, climb vertical walls, swim, cross narrow bridges, or solve stacked surfaces in caves. A path search can reject unsupported routes; significant tilt or a stalled journey causes the live controller to stop. A player's later world edits can require replanning. Dynamic obstacles, terrain types and gradients beyond the measured suite need further qualification.

## Live playground

`tools/Run Leaper Terrain Lab.command` opens a separate **Leaper Terrain Lab**, preserving the previous flat lab. The world contains a four-block stepped hill, a six-block hill, mixed full/half-block terrain and a wall. It starts with terrain adaptation and Horrific selected.

- Right-click a supported surface with the Leaper Targeting Stick to choose a destination.
- Shift-right-click to stop.
- `/mm leaper pace careful`, `standard` or `horrific` changes the preference and stops the current journey; then choose the next point.
- `/mm leaper watch` follows; `/mm leaper unwatch` releases the camera.
- `/mm leaper terrain flat` selects the preserved flat controller; `auto` restores terrain adaptation.

The standalone release is `release/MinecraftMachines-Leaper-Terrain.jar`. `terrain.json` binds the terrain parameters to SHA-256 hashes of the exact geometry and all three saved gait resources. The loader rejects missing, unqualified or mismatched resources. The previous Careful, Standard and Horrific archives passed a complete 114-file hash check with no changes.

## Reproduction

Use the existing GameTest entry point with `-PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning -PleaperProfile=horrific` and one of:

- `-PleaperMode=terrain-courses`: baseline course evaluation.
- `-PleaperMode=terrain-search`: seeded terrain-parameter optimization.
- `-PleaperMode=terrain-qualify`: qualification routes across the fixed course set.
- `-PleaperMode=terrain-fresh`: the 24 harder development cases.
- `-PleaperMode=terrain-holdout`: the final 24 seeded additional cases.
- `-PleaperMode=terrain-interaction`: moving retarget and stop/pace/resume tests.
- `-PleaperMode=terrain-map`: native collision-surface sensing tests.
- `-PleaperMode=terrain-routes`: obstacle detours and elevation routes.

`-PleaperScratch=true` isolates a disposable test world. Reports remain in the corresponding `leaper_learning` directory and final evidence is archived under `artifacts/leaper-terrain/`.

`tools/package_leaper_terrain.py` validates matching final reports before writing the qualified resource. `-PleaperTelemetry=true` enables one-second native game position/speed/attitude logs for review recordings.

## Native game review

The packaged resource loaded successfully in Minecraft on 7 September 2026. Two actual targeting-stick clicks selected surfaces four blocks apart in elevation. Horrific approached and climbed to `(0.50, -55.00, -50.50)` in 53 seconds, with a final error of 0.37 blocks. Careful descended to `(0.49, -59.00, -98.52)` in 85 seconds, with a final error of 0.04 blocks. Both held after arrival. Shift-right-click stopped the robot, and changing pace and releasing the follow camera worked in the native client.

`artifacts/leaper-terrain/leaper-terrain-review.mp4` shows the complete ascent and a descent excerpt at normal game speed. Cuts omit camera setup and excess time standing still; all original window captures are retained. `native-game-verification.json` contains the exact clip boundaries and one-second native position/attitude samples. The test suite measures every simulation step; the live log samples once per second. No robot teleportation, artificial chassis force, synthetic video frames or playback acceleration was used for these journeys.

The first playground launch exposed a missing scoreboard initializer, which prevented its terrain-building function from running. The initializer was corrected and the intended terrain was built before either recorded journey. This was a world-setup defect, separate from locomotion. The fixed builder and datapack sources are included.
