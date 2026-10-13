# Leaper

The Leaper is a physically articulated, player-owned machine for Minecraft 1.21.1 / NeoForge. Its appearance is a reference-based interpretation of the ARC Raiders Leaper. The meshes and procedural effects are authored for this project; reference-game assets are not bundled.

Current gameplay combines trained gait parameters, an engineered terrain planner, the Apex force controller, sensory awareness and a concussive attack. It is more than the earlier flat-ground point-navigation prototype. The Python PPO bridge belongs to the separate Duopod experiment and does not control this machine.

## Start the current preview

From the repository root with Java 21:

```sh
tools/run-client.sh --preview
```

This prepares the versioned preview candidate/settings and enables high-step, agile movement and ballistic leaps. On macOS, [Run Leaper Hunt](../tools/Run%20Leaper%20Hunt.command) and [Run Leaper Apex](../tools/Run%20Leaper%20Apex.command) use the same setup. The ordinary launcher without `--preview` loads the earlier packaged baseline; results from the two workflows should be distinguished.

Open a disposable world with cheats enabled. Creative mode permits inspection without taking attack damage. Spawn where the full body and legs have room to settle; the attack can destroy the surrounding terrain.

```text
/mm leaper spawn
/mm leaper stick
```

Spawn starts a blue-eyed patrol within 24 blocks. Survival and Adventure players can alert it naturally; `/mm leaper hunt` explicitly starts pursuit of the operator, including for Creative testing. Right-click a block with the stick for a manual destination, empty air to hunt, or Shift-right-click to stop.

| Command | Purpose |
| --- | --- |
| `/mm leaper patrol [radius]` | Resume autonomous behaviour; establish home at its current position, radius 8–48 blocks. |
| `/mm leaper hunt` | Begin a hunt; later tracking obeys sight and memory rules. |
| `/mm leaper goto <x> <z>` | Request a manual ground destination. |
| `/mm leaper stop` | Pause movement and autonomous behaviour. |
| `/mm leaper pace careful\|standard\|horrific\|super_horrific` | Select a movement/hunt pace. `fast` aliases Standard. |
| `/mm leaper scale 1\|1.5\|2` | Rebuild the body at an authored physical size. |
| `/mm leaper shockwave on\|off` | Enable or disable future attacks and clearing pulses. |
| `/mm leaper stand`, `crouch`, `articulate` | Inspect motor-driven poses; pause autonomous behaviour. |
| `/mm leaper watch`, `unwatch` | Enable or release the follow camera. |
| `/mm leaper status` | Inspect behaviour, size, pace, body tilt and joint-anchor error. |
| `/mm leaper remove` | Remove the owned rig, including any committed attack. |

Stopping movement or disabling future shockwaves does **not** cancel a charge already underway. Use Remove to dispose the rig. See [controls](leaper-controls.md) for targeting and pursuit details.

## Physical assembly and movement

The normal-size rig has 13 independent Sable rigid bodies and 12 force-limited motor joints. Each leg has a hip swivel, a shoulder bending axis and a knee bending axis. The chassis remains free to translate and rotate. Approximate authored dimensions at 1× are a 2.10-block core diameter, 4.00-block upper joint span, 4.50-block lower knee-to-toe span and 2.25-block nominal core height. The rig's full stance is much wider than its core.

Visible meshes use stepped cuboid armour, native block textures and an emissive eye. Geometry generation removes internal and overlapping coplanar faces. Native collision proxies follow the authored body cells; small decorative details do not each receive their own collider. Physical sizes 1, 1.5 and 2 also rebuild joint anchors, feet and planning envelopes; see [scaling](leaper-scaling.md).

Apex is always active during gameplay. It adds bounded traction, damped suspension, steering and braking to the articulated terrain gait. Movement uses motor targets and native impulses rather than travel teleportation. Walking retains priority where a supported route exists. Checked leaps handle gaps, elevation changes and recovery; takeoff needs planted footing and the search checks the flight envelope and supported landing. The current arc preference is near 70°, considering arcs of at least 55° before shallower fallbacks. Landing and temporary footing loss preserve travel intent while the body recovers.

Careful, Standard, Horrific and Super Horrific supply different pace preferences. Patrol uses Careful and investigation uses Standard. Faster selections trade stopping precision and smoothness for speed; clear-ground cruise measurements from an older fixture do not predict every journey. See [Apex movement](leaper-apex.md) and the [historical pace records](index.md#engineering-records).

## Awareness and combat

Blue eyes mean patrol, orange eyes mean investigation, and red eyes mean hunting. Vision originates at the physical optical disk and tests terrain and moving geometry. Light, visibility, crouching and invisibility affect acquisition. Hearing supplies coarse nearby clues and does not independently confirm a hunt.

Visible prey can be pursued predictively. Lost sight freezes the last-seen position; searching is finite, and unsuccessful hunts eventually return home. The brain does not continue receiving a hidden player's live coordinates. See [awareness and behaviour](leaper-behaviour.md).

An established nearby hunting target can trigger a three-second concussive charge. The blast uses original particles and sound, damages nearby Survival players, launches them and breaks terrain according to blast resistance and world loot rules. A persistent physical obstruction can trigger the same pulse for recovery. Range and visual size scale with the body. Attack timing is owned by the server and remains committed through target loss or a movement stop. See [the attack guide](leaper-shockwave.md).

## Scope and verification

One Leaper can be active per owner. It is command-spawned, removed when the owner disconnects or the server stops, and does not persist as a saved world mob. A controller can reject unsupported, wet or obstructed stances. Dense vegetation, narrow geometry, landing rebound and difficult terrain remain limitations.

The repository includes unit tests, native Sable GameTests, resource validators and separate behaviour/hunting runners. Run the checks in [CONTRIBUTING.md](../CONTRIBUTING.md), then inspect movement and effects in the native client. Automated fixtures establish the behavior they exercise; they do not establish visual comfort or universal world reliability.

The [original articulation and flat-ground record](leaper-articulation.md) preserves early dimensions, motor envelopes and recorded measurements. The [documentation index](index.md) links the subsequent terrain, pace, pursuit and recovery records and distinguishes them from current controls.
