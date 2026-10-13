# Leaper swing clearance, v10

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

This revision addresses feet sweeping into vertical block faces and throwing the chassis backward. It combines earlier obstacle clearance, coordinated joint target limiting, optimized swing timing and a correction to descending foot trajectories.

## Mechanism

A pure vertical toe request did not necessarily remain vertical after the old servo limiter. It independently clipped each of the three angular increments. A regression test using the v9 rear-leg liftoff pose reproduces a 0.265-block forward command excursion on the first step of a 3.7-block vertical lift. The new limiter interpolates the toe command in Cartesian space and solves for coordinated bounded joint angles. If joint limits prevent progress along that segment, it uses a shared joint-space interpolation fraction. This fallback retains limits and progress but cannot preserve the exact Cartesian path. Neither interpolation is a guarantee about the swept volume of the physically tracking leg.

The existing footprint sweep senses the selected landing corridor. For a raised obstacle the new trajectory blends in an early lift, delays forward sweep, and keeps the foot high until late in swing. The full blend targets 1.2 blocks above the highest sensed surface. This is earlier sustained clearance rather than simply increasing every step's peak height. The baseline trajectory remains for unobstructed steps. A descending foothold no longer counts half the drop depth as an obstacle above the two endpoints; that error had added unnecessary lift and hard landings.

The collision mesh, robot geometry, native joint limits, finite motor caps, contact assistance and attitude assistance retain the qualified v9 physical contract. Travel still comes from the articulated legs in Sable. There is no root translation actuator, travel teleport or leaping behavior.

## Optimization and selection

The new timing search completed 96 native Sable episodes: eight candidates, three generations, four terrain tasks. Only stride, period and stance duty were mutated; the other 17 parameters stayed at their v9 values. The score includes task completion, elapsed ticks and backward travel. This is cross-entropy optimization of a parameterized controller, with engineered sensing, trajectory generation and route planning; it is not end-to-end neural reinforcement learning.

The final training winner failed one of 72 broader route trials. The retained generation-one candidate 3 (zero based) passed all four of its training tasks and was selected through further development qualification. The descent-clearance correction and pending-destination fix were engineering refinements after that search, so the training and final implementation hashes are recorded separately. The wider benchmarks have influenced selection and must be treated as development/regression evidence, not untouched held-out evaluation.

Stride is 3.5714 blocks, period 1.1663 seconds and stance duty 0.53975 before pace and terrain conditioning. The longer swing gives the bounded servos time to execute the clearance trajectory. There is one continuous terrain controller across Careful, Standard and Horrific paces.

## Destination changes during an unsupported stance

Broader validation exposed an existing planner issue: a moving stance can briefly overlap the conservative water margin even though the new destination has a valid dry support area. Previously a click at that instant failed immediately. The navigator now retains the valid request while following the existing route, retries twice per second for up to ten seconds, and switches only after a new route exists. It rejects invalid destinations immediately, preserves an existing pending request when a later invalid click occurs, and cancels pending work on Stop. If planning still fails, it stops and reports the failure. Arrival is suppressed while a new request is pending.

The native pond interaction now records the pending request and checks that the robot reaches the actual new point. A rejected invalid request must preserve both the old route and the pending valid destination. This is a navigation state-handling correction, not a change to collision margins or a special pond gait.

## Evidence and reproduction

The final frozen implementation is `25f7b11c6a5c96462bbfe4d75ae189b036d758ae1f4e6f4e3a098756dffa50da`. Final native reports bind this source, the selected genome, four physics substeps and unchanged precise collision geometry.

| Check | Final result |
| --- | --- |
| Original routes, all three paces | 72/72 |
| Pursuit, retargeting, stopping and obstacles | 6/6 |
| Recorded wilderness journeys | 6/6 |
| Longer natural circuit | 13/13 |
| Difficult terrain development routes | 18/18, baseline 17/18 |
| Common Java tests | 144 passed |
| Native geometry switching and surface sensing | Passed |

The previously failing head-on three-block-riser course now completes in 19.9 seconds. This is the specific 48-block course with three-block risers and three-block treads, not a claim of arbitrary wall climbing.

Across the matched 72 routes, fast backward-motion events fell from **135 to 60 (55.6%)**; total backward travel fell from **405.45 to 243.09 blocks (40.0%)**. Maximum reverse speed fell from **22.61 to 10.79 blocks/s**. These are movement proxies: sampled opposite the final goal, after initial settling, outside intentional navigator back-outs, and more than three blocks from the goal. An event starts when reverse speed exceeds four blocks/s. They do not directly identify individual wall collisions and can include legitimate detours.

| Pace | Fast reverse events, before → after | Median paired route speed | Total journey time |
| --- | --- | --- | --- |
| Horrific | 116 → 49 | −2.8% | −6.9% |
| Standard | 15 → 6 | −4.0% | −1.7% |
| Careful | 4 → 5 | −7.8% | −0.7% |

Overall median paired speed decreased 4.9%, while aggregate journey time decreased 4.4% because some previously slow cases improved substantially. Careful is not a uniform backward-motion improvement: its accumulated backward travel rose from 23.36 to 29.87 blocks. All paces retain their route passes.

Mean arrival error was 0.517 blocks, maximum 0.842. Stop/resume drift was 1.373 blocks. Maximum anchor separation was 0.0713 blocks in the regular routes and 0.1074 in the stress suite; the unchanged failure gate is 0.12. Maximum regular-route joint-limit excursion was 0.00672 rad. The pond retarget test exercised a pending request, rejected an invalid subsequent point without replacing it, and verified arrival at the actual new destination.

Raw reports, failed checkpoints and source snapshots are retained in `artifacts/leaper-clearance/`. `checkpoint-validation.json` records broader checkpoint selection, and `pre-lifecycle-*.json` preserve the preceding combined build's 115 passes. Final aliases refer exclusively to the last frozen source. The old v9 release remains available as `release/MinecraftMachines-Leaper-Terrain-v2.jar`; v10 is `release/MinecraftMachines-Leaper-Clearance-v10.jar`, also installed as `release/MinecraftMachines-Leaper-Predator.jar`.

Source snapshots distinguish the trained implementation (`timing-training-source.zip`) from the final implementation (`qualified-source.zip`). The explicit seed file is `leaper_learning/horrific-predator-clearance-seed.json`; copy `baseline-model.json` there to reproduce the original v9 seed after installing v10. The candidate file for native evaluation is `leaper_learning/horrific-predator-candidate.json`.

Use Java 21 with `:minecraft_machines:neoforge:runGameTest`, `-PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning`, `-PleaperProfile=horrific` and `-PleaperMode=predator-clearance-tune` for the search. Qualification modes are `predator-certify`, `predator-challenge`, `predator-interaction`, `predator-wilderness`, `predator-natural-tour` and `predator-map`. Use `-PleaperScratch=true` for synthetic tests and `-PleaperNatural=true` for recorded-world tests; do not run two servers against the same game directory. `predator-baseline-certify` and `predator-baseline-challenge` explicitly disable both swing features for the preserved baseline genome. Common tests use `:minecraft_machines:common:test`.

The simulation retains a 20 Hz controller and four native physics substeps per tick. Headless tests accelerate wall-clock throughput without changing those time steps. `tools/package_leaper_clearance.py` checks complete reports, source/resource/genome provenance, actuator contracts, unit tests and native surface sensing before writing a validated model.

## Live review

The normal packaged client was tested in **Leaper Clearance Review**, a separate copy of the previous world. The review course contains four full two-block risers with eight blocks of total elevation change. Three consecutive live journeys reached their selected points:

| Journey | Approximate time to first arrival | Arrival error |
| --- | --- | --- |
| First ascent | 18 seconds | 0.50 blocks |
| Descent | 9 seconds | 0.81 blocks |
| Repeat ascent after returning | 44 seconds | 0.64 blocks |

The repeat ascent needed multiple backward movements and replans. The original backward-launch behavior is reduced across the matched benchmark, but physical wall contacts and substantial live run-to-run variation remain. The videos retain those difficulties and the full journeys; only trailing idle time is trimmed.

Watch the first ascent (local evidence: `artifacts/leaper-clearance/leaper-first-stair-review.mp4`), the HUD-free repeat ascent (local evidence: `artifacts/leaper-clearance/leaper-clearance-review.mp4`), or the descent (local evidence: `artifacts/leaper-clearance/leaper-stair-descent.mp4`). Frames are captured directly from Minecraft's rendered framebuffer and encoded with their recorded wall-clock spacing at 20 fps output; there is no simulation or playback speed-up. Raw frames, timestamps, encoding metadata and telemetry are retained. The HUD is hidden for two recordings and restored afterward; no robot/controller code is changed by the review utilities.

Local development agents execute Minecraft commands and post the actual targeting-stick server event, exercising marker selection, cooldown, raycasting, destination handling and Shift-stop. This is verification of the live server interaction path, not a claim of physical desktop mouse-click automation. The utilities live only under `artifacts/` and are not included in the release.

At the end of that recorded session, the game was left open with the Leaper stopped on the upper platform, hunting and follow camera off, and the targeting stick selected.

## Controls and limits

Use the Leaper stick to right-click a supported destination, Shift-right-click to stop, `/mm leaper pace horrific|standard|careful` to change pace, and `/mm leaper hunt` for pursuit. `/mm leaper watch` and `unwatch` control the follow camera. Inspection poses remain available.

This does not provide arbitrary wall climbing. Reach, available footholds, large body clearance, local planning bounds and native contact dynamics still matter. Feet and shin armour can still contact obstacles, and the coordinated target limiter does not eliminate physical tracking error. Difficult descents and closely spaced tall obstacles remain useful failure cases to test before adding leaps.
