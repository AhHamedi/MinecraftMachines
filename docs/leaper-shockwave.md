# Leaper concussive attack

Launch `tools/run-client.sh --preview` (or `tools/Run Leaper Hunt.command` on macOS), enter a disposable world, and run `/mm leaper spawn`. An established hunting target within ten blocks of its central chassis can trigger the attack, including through cover to excavate a hiding place. Creative mode lets you inspect it without taking damage. Autonomous hunting follows the perception rules; `/mm leaper hunt` explicitly starts pursuit.

The Leaper lowers its stance while gold-white trails accelerate around it and tighten inward. Pursuit continues throughout the charge and recovery. After three seconds, it discharges a pressure burst with sparks, terrain chips, rolling dust, positional sound and a brief camera impulse. The attack then recovers and waits four seconds before charging again. A newly spawned Leaper gets two seconds to settle first.

| Command | Effect |
| --- | --- |
| `/mm leaper shockwave off` | Disable future attacks; a charge already underway still explodes. |
| `/mm leaper shockwave on` | Enable attacks against established prey, including hidden prey and terrain-clearing recovery. |
| `/mm leaper remove` | Remove the Leaper. |

The ten-block trigger test includes the player's occupied height, so standing under the raised chassis counts. Trigger range scales with body size: 10 blocks at 1×, 15 at 1.5×, and 20 at 2×. Patrol and alert states do not acquire unknown underground players through this attack check. Living hunted players trigger the charge; spectators do not. The Leaper starts an attack from an upright, supported stance. Once charging starts, the explosion commits even if the player escapes, loses line of sight or the Leaper changes awareness mode. Movement commands and disabling future attacks do not cancel the current charge. Removing the Leaper disposes the attack.

It also uses the pulse to free itself: after roughly three seconds of little movement while trying to reach a destination, nearby solid obstructions can trigger the same charge without a player in range. Idle Leapers and normal supporting ground do not trigger this recovery. The usual attack cooldown and block resistance rules still apply. The clearing blast also excavates breakable blocks underneath the chassis and feet; there is no protected support plane. After the blast, it recovers its footing and replans through the changed terrain. `/mm leaper shockwave off` disables future proximity attacks and clearing pulses; an active charge still completes.

When nearby blocks put excessive strain on the joints, it holds its stance while preparing the clearing pulse and keeps its destination. Falling over, leaving the owner's dimension, explicit stop commands and unrelated controller faults still stop navigation.

At normal size, Survival players within ten blocks at discharge receive an outward and upward impulse, with three times the original base launch strength. Damage reaches five blocks. Trigger, launch, damage and terrain radius checks scale with body size; damage strength and launch velocity keep the same tuning. Both player effects use distance falloff, and solid cover blocks the hit. Creative players take neither damage nor knockback. Screen motion follows Minecraft's **Screen Effects** accessibility setting, including zero.

At normal size, the blast also breaks terrain within ten blocks of its center using twice TNT's explosion strength, blast resistance, water shielding and loot rules. Radius and explosion strength scale with the body. Bedrock and obsidian survive. Destroyed blocks drop their normal explosion loot, including container contents; TNT can ignite in a chain reaction. The world’s `doTileDrops` and `tntExplosionDropDecay` rules apply. The Leaper's own body parts are protected.

The implementation lives in the existing native NeoForge Leaper manager, with server-owned timing and damage and a compact client effects payload. The two sound effects and particle textures are original procedural assets. Reference-game art and audio are not bundled.

Testing used the isolated **Leaper Shockwave Review 20260914** world. The implementation and verification record (local evidence: `artifacts/leaper-shockwave/IMPLEMENTATION.md`) contains the live checks, capture provenance and independent animation reviews.

Committed-attack fixtures remove target eligibility, stop navigation and disable future attacks mid-charge, then verify exactly one blast, destruction beyond the old five-block limit, preservation outside ten blocks, and a four-second cooldown. The [fresh 13 October 2026 behaviour report](evidence/leaper-behaviour-20261013.json) records current fixture verification; the [September build manifest](evidence/leaper-behaviour/build-manifest.json) preserves the earlier development snapshot.

## Escape after a clearing burst

After the burst finishes and the body recovers, the Leaper searches for a supported escape landing, starting towards its travel goal and then trying other compass directions. Search work is spread over ticks. It uses the normal landing-foot and swept-flight collision checks. If no launch is possible within the bounded search, it resumes terrain navigation. The original pursuit or patrol destination remains intact.

Jumps emit a layered flame plume under the chassis with a bright throat, embers and smoke. The plume follows the physical body, scales with its size, and tapers before touchdown. It is a visual effect and does not place fire blocks.
