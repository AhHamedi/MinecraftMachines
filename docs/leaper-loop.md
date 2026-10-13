# Shoreline jump-preparation loop

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

The live field-test Leaper repeatedly found an arc while standing, entered its fixed deep crouch, then rejected the arc during the mandatory takeoff recheck. Each abort restored its standing pose, making the original arc feasible again. The first observation contained 162 aborts near the same location; reissuing the same destination reproduced the cycle in the running game.

Apex now preserves its terrain-adapted supporting stance during launch preparation. Its mass-distributed launch impulse already supplies takeoff energy, so a forced symmetric crouch is unnecessary. The takeoff search still checks the current body position, native gravity and drag, terrain collision, landing footholds, velocity limits and impulse limits. Classic retains its existing crouch behavior.

## Actual-world verification

The method was updated in the already-running client, preserving the destination at (-318.8144, 64, 20.0223). The existing rig made two successful leaps and arrived 0.256 blocks horizontally from that target, with four supporting feet, 0.0303-block maximum joint-anchor separation at the final observation, and zero additional aborts. No player-world terrain changes or rig teleport were used. [Live result](evidence/leaper-loop/live-result.json) records the original runtime source fingerprint and the hash of the updated class; a method update is not represented as a full client restart.

## Regression fixture

The scratch-world test copies the actual shoreline and initializes the thirteen body poses and motor targets from the live snapshot. It then uses ordinary navigation and native physics. Terrain alone did not reproduce the failure: the captured leg configuration was necessary. With that configuration the old code failed to arrive during the 1,200-tick test; the corrected code reached the target in 217 ticks including initialization, with two launches and zero aborts. [Baseline](evidence/leaper-loop/baseline-pose.json) and [corrected replay](evidence/leaper-loop/fixed-pose.json) retain the trajectories.

`python3 tools/verify_leaper_hunting.py --apex --full` includes this case as `predator-shore-loop`, alongside the partial-support, hunting and existing movement suites. The captured terrain and pose inputs are under `data/minecraft_machines/leaper/fixtures/` and are used only by that explicitly selected scratch test.

Final source `fee49c40c30364ade664dcb848196cfba50962427a4e5986339e57b11968f66d` passed **181 unit tests and all 67 native checks**. [Verification summary](evidence/leaper-apex/verification-20260914-192850/summary.json), development JAR (local output: `artifacts/leaper-loop/MinecraftMachines-Leaper-Apex-Shoreline-Fix.jar`; [build current source](../README.md#build-and-play)), [build manifest](evidence/leaper-loop/build-manifest.json), and source snapshot (local output: `artifacts/leaper-loop/source.zip`; [build current source](../README.md#build-and-play)) preserve the permanent fix.
