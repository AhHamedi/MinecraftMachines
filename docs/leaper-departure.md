# Blocked departure and missed arrival

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The second live shoreline failure was different from the crouch loop. The rig had three supporting feet, but both route searches had exhausted their candidates. The HUD continued to say “Finding a way” merely because `routeBlocked` remained true.

The launch sweep was a solid 11-block-wide box from the first flight sample, including the empty space between the grounded legs. At the captured departure, the 2.8- and 6-block-wide body envelopes were clear while the 11-block envelope intersected surrounding terrain. This rejected every launch before the legs could leave their existing contacts. The walking planner also rejected the exact requested destination, although nearby stances and a physical landing were possible.

Apex now expands the departure envelope from torso width to full limb width over the first fifth of the planned flight. Continuous overhead and torso collision checks remain, alongside the existing full flight/landing checks and native limb collision. Failed automatic jump searches back off for two seconds. New destination commands still initiate their own search.

After the corrected jump reached the target, a second issue appeared: an empty walking route returned before the arrival logic. Apex now recognizes actual supported, settled arrival within the normal two-block radius even when the walking graph rejects that stance. It resumes planning after a sustained departure from the holding range. The HUD distinguishes active search, leap preparation, retreat, blocked reason, and arrival.

## Live result

The updated running rig made one leap with no additional aborts and reported arrival 0.708 blocks from the unchanged target, supported by three feet. No terrain modification, rig teleport or restart was used. [Live result and loaded-class hashes](evidence/leaper-way/live-result.json) preserve the observation. Later normal commands also produced arrival messages in the client log.

## Regression

`predator-departure-clearance` replays the saved terrain and thirteen body poses in the scratch world. The baseline remained 25.88 blocks away with zero launches throughout 1,200 ticks. The final assertion requires sustained supported settling **and** the navigator's arrived/holding flags; physical proximity alone is insufficient. [Baseline](evidence/leaper-way/baseline.json) and [initial corrected arrival replay](evidence/leaper-way/arrival-fix.json) retain the diagnostic runs.

Select focused suites with repeated `--suite` arguments to `tools/verify_leaper_hunting.py`; `--apex --full` includes this regression too.

Final source `c409dc57f1fbd49ce61604155886431d2a704c4615bc36d61027a98202e18a53` passed **181 unit tests and 31 focused native checks**: captured departure, prior crouch-loop replay, partial footing, pounce commands, elevation and hunting. [Verification summary](evidence/leaper-apex/verification-20260914-194752/summary.json), development JAR (local output: `artifacts/leaper-way/MinecraftMachines-Leaper-Apex-Departure-Fix.jar`; [build current source](../README.md#build-and-play)), [build manifest](evidence/leaper-way/build-manifest.json), and source snapshot (local output: `artifacts/leaper-way/source.zip`; [build current source](../README.md#build-and-play)) preserve this build. The earlier full-suite counts describe their respective earlier source snapshots.
