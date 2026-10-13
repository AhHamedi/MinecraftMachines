# Contributing to Minecraft Machines

Minecraft Machines is an early-stage, physics-sensitive prototype. Small, focused contributions are easiest to review.

## Development setup

Use Java 21 and clone the pinned submodule:

```bash
git clone --recursive https://github.com/AhHamedi/MinecraftMachines.git
cd MinecraftMachines
```

If needed, run `git submodule update --init --recursive`.

Build the add-on with `./gradlew :minecraft_machines:neoforge:build`.
Start the current Leaper preview with `tools/run-client.sh --preview`. The
portable launcher checks Java 21 and copies versioned candidate/settings from
`tools/profiles/leaper-preview/` only when the runtime inputs are missing.
Existing local candidates are preserved. Record their hashes when comparing a
local experiment with the versioned preview.

For a direct Gradle launch, prepare the inputs and supply the same flags:

```sh
python3 tools/prepare_leaper_preview.py --target client
./gradlew :minecraft_machines:neoforge:runClient \
  -PleaperMode=predator-preview -PleaperProfile=horrific \
  -PleaperHighStepCandidate=true -PleaperAgile=true -PleaperLeap=true
```

Apex is always enabled by the current build. Running `tools/run-client.sh`
without `--preview` uses the earlier packaged controller baseline; it does not
load the current preview candidate.

Development runs default to a 1 GiB initial heap, 4 GiB maximum heap and 2 GiB
direct-memory limit. Override these with `-PminecraftRunHeapInitial`,
`-PminecraftRunHeapMax` and `-PminecraftRunDirectMemory` (for example,
`-PminecraftRunHeapMax=8G`). Optional quick-play selects an existing local world
with `-PquickPlayWorld=<name>`; prepared lab worlds are not included in the repo.

Use a disposable world for Leaper combat and training. Shockwaves destroy
terrain, and the Duopod training bridge owns the dimension's Sable clock.

## Before opening a pull request

Run the core checks:

```bash
./gradlew :minecraft_machines:common:test :minecraft_machines:neoforge:compileJava
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

Changes to assembly, servo behavior, training lifecycle, world mutation, or cleanup should also pass:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest
```

For Leaper changes, run the relevant resource and native physics checks:

```sh
python3 tools/verify_leaper_eyes.py
python3 tools/verify_leaper_scales.py
python3 tools/verify_leaper_behaviour.py
python3 tools/verify_leaper_hunting.py --apex --full
```

The behaviour and hunting runners prepare preview inputs, rebuild the mod,
exercise disposable scratch worlds and check fresh report/source fingerprints.
Run them sequentially: concurrent native suites must not share a game directory.
Generated reports and recordings remain local under `artifacts/`; selected
published evidence lives under `docs/evidence/` with its provenance.

The older quadruped robustness suite intentionally includes unsupported blue
ice and half-block obstacles and reports those failures. It is separate from
the base integration and current Leaper fixtures.

## Guidelines

- Keep changes scoped to one problem.
- Add or update tests when behavior changes.
- Preserve compatibility checks for observation, action, policy, and checkpoint formats.
- Do not modify `external/Simulated-Project` directly. Update its pinned revision in a separate change.
- Do not copy third-party code or assets into Minecraft Machines without confirming license compatibility and preserving the required notices.
- Preserve attribution headers and update `THIRD_PARTY_NOTICES.md` when dependencies or derived build files change.
- Treat training output and visual replays as diagnostics, not proof of general behavior.
- Describe manual testing clearly when a change depends on live Minecraft physics.
- Keep current player controls separate from historical development measurements.
- For movement, perception, attacks or assets, inspect the original behaviour in
  the native client and report any remaining failure. Passing fixture counts
  alone do not establish gameplay or rendered quality.

By contributing, you agree that your original contribution is licensed under
the repository's MIT License. Third-party work remains under its original
license.
