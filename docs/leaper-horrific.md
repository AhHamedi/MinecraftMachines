# Leaper Horrific sprint

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The previously qualified Fast gait is now the **Standard** pace. Its model is unchanged; `artifacts/leaper-horrific/baseline.json` records the preserved model and release hashes. The earlier Careful gait remains archived and accessible through its legacy command. The lab presents Standard and Horrific controls.

## Engineering approach

The first matched sweep tested the standard gait at higher cadence, target speed and motor power. Faster leg cycles often reduced forward progress and left pairs of feet dragging. A second sweep tested longer strides and a shorter support phase. This produced substantially more useful forward travel, with double motor power improving the promising operating point. Quadruple power caused large body excursions or joint-anchor separation in several cases and was rejected.

Horrific therefore uses a separate, versioned foot-space policy. Its search allows 1.8–5.0-block strides, 0.4–1.5-block lift, 0.40–0.75 stance duty, and a separately bounded cycle period. Physical inverse kinematics and native joint-angle limits are shared with Standard. The initial search envelope uses cadence 0.8 applied to the sprint policy's 0.55–1.5-second period range, joint target speed capped at 3.5 radians/second, and motor power multiplier 2. The power multiplier scales stiffness and the finite torque cap linearly, and damping by its square root. It does not apply forces to the chassis.

The initial settings are evaluated and optimized in native Sable physics. The world timestep remains unchanged: motor targets update every 0.05 seconds and destination feedback every 0.1 seconds. Standard continues to use its original policy, parameters, 1.8-radian/second target cap and original motor power.

## Qualification contract

The sprint search prioritizes physical progress while retaining repeated foot lift/plant cycles and penalties for slipping, path inefficiency, tilt and stopping drift. It trains forward, reverse, sideways and turning motion. Destination training uses separate cases from qualification.

Sprint qualification allows more body motion than Standard: upright dot product must remain above 0.90 (about 26 degrees), measured height variation below 1.5 blocks, and periods with all feet clear of the ground below 40 percent of the measured interval. Native joint limits remain unchanged, with at most 0.04 radians measured violation and at most 0.04 blocks of anchor separation.

Sixteen motor trials cover full and half commands, forward/reverse/sideways/diagonal motion and both turn directions across four flat floor materials. Full translation must exceed 3.5 blocks/second. Estimated near-ground foot motion must remain below 0.7 blocks per foot per chassis travel block and 3.5 blocks per foot per second; stationary turning uses a 2.5-per-second bound. These geometry-based measurements are not contact-force sensors. Repeated foot plant counts must cover at least half the expected cycles in full-command tests.

Destination qualification requires 48 comparison and 48 fresh trials, including arbitrary initial headings, close corrections and pushes. Arrival includes settling plus five additional seconds within 0.48 blocks. Route tests exercise stopping, resuming and retargeting. Moving pace transitions must preserve physical bodies and finish without falling or breaking joint bounds. Emergency stop and transition drift may reach four blocks at sprint speed; stopping is physical braking rather than a lock.

## Controls

- `/mm leaper pace standard` — the saved Fast gait, now the default. The `fast` alias remains available.
- `/mm leaper pace horrific` — the separately qualified sprint model.
- Right-click clear ground with the Leaper stick to select a destination; Shift-right-click to stop.

Pace changes cancel the current destination and allow five seconds for the existing robot to settle into its new stance. No leap or obstacle traversal is included in this flat-ground sprint task.

## Reproduction and evidence

Use the existing Leaper GameTest commands with `-PleaperProfile=horrific`. `speed-grid` reproduces the first cadence/power comparison, and `sprint-grid` the longer-stride comparison. Training and qualification reports are prefixed `horrific-`, keeping Standard's reports separate. All raw and final records for this work are saved under `artifacts/leaper-horrific/`.

The first gait search completed 11 generations of eight candidates in four scenarios (352 completed episodes). It was stopped to address the diagonal traction boundary exposed by independent motor qualification. The incomplete next generation is excluded from that count. Raw search results and the pre-envelope source are preserved.

A separate 16-case diagonal-amplitude sweep found that approximately 80 percent of the full diagonal command produced more useful motion than a full diagonal sweep. The sprint policy therefore limits translation to `1 − 0.2·mixed`, where `mixed = 2|forward·strafe|/(forward² + strafe²)`. This preserves full straight-line commands and smoothly reduces the maximum diagonal command to 0.8. Simultaneous translation and turning are also scaled when any leg would request more than one unit of horizontal foot sweep. Low commands retain their original magnitude. These limits are controller engineering based on the measured operating range, not learned parameters or novel mathematics.

The standard model replayed all 48 archived comparison trials identically after the sprint code was introduced.

## Motor results

The final sprint gait passed all 16 full/half-command motor trials. It recorded zero hard-angle violations and maximum joint-anchor error of 0.01305 blocks. Maximum tilt across these trials was 11.52 degrees. Body motion is deliberately less restrained than Standard.

| Full-command direction | Standard | Horrific |
|---|---:|---:|
| Forward | 2.03 blocks/s | 6.02 blocks/s |
| Reverse | 2.07 blocks/s | 5.06 blocks/s |
| Right | 2.32 blocks/s | 6.00 blocks/s |
| Left | 2.35 blocks/s | 5.98 blocks/s |
| Diagonal right | 2.15 blocks/s | 4.22 blocks/s |
| Diagonal left | 2.14 blocks/s | 4.05 blocks/s |

These are physical sustained-speed measurements, excluding startup. Sprint speed is 1.9–3.0 times the matching Standard direction. Short destination journeys also include turning, braking and settling, so their total duration should not be inferred from this speed ratio.

- [Measured speed comparison](evidence/leaper-horrific/pace-comparison.png)
- [Verification record](evidence/leaper-horrific/verification.json)

## Destination and live results

The final navigation search completed 16 generations × eight candidates × four tasks, or 512 episodes. Together with the 352 completed gait episodes, this is 864 completed training episodes, excluding parameter sweeps and qualification. Training used seeded cross-entropy parameter optimization in native Sable physics, not a neural-network policy.

The deployed model passed **96/96 destinations**, including 48 fresh trials loaded from the packaged resource. Mean final error was **0.211 blocks**, maximum **0.442**, and mean settled arrival time **9.48 seconds** (Standard: 10.68). Turning, close corrections and braking reduce the travel-time advantage on short journeys. Standard's mean error was 0.088 blocks, so the faster mode trades some endpoint precision for speed.

All four continuous routes passed, including retargeting at 6.18 blocks/second and emergency stopping at 5.49 blocks/second. The latter drifted 3.38 blocks before stopping. All four moving pace transitions passed with zero hard-limit violations. A separate 96-block journey with an initial heading offset and a physical push settled in 26.25 seconds, versus Standard's 43.65, with 0.249-block final error. These tests cover clear flat ground; they do not establish obstacle or leap capability.

The combined release built successfully and all 111 common unit tests passed. Its three model resources and physical geometry match the source files. The qualified Standard model and original Careful model remain byte-for-byte unchanged. The final release SHA-256 is `bd3b1f78726415c6b77f201b633626cc021935cf9c45c2ee3992027079dd730c`.

Native Minecraft review confirmed the actual targeting-stick raycast, a 9.6-block arrival at 0.34-block error, an approximately 80-block sprint settling at 0.25-block error, Shift-right-click stopping during motion, and both pace changes. The game ran at 20 ticks/second. The 40-second window recording includes the sprint, braking and hold. It was encoded at 30 fps without changing playback speed or synthesizing frames; five frames were visually inspected and the full MP4 decoded without errors.

- Native sprint video (local evidence: `artifacts/leaper-horrific/leaper-horrific.mp4`)
- [Destination comparison](evidence/leaper-horrific/destination-comparison.png)
- Combined Standard / Horrific release (local output: `release/MinecraftMachines-Leaper-Horrific.jar`; [build current source](../README.md#build-and-play))
- [Video provenance](evidence/leaper-horrific/video-provenance.json)
- Final source and resources (local output: `artifacts/leaper-horrific/source-and-resources.zip`; [build current source](../README.md#build-and-play))

The archived reports distinguish rejected experiments, the early navigation checkpoint, the final comparison and fresh qualification. The manifest in `artifacts/leaper-horrific/` records final artifact hashes.
