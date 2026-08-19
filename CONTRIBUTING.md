# Contributing to Minecraft Machines

Minecraft Machines is physics-sensitive research software. Contributions are welcome, but behavior claims must stay coupled to reproducible evidence.

## Development setup

Use Java 21 and clone the repository with its pinned submodule:

```bash
git clone --recursive https://github.com/AhHamedi/MinecraftMachines.git
cd MinecraftMachines
```

If the submodule is missing, run `git submodule update --init --recursive`.

## Before opening a pull request

Run the deterministic checks:

```bash
./gradlew :minecraft_machines:common:test :minecraft_machines:neoforge:compileJava
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
python3 artifacts/duopod_walk_forward_v6/verify_evidence.py
python3 tools/verify_publication.py
```

Changes to assembly, servo behavior, environment lifecycle, training semantics, benchmark logic, or world recovery must also pass:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest
```

## Evidence rules

- Do not describe a training score, replay, screenshot, or unit test as locomotion acceptance.
- Do not rewrite failed or superseded benchmark artifacts. Add a new immutable artifact and classify it.
- Bump schema, fitness-contract, checkpoint, or acceptance-rule versions when semantics become incompatible.
- Reject checkpoints whose morphology, policy, schema hashes, curriculum, or fitness protocol do not match.
- Keep `README.md`, `AI_PROJECT_GUIDE.md`, the acceptance audit, and the public dashboard synchronized with material changes.
- Do not modify `vendor/Simulated-Project` in this repository; update the pinned upstream revision deliberately in a separate change.

## Pull requests

Explain the behavior changed, why it changed, and which verification layers were run. Include new live evidence for new locomotion claims. Keep unrelated changes out of the same pull request.

By contributing, you agree that your contribution is licensed under the repository's MIT License.
