# Uphill assistance — 2026-09-08

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

This development change adds gameplay suspension and impact braking to the existing gait. It is not a new neural training run.

The planner now samples one-block treads, retains lower rear support when the front feet are on higher steps, and raises its planned body clearance over successive risers. Collision checks remain enabled. Search can process32 nodes per slice instead of4, within the existing2ms deadline.

A nearby-terrain controller supplies bounded mass-proportional native impulses across all13 bodies. It lifts the torso before driving into a riser and continues across brief loss of foot contact. Ordinary horizontal delta is capped at0.6 per control tick, vertical at-0.7 to+1.25. Backward impacts may receive up to3 horizontal delta, but that exception must decrease horizontal kinetic energy. Actual leaps, genuine drops, stopped/arrived states and blocked routes disable it.

The original one-block treads remain unreliable. The user authorized two-block treads. The delivered review course has three two-block rises and two-block treads, for six blocks of total ascent. Leaping is disabled in the review so it must climb.

Evidence under `artifacts/leaper-hill`: the frozen baseline and two matched assistance-off/on comparisons retain failures. Native stance probes identified real torso intersections and discarded footholds. `wide-stairs-01.json` completed both wider routes, but Horrific failed the tighter backslide criterion; that report is not presented as a fully satisfactory result. The final impact-braking check and live result follow below.

The isolated `Leaper Uphill Review 20260908` world contains no copied regions, entities or physics state. The normal field world was saved intact. Right-click a block with the stick to navigate; Shift-right-click stops. This review session has leap disabled; the normal development launch can enable it with `-PleaperLeap=true`.

## Final result

`wide-stairs-02.json` and its source archive record implementation `a28503d685ebf1872072d3e47d4e8570dc7304cf2f88d37422ee2db231f73f5c`. Both two-block-tread cases passed functional and prospectively fixed quality criteria. Horrific:16.3seconds,0.909blocks total backward travel,0.320 maximum setback. Careful diagonal:35.3seconds,2.808 total backward travel,0.719 maximum setback. Neither fell, leaped or recorded a reverse burst above4blocks/s; no joint-limit violations, maximum anchor error0.06277. All29 stronger impact-braking applications reduced measured horizontal kinetic energy. Stop drift remained below0.015blocks.

The final live review completed the same six-block staircase in about20seconds. `artifacts/leaper-uphill/final-state.json` records arrival; `final-review.mp4` preserves wall-clock capture timing. The live client compiled before the final harness audit edit; production controller/planner code is the same, but its implementation hash includes the earlier test harness. Fourteen common planner tests passed. One-block treads remain unreliable and are not claimed as solved.
