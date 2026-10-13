# Careful and fast Leaper paces

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The original careful controller is preserved unchanged in `data/minecraft_machines/leaper/navigation.json`. Its verified source, resources, model, reports and working release are archived in `artifacts/leaper-careful-v1/`, with SHA-256 hashes in `manifest.json`.

The faster pace uses a separate qualified model, `fast_navigation.json`. `/mm leaper pace careful` restores the original gait; `/mm leaper pace fast` selects the faster gait. A pace change cancels the current destination and settles the existing physical robot into the new stance for five seconds. The targeting stick then uses the selected pace. Existing physical bodies are not replaced or teleported during pace changes.

## Experimental design

A matched sweep compared eight combinations of cadence and bounded actuator target speed over forward, reverse, sideways and turning trials. The careful gait had a roughly 4.3-second cycle. The selected experimental envelope multiplies gait cadence by three and increases maximum joint target speed from 0.6 to 1.8 radians per second. The native hard joint limits, 150,000-unit motor force cap, stiffness, damping, foot friction and collision geometry remain unchanged. The physical control timestep remains 0.05 seconds and the destination-controller timestep remains 0.1 seconds.

The sweep showed diminishing returns above three times the original cadence: front or rear feet started dragging even though the chassis stayed upright. Therefore the faster controller is trained within the three-times cadence envelope. The gait objective rewards greater physical progress, penalizes sliding and stopping drift, and requires repeated lift/plant cycles by every foot. Startup clearance alone cannot satisfy that requirement.

The fast gait search uses 16 generations of eight candidates, each evaluated in four physical movement scenarios. It begins from the archived careful genome and tunes all ten foot-space parameters. A separate seeded search retunes the destination controller for the faster gait; gait and navigation candidates are not loaded into player controls before qualification.

## Qualification requirements

Motor qualification covers 16 full- and half-amplitude movement cases on four flat floor materials. Full translation must exceed 1.0 blocks per second. Posture, joint-anchor separation, hard-angle bounds and body-height thresholds are retained. The minimum completed step count increases with cadence. Walking grip is bounded both per distance travelled (less than 0.6 blocks of estimated near-ground foot motion per foot per chassis travel block) and per second (less than 1.0). Stationary turning retains a 0.75-block-per-foot-second cap, since it has no translational denominator. The actual measured values are reported. A preliminary uniform 0.75-per-second threshold rejected a 2.03-block/second forward trial at 0.756 foot blocks/second, despite better foot-motion-per-distance than the careful gait; that report is retained, and the dimensionally comparable grip criterion is documented here. This geometry-based metric is not a contact-force measurement.

Destination qualification requires 48 comparison trials and 48 separate fresh trials, including close corrections, arbitrary starting headings and physical disturbances. Successful arrival requires settling and a further five-second hold within 0.48 blocks. Continuous routes and moving pace transitions exercise stopping, resuming, retargeting and changing between careful and fast. The faster pace is qualified for clear flat ground; leaping and obstacle traversal are separate work.

## Reproduce

Use Java 21. Add `-PleaperProfile=brisk -PleaperScratch=true` to the Leaper GameTest commands in [the navigation record](leaper-navigation.md). The experimental profile is named `brisk` in training files; its player-facing name is **Fast**. Reports receive a `brisk-` prefix so the careful reports remain separate. `-PleaperMode=speed-grid` without the profile flag reproduces the initial matched cadence/servo sweep. `-PleaperMode=pace-switch` tests changing both ways during movement.

## Measured results

The original careful model replayed all 48 comparison trials exactly, including the recorded physical trajectories. The packaged fast model also replayed its 48 candidate qualification trials exactly. The two independent target sets contain 96 destinations in total; both models were compared on these same targets and initial headings.

| Measurement | Careful | Fast |
|---|---:|---:|
| Sustained full-command translation | 0.42–0.51 blocks/s | 2.03–2.35 blocks/s |
| Mean time to settled arrival, 96 targets | 36.22 s | 10.68 s |
| Mean final target error | 0.148 blocks | 0.088 blocks |
| Worst final target error | 0.273 blocks | 0.157 blocks |
| Successful target trials | 96/96 | 96/96 |
| Joint target speed cap | 0.6 rad/s | 1.8 rad/s |

Fast sustained translation is 4.52–4.90 times the matching careful direction. Average destination arrival is 3.39 times faster, including braking and settling. Fast qualification covered 16 gait primitives, four continuous routes (15 accepted waypoints and a target replaced in motion), and four switches between paces during motion. Every pace-switch trial retained the native hard limits; maximum joint-anchor error was 0.00356 blocks. The continuous-route stop test drifted 0.589 blocks after its emergency stop command; emergency stopping is physical braking, not an instantaneous lock.

A separate 96-block trip with an initial heading offset and a physical push arrived in 43.65 seconds, with final error 0.058 blocks, followed by the required hold. Maximum body tilt in the 96 destination trials was 4.60 degrees. These are measured results for the tested flat-ground conditions, not an assertion about every possible disturbance or terrain.

Training comprised 512 gait episodes and 512 navigation episodes, with 128 candidates in each search. The method is seeded cross-entropy optimization of gait and navigation parameters in native Sable physics. It is not neural-network training. Full reports, rejected experiments, training logs and comparison figures are in `artifacts/leaper-brisk/`.

- [Measured speed comparison](evidence/leaper-brisk/pace-comparison.png)
- Native Minecraft video of Fast pace (local evidence: `artifacts/leaper-brisk/leaper-fast.mp4`): 35 seconds at normal game speed, showing a 39.9-block journey, braking and holding. The trip arrived in approximately 20 seconds; the later status check measured 0.086 blocks of remaining error. The camera follows the robot from the front quarter.
- [Arrival time and accuracy comparison](evidence/leaper-brisk/destination-comparison.png)
- [Verification record](evidence/leaper-brisk/verification.json)
- Combined development release with both paces (local output: `release/MinecraftMachines-Leaper-Paces.jar`; [build current source](../README.md#build-and-play))
- [Preserved careful release and source manifest](evidence/leaper-careful-v1/manifest.json)

The live development client also verified actual right-click targeting with the Leaper stick: a 9.6-block approach arrived within 0.059 blocks of the raycast point. Both pace-selection commands loaded successfully, including a change from Careful to Fast while navigating. The initial Fast lab startup and targeting tests ran at 20 game ticks per second.

The first sustained-speed fixture was too small: at around two blocks per second, robots could walk off its 76-block-radius platform. Qualification was repeated on isolated 128-block-radius platforms, spaced 288 blocks apart. The rejected small-platform report is retained.
