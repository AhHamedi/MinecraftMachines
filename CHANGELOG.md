# Development changes

## Unreleased source snapshot — 13 October 2026

This repository update brings the published source and documentation in line
with the existing Leaper development work. It does not announce a supported
binary release.

- Added the articulated 13-body, 12-joint Leaper and its original cuboid models,
  collision geometry, eye states and procedural effects.
- Included current Apex terrain movement, gait and steering, predictive
  ballistic traversal, partial-footing recovery, retreat and escape planning.
- Included patrol, sensory investigation and hunting, with obstructed sight,
  footsteps, bounded prediction, frozen last-seen memory and finite searches.
- Included committed concussive charges, player launch, terrain destruction,
  clearing pulses, sound and jump effects.
- Included authored 1×, 1.5× and 2× physical bodies and Careful, Standard,
  Horrific and Super Horrific pace controls.
- Retained the earlier quadruped, Duopod, servo and training experiments, with
  their distinct control and evaluation contracts.
- Added portable preview setup and launchers, versioned controller inputs and
  resource/native verification workflows.
- Rebuilt the public guides, published selected gameplay recordings and dated
  verification evidence, and preserved earlier results as engineering records.

Fresh [behaviour](docs/evidence/leaper-behaviour-20261013.json),
[scaling](docs/evidence/leaper-scale-20261013.json) and
[sustained hunting](docs/evidence/leaper-hunting-20261013.json) reports record
the current source checks. Selected gameplay clips were recorded in September
2026 and remain separate from those checks; see
[evidence provenance](docs/evidence/README.md).
