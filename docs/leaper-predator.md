# Leaper predator navigation

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

This is the historical v8 engineering record. The subsequent precise-collision revision is documented in [Leaper terrain v2](leaper-terrain-v2.md); current gameplay is described in the [Leaper overview](leaper.md).

This replaces automatic crawl/climb switching with one continuous, terrain-aware gait. The robot searches for a route to the selected surface, places its feet on collision geometry, and uses the same controller at Careful, Standard and Horrific paces. The approved blocky model and earlier flat-ground checkpoints are preserved.

## Controls

- `/mm leaper spawn` creates the Leaper on a clear starting area.
- `/mm leaper stick` gives the targeting stick. Right-click the top of a block to set a destination; Shift-right-click stops.
- `/mm leaper hunt` pursues the player who issued the command. It updates the target as that player moves, including changes in elevation. This is movement pursuit; combat damage and attacks are not implemented by this release.
- `/mm leaper pace horrific`, `standard`, or `careful` changes the requested pace without resetting the gait or its destination.
- `/mm leaper stop` cancels pursuit and holds position.
- `/mm leaper watch` follows the robot with the camera; `/mm leaper unwatch` returns control. Hunt cancels the follow camera.
- `/mm leaper terrain auto` enables this controller. `terrain flat` restores the preserved flat-ground controller.
- `/mm leaper remove` removes the rig and its physics constraints.

## What is different

The route graph contains separate elevation layers. A tunnel floor and the roof above it are different states; the robot cannot plan a transition by pretending it already stands on the roof. Search uses a two-block horizontal grid and connects the exact clicked surface to that graph. It considers the full stance, torso and leg clearance, and penalizes missing footholds and proximity to deep holes. Smoothing preserves the cost of the safer route instead of cutting back across an edge. Trees, low branches, ponds and cliffs can cause detours. The current bounds allow individual rises up to two blocks and drops up to 3.5 blocks; these are planning bounds, not a guarantee for every arrangement of those obstacles.

The foot controller estimates the local slope from collision samples. It adjusts body attitude, foot elevation and swing clearance continuously. At each swing it searches 25 nearby landing candidates, checks the physical joint envelope, and sweeps the path for intervening obstacles. Pace changes stride and cadence continuously; rough ground does not automatically select Careful mode.

Stance feet receive finite horizontal contact springs while their tips are within 0.32 blocks of a dry surface. The springs release for swing and when contact is lost. Vertical motion remains free. This supplies gameplay grip, not an invisible floor or permanent weld. It was added because sliding contacts were preventing otherwise reachable ascents.

There are still 13 physical bodies and 12 limited leg joints. Predator joint limits are ±0.9 rad yaw, ±1.05 shoulder and ±1.2 knee. Motor forces and slew rates are finite. A native attitude spring supplies pitch/roll assistance; translation and yaw are free. This is explicitly a game-assisted physical creature, not a demonstration of unassisted real-world robotic balance. Travel comes from the articulated body and ground contacts; navigation does not teleport or prescribe the root's linear velocity.

Control runs at 20 Hz with four native physics substeps (80 Hz). The substep setting is held while predator rigs are active and restored when the last one is removed. Minecraft time is not accelerated in the playable game.

## Learning and evidence

Learning uses seeded cross-entropy optimization over 20 bounded gait and actuator parameters in the actual Sable engine. Fitness rewards reaching destinations, progress and reliable stance, and penalizes structural failures and poor tracking. Selection combines average and worst-course scores. An incumbent is re-evaluated alongside new candidates rather than retaining an old lucky score indefinitely.

The search, perception, inverse kinematics, route following, terminal braking and contact mechanics form a hybrid controller. This is not PPO, a neural policy, end-to-end reinforcement learning, or a claim of new mathematics. The work on anticipating terrain before contact is consistent with the motivation in [Miki et al., *Learning robust perceptive locomotion for quadrupedal robots in the wild*](https://arxiv.org/abs/2201.08117); this implementation does not reproduce that paper's neural architecture or its reported results.

Raw successes and failures are retained in `artifacts/leaper-predator/`. Release promotion checks that the selected genome, source fingerprint, physics settings and approved geometry agree with the native reports. Results from earlier controllers remain historical evidence and do not qualify a later implementation.

The qualified source fingerprint is `2f9228b89c26792ee3449699db9d741f8634a6fbc5997f0434a2d564acbb9350`. The final checks passed:

| Check | Result |
| --- | --- |
| Terrain routes, four Horrific approach orientations plus Standard and Careful | 72/72 |
| Wilderness downhill/uphill return, two journeys per pace | 6/6 |
| Longer natural dune circuit, including nine-block ascents | 13/13 |
| Moving prey, retargeting, pace changes, stop/resume and a new obstacle | 6/6 |
| Common Java tests | 127 passed |
| Native collision-surface and terrain-layer checks | Passed |

The 72-route mean destination error was **0.513 blocks**, maximum **0.875**. Mean displacement divided by journey time was **2.43 blocks/s Horrific**, **2.26 Standard**, and **1.49 Careful**; these include turns, detours and settling, and are not peak sprint speeds. Maximum structural anchor error was **0.031 blocks**. The largest transient excursion beyond a joint limit was **0.067 rad (3.85°)**. Targets and motor forces remain bounded; the physics solver is not mathematically rigid. The stop/resume test measured **1.49 blocks of drift** during its stop interval, so stop should not be interpreted as an instantaneous freeze.

The project retained 1,416 completed optimization episodes across the experiments, including 256 with the final contact mechanics. The selected contact-search incumbent passed all eight tasks in generation 0. Across its two training evaluations it passed 15/16: the other trial timed out during final settling, 0.458 blocks from its goal, without a structural failure. The final navigation controller, with its own terminal braking, passed the separate suites above. Later search winners were archived instead of replacing a checkpoint already qualified across the wider terrain set.

The review video contains two continuous live-game journeys at normal speed, joined at the turnaround: a stick-selected downhill destination and the uphill return. The live game stopped approximately **0.37** and **0.30 blocks** from those targets. Raw recordings and telemetry are preserved alongside the edited review clip.

## Practical limits

The Leaper has a large physical footprint. A point that supports a player may not support all its legs or provide room for the body. Dense trunks, narrow ledges and low tunnels can be inaccessible. Hunt searches nearby approach points when the player's exact location cannot support the robot.

Planning uses loaded chunks within a bounded local horizon, currently 192 blocks; pursuit advances toward more distant targets in stages. The creature does not yet leap, swim, climb sheer walls, break trees, or excavate terrain. A cliff or pond requires a traversable way around it. Navigation is evaluated on concrete terrain and moving-target cases; it is not guaranteed across every Minecraft seed or arbitrary player construction.
