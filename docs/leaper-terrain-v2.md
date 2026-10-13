# Leaper terrain navigation, precise-collision revision

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

This revision improves traversal before adding leaps. It preserves the approved blocky render model and the earlier flat-ground policies. Predator mode now uses collision geometry derived from the actual visible armour and foot pads, a newly optimized stance and gait, and more accurate foothold and route sensing.

## What changed

The old collision boxes were simplified proxies. Some extended beyond the narrow lower-leg and foot geometry. `tools/generate_leaper_geometry.py` now exports a second collision model from the same authored cuboids that generate the render meshes. The solids are clipped to Sable's cells, unioned, and merged into disjoint boxes. There are no duplicate overlapping volumes within a cell. An independent OBJ occupancy check compared 21,240 regions of the coordinate partition with zero mismatches at 0.000001-block precision. This checks model-to-collider agreement; Sable's numerical contacts and solver tolerances still apply.

The precise shapes are enabled for predator navigation and inspection. Selecting the explicit legacy flat controller restores its original shapes, so its preserved checkpoints retain their original physical environment. Visible OBJ assets and the legacy geometry/navigation resources remain byte-for-byte unchanged.

The terrain controller samples the actual half-block-wide foot pad. It sweeps the corridor to the **selected** landing point, including a lateral foothold adjustment. Previously it could check the nominal point even after selecting a different foothold. Collision sampling now distinguishes the upper and lower halves of Minecraft stairs, and leaves are excluded as foot support.

The planner accounts for the learned body height and stance envelope, separate floor/roof layers, and steep uphill travel costs. Its maximum individual planned rise is now three blocks. It can preserve a gentler approach instead of smoothing the path back into a steep head-on ascent. Arrival checks use the support plane under the robot's stance rather than assuming the body must sit a fixed distance above the single clicked block. Hunt also notices changes in the target's elevation.

The selected gait raises nominal body clearance from 2.774 to 3.317 blocks, brings the stance radius from 5.048 to 4.603 blocks, and shortens the base cycle from 1.067 to 0.947 seconds. It spends 61.6% of each cycle in stance, versus 69.4% previously. Careful pace keeps a useful step span while reducing cadence, which helps it step over tall obstacles. All paces use the same terrain controller and continuous gait. The leg lengths remain four and 4.5 blocks; arbitrary lengthening was not necessary to obtain the measured improvement.

There are still 13 rigid bodies and 12 bounded native leg joints. Motor power increased by approximately one percent, to a finite maximum of 959,476 in the existing Sable motor units, with a commanded slew limit of 5.922 radians/second. Joint limits are ±0.9 rad yaw, ±1.05 shoulder and ±1.2 knee. Travel remains physical. Finite horizontal contact springs provide gameplay grip; the foot's vertical axis is free. Finite native pitch/roll assistance remains enabled, with body translation and yaw free. This is an assisted game creature, not an unassisted real-world robotics result.

## Optimization and verification

A new native Sable cross-entropy search ran 256 episodes: eight candidates, eight terrain tasks and four generations. It searched 20 bounded gait and actuator parameters, ranking candidates by both mean and worst-course performance. Generation 3, candidate 3 reached all eight training destinations. Its genome and all search results are retained in `artifacts/leaper-terrain-v2/`.

Sensing, pathfinding, inverse kinematics, recovery and braking are engineered components around that optimized gait. This is not neural reinforcement learning or a claim of new mathematics. Pace conditioning and the gameplay lifecycle were refined after optimization, then tested with the exact final implementation. The training-source fingerprint and final qualification fingerprint are recorded separately.

The development stress benchmark contains 18 routes: three-block staircases in both directions, three-block rubble and ridges, cross-slopes, and tightly spaced broken ground, with different approaches and paces. The original build passed **6/18**; the new build passed **17/18**. The remaining failure is the head-on Horrific ascent of a staircase with three-block risers and three-block treads. It times out; this result is retained in the release evidence. This benchmark was used during development, so it is not described as an unseen test set.

The original 72 terrain routes and recorded natural-world fixtures are also rerun as regression checks. Release promotion requires every original route, interaction, wilderness journey and dune-circuit leg to pass with the same genome, final source fingerprint, collision hash and actuator settings. Test successes from an earlier implementation cannot qualify a later one.

Several plausible changes were rejected after testing: world-space swing interpolation, longer rough-terrain strides, more aggressive torso pitch, constant-speed travel through intermediate waypoints, stronger recovery penalties, and extra shoulder collision exclusions. The last experiment reduced stress-route success from 17 to 13. Those changes and their failed reports remain archived rather than being included merely because their rationale sounded convincing.

## Final qualification

The frozen implementation is `a3abdad26404b5b20fbae9cbedbee946adca512d752dd39d2af7f52b77804167`. Every final native report binds this source, the selected genome and precise-collision SHA `4101a5c756ae9b9ffdbab0f3fd5dadcd179c4c77ab25314ec69e4cf77788cd13`.

| Check | Final result |
| --- | --- |
| Original terrain routes, all three paces | 72/72 |
| Recorded wilderness journeys | 6/6 |
| Longer natural dune circuit | 13/13 |
| Pursuit, retargeting, stopping and obstacle interactions | 6/6 |
| Added difficult terrain routes | 17/18, baseline 6/18 |
| Common Java tests | 132 passed |
| Native shape switching and surface sensing | Passed |
| Independent render/collision partition comparison | 21,240 samples, zero mismatches |

| Pace | Median paired route speed change | Total journey time change | Mean journey speed |
| --- | --- | --- | --- |
| Horrific | +26.6% | −10.7% | 3.01 blocks/s |
| Standard | +12.3% | −13.0% | 2.55 blocks/s |
| Careful | −4.2% | +2.7% | 1.48 blocks/s |

These compare the same 72 routes against the preserved v8 reports. Journey time excludes initial settling and the final two-second arrival hold, and includes turns, detours and braking. Median paired improvements and aggregate time improvements measure different things; neither is a claim of a uniform speed increase on every route.

Mean destination error was 0.517 blocks, maximum 0.838. The stop/resume interval drifted 1.308 blocks. Maximum joint-limit excursion was 0.0068 rad (0.39°). The largest temporary structural-anchor separation was 0.120 blocks, close to the unchanged 0.12-block failure threshold and higher than the old build's 0.031. That is a real tradeoff in this more capable terrain gait, not a claim that physical constraint error has disappeared.

The release model and raw results are in `artifacts/leaper-terrain-v2/`; the older build, candidate and source snapshot are retained there for rollback. Native reports are reproducible development evidence, not a guarantee for arbitrary player-built worlds.

## Play and reproduce

Open **Leaper Predator Wilderness** and use:

- `/mm leaper spawn`
- `/mm leaper stick`; right-click a supported surface to travel there, Shift-right-click to stop.
- `/mm leaper pace horrific`, `standard` or `careful`; pace changes retain the current destination.
- `/mm leaper hunt` to pursue you; `/mm leaper stop` cancels pursuit.
- `/mm leaper watch` and `/mm leaper unwatch` for the follow camera.
- `/mm leaper terrain flat` explicitly restores the historical flat-ground controller; `terrain auto` returns to the precise terrain controller.

Spawning immediately selects the precise terrain controller. Inspection poses release the contact and attitude assistance while retaining the precise shapes; selecting a new destination restores navigation.

Run `tools/verify_leaper_collision.py` with NumPy installed. Java tests use Java 21. Native suites use `:minecraft_machines:neoforge:runGameTest`, `-PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning`, `-PleaperProfile=horrific` and the corresponding `-PleaperMode`: `predator-certify`, `predator-challenge`, `predator-interaction`, `predator-map`, `predator-wilderness`, or `predator-natural-tour`. Use `-PleaperScratch=true` for synthetic suites and `-PleaperNatural=true` for the two recorded-world suites. Copy the selected candidate to the chosen run directory's `leaper_learning/horrific-predator-candidate.json`. `tools/package_leaper_terrain_v2.py` checks the evidence before writing the release model.

The playable simulation runs at normal Minecraft speed, with a 20 Hz controller and four native physics substeps per tick. Headless tests run faster in wall time while retaining the same simulation time step.

## Live game review

Watch the actual Horrific uphill journey (local evidence: `artifacts/leaper-terrain-v2/leaper-terrain-review.mp4`) or the Careful dune journey (local evidence: `artifacts/leaper-terrain-v2/leaper-careful-terrain.mp4`). The Horrific route covers 50 horizontal blocks and nine blocks of net ascent, reaching the goal in approximately 14 seconds and subsequently settling within 0.04 blocks. The earlier platform downhill/return checks settled at approximately 0.56 and 0.06 blocks respectively.

The desktop automation tool could not attach to the Java window. A temporary local development agent therefore invoked Minecraft commands and posted actual targeting-stick server events, exercising the item marker, cooldown, raycast, destination planning and stop hook. This verifies that live server interaction path; it is not represented as a physical mouse-click test. Inspection-to-navigation, all three pace commands, stop/resume and final stick selection were checked in the running packaged client. Review utilities are confined to `artifacts/leaper-terrain-v2/live-tools`; the release does not include them. No controller classes were changed by the review utilities.

Frames come directly from Minecraft's actual rendered framebuffer. The reviewed ascent captured approximately 12.5 distinct frames per second, and the encoded video preserves their original wall-clock timing. Output frames are duplicated where needed for 20 fps playback; the simulation is not sped up. The raw frames, timestamps, chat events, telemetry and video checksum remain in the evidence directory.

The live game is left with the creature stopped near the starting platform, hunting and follow camera disabled, and the targeting stick selected. Right-click a supported destination to continue testing.

## Remaining limits

The large stance needs room around trees, ledges and low ceilings. Planning uses loaded chunks within a bounded local horizon. The robot does not yet leap, swim, climb sheer walls, break obstacles or guarantee access to every place a player can reach. Three-block traversal depends on obstacle spacing and approach; the recorded failed steep staircase is a concrete example. Careful prioritizes usable obstacle clearance rather than being faster on every original course.
