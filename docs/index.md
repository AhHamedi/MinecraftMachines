# Documentation

Start with the current Leaper preview and controls. Engineering records preserve the reported measurements, failures and implementation decisions from earlier development snapshots. Their historical commands, source hashes and test counts should not be treated as current release support or fresh acceptance of this checkout.

## Current guides

| Guide | Contents |
| --- | --- |
| [Leaper overview](leaper.md) | Current preview, physical assembly, movement, awareness and limits. |
| [Controls](leaper-controls.md) | Targeting stick, paces, patrol, hunting and leap profile. |
| [Apex movement](leaper-apex.md) | Current assistance, traversal, footing and verification workflow. |
| [Awareness and behaviour](leaper-behaviour.md) | Sight, hearing, memory, investigation, returning home and recovery. |
| [Concussive attack](leaper-shockwave.md) | Committed charge, player launch, terrain destruction and clearing traps. |
| [Physical scaling](leaper-scaling.md) | 1×, 1.5× and 2× bodies, preserved state and resource checks. |
| [Contributing and development](../CONTRIBUTING.md) | Setup, core tests, native fixtures and review expectations. |
| [Python training](../training/python/README.md) | Separate Duopod Gymnasium / PPO bridge and evaluation contracts. |

## Media and evidence

[Selected media and evidence](evidence/README.md) records provenance and the limits of the published clips, charts and reports. The [publication manifest](evidence/publication-manifest.json) identifies their local source paths and hashes. Development recordings retain their original dates and source fingerprints. The [13 October 2026 repository verification record](evidence/repository-sync-20261013.json) separately records the fresh source build and checks.

## Engineering records

These records help reproduce a specific experiment and explain why the controller changed. Remaining paths under `artifacts/` and `release/` refer to local development output omitted from the repository. Build the current source instead of looking for an old local JAR.

| Record | Development scope |
| --- | --- |
| [Original Leaper articulation](leaper-articulation.md) | Initial 13-body model, motor limits and flat-ground qualification. |
| [Point navigation](leaper-navigation.md) | Trained point controller, grip experiments and early qualification. |
| [Careful and Fast](leaper-fast.md) | Early flat-ground gait comparison. |
| [Horrific sprint](leaper-horrific.md) | Sprint training, braking and endpoint tradeoffs. |
| [Super Horrific and pursuit](leaper-super-horrific.md) | Development sprint, approach gating and ballistic traversal. |
| [Terrain navigation](leaper-terrain.md) | Terrain-conditioned stepping and native optimization. |
| [Precise collision revision](leaper-terrain-v2.md) | Collision envelopes and terrain qualification. |
| [Predator terrain revision](leaper-predator.md) | Wilderness routes and continuous terrain controller. |
| [Swing clearance](leaper-clearance.md) | Earlier lift, coordinated servo limits and difficult risers. |
| [Field testing](leaper-field-test.md) | Turning and a recorded field world. |
| [Uphill assistance](leaper-uphill.md) | Recorded ascent correction. |
| [Hunting measurements](leaper-hunting.md) | Sustained chases and earlier traversal/recovery rules. |
| [Shoreline preparation loop](leaper-loop.md) | Captured failing body pose and native regression replay. |
| [Departure and arrival](leaper-departure.md) | Launch clearance and supported arrival handoff. |
| [Predator landing](leaper-predator-landing.md) | Terrain-aware landing and descent work. |
| [Original quadruped](quadruped.md) | Eight-joint gait training and explicit terrain failures. |
| [Quadruped point navigation](quadruped-navigation.md) | Flat-ground destinations, turns, stopping and multi-point routes. |

The original [Duopod preview](media/duopod-demo.gif) remains as historical media. It is distinct from the current Leaper.
