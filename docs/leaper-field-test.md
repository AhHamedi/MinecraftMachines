# Faster turning and field testing — 2026-09-08

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The development build now turns faster and supports following the owning player through the targeting stick. This extends the existing trained gait and engineered navigation/controller; no new neural training was performed.

## Controls and world

The fresh save **Leaper Field Test 20260908** uses normal overworld noise generation, seed2026090717, structures enabled, default game rules, and Creative mode for testing. No region/entity/physics files were copied and no starting platform or terrain fill was applied. The rig was spawned on a naturally flat sandy area near(-64,80,17); dunes and jungle are nearby. `artifacts/leaper-follow/world-manifest.json` records creation.

- Right-click a supported block: select that point and leave follow mode.
- Right-click with no block under the128-block collision ray: follow the player continuously.
- Shift-right-click: stop, including immediately after another click. Physical airborne motion still completes its landing.
- Wall-side hits retain the supported-surface validation; they do not accidentally enable follow.
- `/mm leaper pace super_horrific`, `horrific`, `fast`, `careful` select pace.

A collision-ray miss is broader than literal sky: plants without collision and ignored fluids can also be missed. Invalid destination clicks retain the previous valid intent.

## Implementation

Deliberate turns allow a yaw command up to1 instead of0.5 in navigation/0.7 in the gait. At low translation on clear ground, a short blend increases turning stride to4.6, shortens period to0.60s, reduces excessive lift to0.40 and uses bounded power/slew8. Ordinary heading corrections and sprint cruise keep their gait. Native motors supply rotation; no angular teleport or impulse was added.

Following projects predicted player positions onto supported terrain before deduplication. Player updates coalesce while planning, retreating or preparing a launch. Normal follow replans are limited to once per40 control ticks, preserving sprint inputs and the existing launch cooldown. Airborne updates queue the latest supported goal without replacing the committed landing. Explicit block selection remains immediate.

## Verification

`artifacts/leaper-leap/turning-01-baseline.json` and `turning-02-candidate.json` compare native heading alignment (within10degrees):

| Pace | 90 degrees before / after | 180 degrees before / after |
|---|---:|---:|
| Horrific | 3.00 /2.05s | 6.60 /2.55s |
| Super Horrific | 4.50 /1.65s | 9.20 /3.10s |

All eight candidate turn cases passed, including actual point pursuit and settled holds. Maximum joint anchor separation0.0764blocks, no joint-limit violations, no takeoff impulse. Matched sprint checks in `turning-02-super-speed.json` passed8/8 and retained12.1525 versus3.9323blocks/s (3.0904x flat cruise).

Final combined source: `51863e4242d5f5a7102828e62ec3df7253a210f1d7fe7683a03b7c9bab05bfd1`, archived in `artifacts/leaper-follow/final-source.zip`.

- `final-pursuit.json`:9/9, covering nearby holds, far/elevated plans, close-wall backing up, Super approach.
- `final-commands.json`:8/8, including airborne retargeting/stop, cooldown and changed takeoff clearance.
- `final-follow.json`:3/3. Moving flat target:59.95blocks of progress during target motion, final0.136blocks. Moving uphill+12 target:launches before target stops, preserves preparation/flight through updates, final0.649blocks. Stationary jumping target:16height-varying updates produce exactly one plan, final1.959blocks. Final stable windows59/63/88ticks. Native tests use standard unballasted rig and4physics substeps.

Turning and sprint reports have their own source hashes/snapshots; the subsequent changes were stick/follow test additions, not another turning or sprint-profile revision. Development reports retain `qualified:false`; they do not certify arbitrary wilderness traversal.

Live field checks used actual server item events and read-only snapshots: `following-state.json` records following enabled; `moved-state.json` records the changed target and actual arrival; `block-state.json` records following disabled by a valid ground click; `stopped-state.json` records hold mode, no goal, no planning after sky-click immediately followed by Shift-click. Setup commands moved the player to exercise these controls; they did not reposition the rig or alter terrain. `client.log` records the session. Initial Sable sub-level packet-order errors occurred during spawn; the assembled rig subsequently rendered and moved normally.

## Restart this development world

With Java21 configured, run from the repository root:

```sh
./gradlew :minecraft_machines:neoforge:runClient -PleaperMode=predator-preview -PleaperProfile=horrific -PleaperHighStepCandidate=true -PleaperAgile=true -PleaperLeap=true '-PquickPlayWorld=Leaper Field Test 20260908'
```

After a game restart, `/mm leaper spawn` creates a fresh rig16blocks north of the player, requiring15blocks of flat clear ground. The reviewed starting coordinates are player(-64,83,33). Use `/mm leaper stick` and select your pace. The old packaged release remains unchanged; this is the current development preview.
