# Predator landing and terrain assistance — 2026-09-08

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

Development continuation of the field-test controller. The user prioritized predator responsiveness and explicitly authorized gameplay impulses. This is an engineered navigation/physics change on the existing gait, not a new neural training run.

## Behavior

Landing hands control back after two control ticks when at least one toe is near support, the body remains sufficiently upright, and vertical speed is within the usable contact range. It no longer waits for three planted feet and a motionless stance. A separate quiet-motion fallback releases a rig resting against terrain after ten landing ticks and eight consecutive quiet samples; actual freefall does not qualify. New airborne destinations remain queued until handoff. Walking can resume during the 12-tick (0.6-second) repeat-leap cooldown. The return-to-gait blend is eight ticks (0.4 seconds), down from 16; motor slew limits remain active.

Grounded, commanded locomotion on rough terrain or after a stall can receive a small assembly-wide velocity increment. Each increment is at most 0.30 blocks/s horizontally and 0.45 vertically, at most once per two control ticks. Horizontal speed ceilings are 1.5 / 3.5 / 5 for Careful/Horrific/Super Horrific assistance. The upward component additionally requires a higher nearby surface and overhead clearance. Each rigid body receives its mass-proportional native impulse, so the initial velocity increment is uniform across all 13 bodies. Assistance is disabled during leap preparation, flight, landing, explicit stop, arrival, route planning, and blocked routes. This assistance is intentional gameplay behavior.

The arc search prefers 55–60-degree launches under finite velocity/impulse caps, testing steep candidates before low arcs. Landing candidates evaluate four dry footholds independently against actual joint limits and leg reach. They may use different heights and a narrower stance. The late-flight collision sweep narrows toward the torso, allowing physical leg contact with the landing terrain. These checks do not guarantee collision-free leg trajectories on arbitrary terrain.

## Evidence status

Preliminary native reports are retained under `artifacts/leaper-follow/landing-03.json` and `landing-04.json`, each with exact source snapshots. The latter passed five cases: flat contact, uneven contact, a physical partial-pad loss leaving two supported feet, removed-pad freefall, and two-block terrain assistance followed by an explicit stop. Contact handoffs took two ticks. All13 native body velocity increments matched the requested horizontal assistance within floating-point tolerance. Vertical assistance did not occur in those fixtures and is not claimed as positively tested there.

Final combined-source regression and natural dune replay results are recorded below. Prior reports are development evidence, not a numerical world-class qualification or a promise of universal wilderness traversal.

## Live natural dune replay

Combined source `f4cefb0ed052234869db0af4be259722fbbf4e27fec88c771934f7cd5b13ee6b`, archived as `artifacts/leaper-steep/predator-source.zip`, was loaded in the normal **Leaper Field Test 20260908** save. The rig started near (-63.51,83.31,17.61) on natural sand; the unchanged requested surface point was (-50.274,86,38.779). Only the player/camera and time-of-day were set for review; no terrain edits or rig repositioning occurred during traversal.

The previous build chose a short low arc to the dune base and became stuck in landing recovery. This source selected the actual target directly, launched at 60.25 degrees, flew 2.65 seconds, detected four supported toes, and returned to locomotion after two ticks. Arrival was announced about 7 seconds after submission. The final hold snapshot was 0.266 blocks from the goal horizontally; terrain-assist count was 0. This particular improvement came from steep ballistic selection, individually reachable footholds and prompt handoff.

Evidence: `predator-start.json`, `predator-final.json`, `predator-dune-summary.json`, `predator-client.log` and the unaccelerated 14-second `predator-dune.mp4` under `artifacts/leaper-steep`. The video retains recorded wall-clock timing; `predator-dune-encoding.json` stores its SHA256 and capture details.

## Final combined-source regression

All reports below record the same `f4cefb0e…` source and explicit runtime candidate/settings; no source edits occurred between these runs. Artifacts under `artifacts/leaper-steep`:

- `final-f4ce-landing.json`: 5/5. Flat, uneven and physical two-foot support-loss handoffs completed after two ticks; pending goals continued. Missing-platform freefall never falsely resumed. Native horizontal and upward assistance were exercised (upward delta 0.449999941); all 13 body increments matched, with no assist in flight or explicit hold. Maximum anchor separation 0.07636 blocks; no joint-limit violations.
- `final-f4ce-steep.json`: 6/6, short straight/diagonal hops, raised platforms, 40- and64-block long-range cases.
- `final-f4ce-elevation.json`: 12/12, raised/lowered straight and diagonal platforms. The slowest +12-block cases still took 17.1 / 23.45 seconds including preparation, replanning and approach. Worst final horizontal error 2.443 blocks under the 2.5-block fixture gate; not every result is within two blocks.
- `final-f4ce-commands.json`: 8/8, stop, queued retargeting and launch-clearance/cooldown controls.
- `final-f4ce-pursuit-02.json`: 9/9, nearby approaches and holds in all paces, far/elevated planning, close-wall backing away, open run-up and Super Horrific pursuit. The 64-block Super Horrific case used a leap, so this suite does not re-establish the earlier ground-only sprint speed. The first pursuit launch used an incorrect mode and skipped the cases; its log is preserved and it is not counted.

Total: 40/40 native regression cases passed on the combined source.

Steep/elevation results include 40 consecutive final walking samples with horizontal speed below 0.5, distance within 2.5, and the correct landing floor. This is a bounded development regression, not an arbitrary-wilderness success rate.

The client remains open in the field-test world with the targeting stick. Restart instructions and stick controls are in [the field-test guide](leaper-field-test.md). This preview does not replace the older packaged release.
