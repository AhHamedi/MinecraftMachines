# Leaper awareness and behaviour

Apex is always used for gameplay, including patrol, investigation and recovery. The server owns three states. Newly spawned Leapers automatically patrol the area around their spawn point; existing manual destination, pose and stop controls remain available.

| State | Eyes | Movement and purpose |
| --- | --- | --- |
| Patrolling | Blue | Careful gait, supported local destinations, brief 0.8–1.6 second pauses between destinations; default patrol radius 24 blocks. |
| Alert | Orange | Investigate a sensory clue or the last seen position, then search nearby supported points at Standard pace. |
| Hunting | Red | Existing predictive pursuit and physical traversal, using the selected hunt pace. |

## Sensing

Perception runs every four server ticks, after an initial four-second spawn settling period. Natural acquisition considers alive Survival/Adventure players in the same dimension. Creative and Spectator players do not trigger it. The explicit `/mm leaper hunt` command can select its Creative-mode operator for testing; Spectator players remain excluded.

**Sight** comes from the physical front optical disk, including its current rotation and tilt. Both the player's eyes and torso are tested against Minecraft visual voxel shapes; the Leaper's own simulated bodies are excluded. A wall or cave roof blocks vision. The main field of view is approximately 139 degrees. Close peripheral awareness within three blocks still requires a clear ray.

Initial detection depends on distance, local block/sky light, crouching and how much of the player is visible. Full-light sight reaches up to 38 blocks, darkness up to 14, and crouching reduces that range. Invisibility prevents visual detection. A glimpse turns the eyes orange; sustained visual evidence confirms a hunt. Strong close-range evidence requires approximately 0.6 seconds; weak distant evidence takes longer. Brief gaps decay confirmation gradually rather than erasing it immediately. Hearing alone cannot confirm a hunt. Once Alert or Hunting has identified a suspect, visual attention can follow that same player out to 192 blocks through a broad horizontal tracking cone, including high flight arcs. This still requires an unobstructed, fully loaded eye/torso ray and rejects invisibility. It cannot acquire an unknown distant player or see through a cave roof.

**Hearing** samples actual grounded movement, with a maximum straight-line range of eight blocks for walking and sixteen for sprinting. Sneaking, standing still, airborne movement and large coordinate jumps do not create footsteps. Sound is received near the feet, so the high optical disk does not make level-ground footsteps appear to come from another floor. It must have a short path through connected, loaded, collision-free air cells, with at most three blocks of vertical separation. A sealed underground cave cannot alert a Leaper on the surface. Nearby footsteps around a corner can. This is a deliberately conservative footstep model: mining, projectiles and arbitrary modded sound events are not currently stimuli. Slabs, closed doors and fluids block acoustic cells.

Sounds produce a coarse four-block horizontal clue rather than the player's exact coordinates. Sensing checks at most eight nearby candidates and at most 256 acoustic search nodes per candidate. It never loads terrain to look for a player. Both sight and the selected sound route also test moving Sable geometry; a moving wall blocks them. A blocked acoustic route is discarded conservatively instead of launching another detour search.

## Memory, giving up and returning

The hunter receives live coordinates only from visible evidence of its identified target. Terrain destinations are resolved below observed flight positions, with a bounded downward scan that handles players more than 24 blocks above the ground. Once visibility is lost, it immediately drops velocity prediction and approaches the frozen last seen point. It keeps its validated route while planning a replacement; a short visibility gap or an Alert/Hunting transition does not stop the legs and restart navigation. On reaching a supported approach to the last-seen area, it actively checks nearby supported positions at hunt pace, with brief 0.4-second pauses, while retaining red eyes and frozen sensory memory. Red searches expand from 8 to 18 blocks; orange searches expand from 12 to 28 blocks, with 0.6-second pauses. Both search at the first dry ground below a remembered airborne position, rather than requiring arrival at empty air. A failed direct approach also starts nearby searching. After twenty seconds without sight it enters Alert. A hunt also gives up after forty-five seconds without meaningful approach, allowing a committed leap or shockwave to finish without counting that time as a movement stall.

Giving up starts a finite investigation. Repeated sounds or glimpses do not reset the forty-five-second search deadline. After checking the remembered area and nearby supported locations, the Leaper returns home. A failed pursuit has a four-second reacquisition cooldown; the same inaccessible player must also visibly change the situation (relocate or become substantially closer) before restarting a hunt. This prevents red/orange oscillation beneath an unreachable ledge. Pursuit is confined to a 512-block territory around home.

Returning home also accepts reaching a checked, supported approach near the original anchor. The approach must remain within three horizontal blocks of home, and the body within eight blocks; reaching an unrelated destination cannot complete the return. This prevents a permanent hold when the exact home point has no support. Visible contact can interrupt a return and confirm a new hunt; footsteps alone do not repeatedly pull it away from returning. After crossing the territory limit it first returns inside 496 blocks before accepting another encounter, avoiding reversals at the boundary. The original patrol area stays fixed, and status distinguishes **Patrolling — returning home** from ordinary patrol.

Returns longer than 24 blocks first try supported waypoints approximately 20 blocks apart. If those fail, they test shorter 12-, 8- and 6-block steps and wider sideways approaches. Destination checks require a complete dry stance, and an old arrival flag cannot reset the sequence of alternative approaches. Each leg runs through the existing collision-aware planner; failed legs try another approach after a short pause. This keeps a distant return from repeatedly exhausting the search budget while preserving the original home.

Patrol destinations remain within the selected radius and within four vertical blocks of their anchor. Patrol uses the same stance-clearance rules as the movement controller and includes nearby fallback destinations. The existing collision-aware navigator validates paths and support. Patrol visits a waypoint area within 2.5 horizontal blocks and two vertical blocks, then takes a brief pause. Intermediate return legs use the same area tolerance once vertical motion settles. This avoids waiting for the exact planted-foot hold used by manual movement commands when foot-contact estimates flicker on uneven ground. Rejected routes are replaced promptly; active planning, recovery and clearing charges can finish. Failed patrol destinations time out and are replaced; no teleportation, physics reset or new learned policy is used. Ordinary patrol uses walking. A short recovery leap is permitted only after the walking planner cannot establish a starting stance, provided Apex has stable planted footing and can validate the takeoff, full flight path and landing. Combat shockwaves require an established hunting target within ten blocks (scaled with body size). They can start after that target hides behind cover, allowing excavation of nearby hiding places; patrol and alert do not attack unknown underground players. Once charging begins, the explosion commits: losing the target, changing modes, stopping movement or disabling future attacks does not cancel that blast. Removing the Leaper disposes its pending effects.

### Recovering from an obstruction

During autonomous ground travel, a temporary loss of physical stability pauses the leg drive while preserving the brain, territory and destination. After twelve consecutive stable ticks, navigation replans the saved destination. The status says **Recovering footing** during this hold. The old manager permanently disabled autonomous behaviour on a single such violation, even if the rig immediately settled upright. Manual movement retains its explicit stop limits.

Terrain-clearing recovery works in every travelling state, including Alert and the return home. If nearby foliage overlaps the conservative circular leg allowance, the Leaper first checks short retreats of four to eight blocks. A retreat must lead to a fully clear stance, keep dry foothold support and torso clearance throughout, move away from every existing foliage overlap, and introduce no new overlap. The Leaper steps sideways or backwards using its normal leg motors, then replans toward the original destination. It rechecks the escape during movement and abandons an obstructed or timed-out attempt.

If a bank leaves the rig with planted feet but no valid walking stance, Apex can check a recovery leap of at most 12 blocks to a supported destination. This does not grant sight, change awareness or attack a player. A rejected recovery leap permits another local approach.

If movement remains stalled for three seconds and a collision obstruction lies inside the ten-block blast volume, it charges the existing clearing burst. That recovery charge can finish without a visible player. It breaks terrain beneath itself as well as surrounding obstructions, releases the crouched pose smoothly, and waits for recovery before replanning. Supporting ground is not protected from the blast; the normal resistance rules and spherical radius still apply. The chassis ground probe only reads beneath the body, so an overhead roof cannot be mistaken for the floor. The attack cooldown is four seconds, half its previous value. Terrain damage has twice the previous radius (ten blocks) and twice TNT strength; it retains TNT resistance rules; hard shelters can survive a burst. A route-planning failure alone, ordinary low ground, exposed water, or buried water below a solid surface does not justify a blast. Exposed water still prevents a dry stance; water covered by solid supporting blocks does not. Status distinguishes the short retreat from an obstruction that still blocks travel.

## Controls

- `/mm leaper spawn`: spawn with an automatic 24-block patrol area.
- `/mm leaper patrol [radius]`: resume autonomous behaviour and establish a new home at the Leaper's current location; radius is 8–48 blocks.
- `/mm leaper hunt`: explicitly begin hunting you; subsequent tracking still obeys perception, memory and give-up rules.
- `/mm leaper stop`: pause autonomous behaviour and hold position; a committed charge still explodes.
- `/mm leaper goto <x> <z>` or a targeting-stick block click: take manual movement control.
- `/mm leaper status`: inspect state, transition reason, suspicion, evidence age and patrol radius.
- `/mm leaper pace <pace>`: select the hunt pace; patrol and alert have their own calmer paces.

Shift-right-click with the targeting stick stops behaviour. Right-clicking empty sky explicitly starts a hunt. Inspection poses and terrain-mode changes leave autonomous behaviour paused; `/mm leaper patrol` resumes it. Eye colour is stored in native block states, replicated to clients and available to late joiners; only the existing emissive eye material changes.

The existing player-owned lifecycle still applies: one command-spawned Leaper per owner, removed when the owner disconnects or the server stops. This change does not add persistent world spawning or save/reload of complete physical rigs.

## Verification

The [13 October 2026 behaviour report](evidence/leaper-behaviour-20261013.json)
records a fresh source-fingerprinted run. The detailed encounter measurements
below remain the September development record. They describe controlled native
fixtures and do not establish rendered quality or arbitrary-terrain reliability.

Run `python3 tools/verify_leaper_behaviour.py` for a single build-and-verification command with timestamped, source-fingerprinted reports. Apex is always exercised; `--apex` remains accepted for older scripts. The individual steps are:

```sh
./gradlew :minecraft_machines:common:test :minecraft_machines:neoforge:jar
python3 tools/verify_leaper_eyes.py
./gradlew :minecraft_machines:neoforge:runGameTest \
  -PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning \
  -PleaperScratch=true -PleaperMode=predator-behaviour \
  -PleaperProfile=horrific -PleaperHighStepCandidate=true \
  -PleaperAgile=true -PleaperLeap=true
```

A continuous native encounter uses a server-side fake player with the production sensor, coordinator and physical rig. It requires multiple completed patrol stops, hearing behind the Leaper at its actual height, natural visual confirmation, continuous pursuit through brief visibility gaps, finite searching and reacquisition before arriving home. Separate native fixtures test physical walls and cave roofs. The behaviour unit tests exercise evidence accumulation, frozen memory, finite searching, inaccessible-target suppression, return-home behaviour, target identity and committed-motion handling. Native fixtures exercise Minecraft occlusion and acoustic connectivity. A physical return-home regression removes support beneath the original home, verifies arrival at the nearby fallback, then requires a new patrol destination and renewed player detection. Resource verification checks every eye block-state combination and unchanged non-eye geometry/materials. The existing sustained hunting suite remains available through `python3 tools/verify_leaper_hunting.py`.

The launch regression runs a real charge and blast, then replays a 128-block flight with a 32-block peak above the floor. The server fake player has no client to integrate a launch packet, so its flight positions are controlled test inputs. Separation reached 63.2 blocks; the Leaper caught up within twelve blocks after 7.8 seconds. After a later mid-air loss of sight it chose 9 red and 2 orange ground search destinations. The fixture also verifies that a wall blocks extended tracking and that unknown distant players remain outside passive acquisition.

### Recorded September 2026 Apex behaviour

The Apex replay verifies all physical cell shapes, all 13 body masses and blockstate serialization across 15 eye-state checks. The continuous encounter runs the production sensor and coordinator against a server-side fake player for 1,792 ticks without forcing a hunt.

| Check | Result |
| --- | --- |
| Patrol | Three destinations selected, two physically reached; 9.52 blocks from home. |
| Footsteps behind the rig | Alert after 16 ticks (0.8 seconds). |
| Natural visual confirmation | Hunting after 16 ticks (0.8 seconds). |
| Chase with brief visibility gaps | 43.30 blocks travelled in six seconds; zero route drops or extra mode changes. |
| Partial footing | One checked recovery leap from two planted feet to dry ground. |
| Post-launch pursuit | Tracks beyond acquisition range and resolves ground below high flight; active red and orange searches after an airborne sighting. |
| Lost player | Twenty seconds of hunting with 8 nearby search destinations; frozen last-seen memory, then finite investigation. |
| Committed blast | One explosion despite target loss, stopped navigation and disabled future attacks; destroys blocks beyond five blocks, preserves blocks beyond ten. Repeated three times. |
| Visible prey while returning | Hunting after 16 ticks, before reaching home. |
| Obstructed 50-block return | Rejected the unsupported first leg and chose another approach; finished within 3.87 blocks of home. |
| Recovery | In that recorded fixture: seven checks repeated three times, including resuming after a temporary physical hold, physical retreat and clearing fragile overhead cover. Supporting ground happened to survive that course; current bursts can excavate beneath the feet. |

The recorded snapshot reported **228 passing unit tests**, **42 native perception checks**, **five passing Apex hunting regressions**, and **1,536 validated eye block-state combinations**. Those hunting regressions cover five sustained pursuit scenarios against the same historical source fingerprint. These controlled fixtures establish the behaviours they exercise; arbitrary terrain still depends on the physical navigator finding supported routes. Native block states/materials are verified; rendered appearance is separate from the automated checks.

[Source-fingerprinted behaviour report](evidence/leaper-behaviour/verification-20260915-161816/summary.json) · [Apex hunting regressions](evidence/leaper-apex/verification-20260915-162018/summary.json).

The running **Leaper Field Test 20260908** session was updated without replacing the physical rig, resetting its brain or changing its patrol anchor. The patch preserves any active charge. Three classes were redefined and two new pursuit helpers were loaded; their hashes match the packaged JAR. The original startup fingerprint remains historical. [Live patch](evidence/leaper-behaviour/live-launch-pursuit-patch.json).

Development JAR (local output: `artifacts/leaper-behaviour/MinecraftMachines-Leaper-Behaviour-Development.jar`; [build current source](../README.md#build-and-play)) · [Build manifest and verification links](evidence/leaper-behaviour/build-manifest.json). Use the existing mod dependencies and preview launcher described in [controls](leaper-controls.md).

## Unreachable pursuit stances

Pursuit validates the entire scaled stance before accepting a destination. A player-sized dry patch beside water is not enough room for a Leaper. When that check fails, approach candidates expand around the player, starting on the Leaper's side, with distances scaled to body size. Existing valid movement continues during background route updates.

If the real feet are planted but the walking graph has no start node, prolonged stationary recovery searches for a separate checked landing instead of braking indefinitely. Ordinary motor-driven retreats retain priority. Repeated route planning no longer resets the physical stall detector.

The native shoreline regression uses a 1.5× Leaper and a small dry island in water; it must reject the island, select a larger shore stance and physically travel more than six blocks.
