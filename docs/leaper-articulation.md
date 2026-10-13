# Original Leaper articulation and flat-ground record

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The original, reference-based reconstruction of the ARC Raiders Leaper was developed for Minecraft 1.21.1 / NeoForge / Sable 2.0.0. This is a separate morphology from the original plank quadruped. This record describes its early trained gait and point-navigation controller for clear flat ground, before ballistic leaping and the current Apex gameplay controller.

The main paces are **Standard** (the preserved Fast gait) and a separately trained **Horrific** sprint. Standard covers 2.03–2.35 blocks per second at full stride; Horrific covers 4.05–6.02 depending on direction. Each passed 96/96 flat-ground destination trials. Horrific allows more body motion and stopping drift. See [the sprint engineering record](leaper-horrific.md). The original Careful gait remains available through its legacy command and archived checkpoint.

The articulation follows the supplied stills and motion references: a low spherical sensor core, four radial rising upper legs, high outward knees, long tapered lower legs, small rounded feet, angular dark armor, red underside panels, bearing caps, actuator rods, and a concentric red eye. The source meshes were authored for this project; no game assets were extracted.

## Articulation

There are 13 independent Sable rigid bodies and 12 force-limited native motor joints. Each leg has a vertical hip swivel, a shoulder bending axis tangent to the leg's radial plane, and a parallel knee bending axis. This three-axis arrangement is inferred from the reference motion; the original game's mechanical rig is not available.

All six chassis degrees of freedom remain free. Holding and pose demonstrations use motor torques, contact, and gravity. Body placement occurs only at assembly. The model has native hard joint limits plus command speed limits, and exposes measured joint angles, body motion, and toe positions to training and qualification.

| Property | Value |
|---|---:|
| Core nominal diameter | 2.10 blocks |
| Upper joint-to-joint length | 4.00 blocks |
| Lower knee-to-toe centre length | 4.50 blocks |
| Nominal core height above ground | 2.25 blocks |
| Upper elevation in bind pose | 27° |
| Lower downward angle in bind pose | 80° |
| Front leg headings | ±55° from forward |
| Rear leg headings | ±125° from forward |
| Swivel hard range relative to bind | ±0.60 radians |
| Shoulder hard range | ±0.65 radians |
| Knee hard range | ±0.85 radians |
| Maximum target slew | Careful: 0.60; Standard: 1.80; Horrific: 3.50 radians/second |
| Motor stiffness / damping | Standard: 100,000 / 8,000; Horrific: 200,000 / 11,313.71 Sable units |
| Maximum motor torque | Standard: 150,000; Horrific: 300,000 Sable units |

The current Minecraft-style skin uses only cuboids: stepped core armour, a pixel-shaped eye ring, rectangular leg plates, square bearing housings, and compact foot pads. It uses native Minecraft block textures at 16 pixels per block. The visual surface uses the exterior of an exact cuboid union, split at block boundaries. Internal faces and overlapping coplanar surfaces are removed before export to prevent z-fighting. An independent check of all 960 exported faces found zero coplanar area overlaps within a rigid body. Collision shapes are simpler solid proxies clipped to the same block cells. Decorative rods and plate edges do not each get a separate collider. Native OBJ loading follows [NeoForge's model system](https://docs.neoforged.net/docs/1.21.1/resources/client/models/).

## Inspect in Minecraft

Open `tools/Run Leaper Lab.command`. The Leaper lab world spawns the rig in Standard pace, gives its targeting stick, and opens a free camera in front of it. The startup chat includes clickable Standard and Horrific controls.

- `/mm leaper pace standard` — select the saved Fast gait, now the default. `fast` remains an alias.
- `/mm leaper pace horrific` — select the sprint gait. Pace changes cancel the current destination and allow five seconds to settle.
- `/mm leaper pace careful` — select the original archived careful gait.
- `/mm leaper stick` — select the Leaper targeting stick. Right-click clear ground to walk there; Shift-right-click to stop.
- `/mm leaper goto <x> <z>` — select a destination directly.
- `/mm leaper stop` — stop walking.
- `/mm leaper articulate` — slow repeating shoulder, knee, and swivel movement.
- `/mm leaper crouch` — lower the body.
- `/mm leaper stand` — return to the reference stance.
- `/mm leaper unwatch` — release the camera for free inspection.
- `/mm leaper watch` — follow the front view.
- `/mm leaper status` — measured body tilt and joint connection error.
- `/mm leaper remove` and `/mm leaper spawn` — replace the rig on clear flat ground.

The Leaper stick and original quadruped stick control their respective robots. See [the navigation engineering record](leaper-navigation.md) for training, grip experiments, qualification, and reproduction commands.

## Visual review

- Native Minecraft Horrific sprint video (local evidence: `artifacts/leaper-horrific/leaper-horrific.mp4`): normal game speed, showing an approximately 80-block approach, braking and holding.
- Native Minecraft Fast pace video (local evidence: `artifacts/leaper-brisk/leaper-fast.mp4`): 35 seconds at normal game speed, showing a roughly 40-block approach, braking and holding.
- Native Minecraft navigation video (local evidence: `artifacts/leaper-navigation/leaper-navigation.mp4`): 32 seconds at normal game speed, showing approach, braking and holding at the selected point.
- Native Minecraft articulation video (local evidence: `artifacts/leaper/leaper-articulation.mp4`): 24 seconds, sampled at 10 fps; encoded playback repeats frames without interpolation.
- Front quarter view (local evidence: `artifacts/leaper/leaper-front.png`)
- Flexed pose (local evidence: `artifacts/leaper/leaper-flexed.png`)
- Side view (local evidence: `artifacts/leaper/leaper-side.png`)
- Trained navigation release (local output: `release/MinecraftMachines-Leaper-Navigation.jar`; [build current source](../README.md#build-and-play))
- [Measured navigation results](evidence/leaper-navigation/navigation-results.png)

## Measured physics verification

Horrific passed 16 motor trials, 96 destination trials, four continuous routes, four moving pace transitions and a 96-block trip with an initial heading offset and a push. Mean destination error was 0.211 blocks, maximum 0.442. The long trip settled in 26.25 seconds versus Standard's 43.65. An emergency stop issued at 5.49 blocks/second took 3.38 blocks; allow room for physical braking. Native angle bounds were never exceeded in the motor qualification. Standard replayed all 48 archived comparison trials identically after the sprint code changes. [Full sprint evidence](leaper-horrific.md).

With the original careful gait and destination controller, the packaged model passed 96 point trials across two target sets, with mean final error about 0.15 blocks and worst error 0.27 blocks. Four continuous routes also passed, including emergency stopping, resuming, and changing targets in motion. The 16 gait primitive trials checked both signs, sideways and diagonal motion, half-speed commands, repeated foot lift/plant cycles, posture, and native joint limits. The separately trained fast model passed the same qualification families; its results are recorded in [the fast-pace guide](leaper-fast.md).

The new pad-friction setting also passed a fresh 80-second articulation regression: maximum tilt 2.43°, maximum anchor separation 0.00121 blocks, and worst settled target error 0.01569 radians. That report is in `artifacts/leaper-navigation/articulation-regression.json`. The following measurements document the earlier articulation baseline before the grip change.

The 80-second Sable GameTest covered initial standing, crouching, 50 seconds of continuous articulation, and return to standing. It passed with maximum body tilt **1.742°**, maximum joint-anchor separation **0.00106 blocks**, and worst settled joint-target error **0.02833 radians (1.623°)**. The final displacement from the initial core location was 0.337 blocks, including vertical settling; this demonstration is not a station-keeping controller. Detailed samples are saved in `artifacts/leaper/physics.json`.

The initial lower-stiffness configuration failed the joint-tracking threshold because the shoulders sagged under load. Its measurements are retained in `artifacts/leaper/initial-shoulder-sag.json`. The accepted configuration increases stiffness and damping while retaining the same torque cap.

## Reproducibility

`tools/generate_leaper_geometry.py` generates the current cuboid OBJ/MTL meshes from the preserved physical contract in `geometry.json`. It validates that every face stays in an existing assembly cell and that physical geometry remains byte-for-byte unchanged. The previous smooth model and generator are archived in `artifacts/leaper/smooth-prototype/`. `MinecraftMachinesLeaper` creates the physical rig; `MinecraftMachinesLeaperManager` runs the qualified point controller and retains the inspection poses. The dedicated Leaper GameTest exercises standing, crouching, repeated articulation, and return to standing.

Full-range joint limits are engineering bounds, not a claim that every combination is collision-free or suitable for walking. The gait search stays in a bounded foot-space neighbourhood and the deployed model checks its morphology, collision geometry, policy versions, and native foot friction before loading. No leap behavior has been implemented or qualified.
