# Contributing to Minecraft Machines

Minecraft Machines is an early-stage, physics-sensitive prototype. Small, focused contributions are easiest to review.

## Development setup

Use Java 21 and clone the pinned submodule:

```bash
git clone --recursive https://github.com/AhHamedi/MinecraftMachines.git
cd MinecraftMachines
```

If needed, run `git submodule update --init --recursive`.

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

## Guidelines

- Keep changes scoped to one problem.
- Add or update tests when behavior changes.
- Preserve compatibility checks for observation, action, policy, and checkpoint formats.
- Do not modify `vendor/Simulated-Project` directly. Update its pinned revision in a separate change.
- Treat training output and visual replays as diagnostics, not proof of general behavior.
- Describe manual testing clearly when a change depends on live Minecraft physics.

By contributing, you agree that your contribution is licensed under the repository's MIT License.
