# Minecraft Machines

[![CI](https://github.com/AhHamedi/MinecraftMachines/actions/workflows/ci.yml/badge.svg)](https://github.com/AhHamedi/MinecraftMachines/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE.md)

Minecraft Machines is an experimental Minecraft 1.21.1 mod for building and controlling physically simulated machines. The current prototype focuses on a two-servo robot called the Duopod.

![Duopod moving in a Minecraft test world](docs/media/duopod-demo.gif)

The project is under active development. APIs, commands, machine layouts, and training behavior may change without notice. There are no supported binary releases yet; build from source if you want to try it.

## What is included

- A bounded robotic servo joint with angle, speed, torque, and telemetry controls.
- A physical Duopod assembled from Create, Simulated, Aeronautics, and Sable components.
- An in-process Java cross-entropy-method trainer.
- A loopback Python Gymnasium bridge with Stable-Baselines3 tooling.
- Deterministic unit tests, NeoForge GameTests, and a controlled walk-forward benchmark.

The demo shows one development run in a controlled test world. It is not a claim that the controller generalizes to arbitrary terrain, worlds, or Minecraft versions.

## Requirements

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.228
- Create 6.0.10
- Sable 2.x
- Simulated 1.3.0
- Create Aeronautics 1.3.0

The repository pins the Simulated/Aeronautics source tree as a Git submodule.

## Build from source

```bash
git clone --recursive https://github.com/AhHamedi/MinecraftMachines.git
cd MinecraftMachines
./gradlew :minecraft_machines:neoforge:build
```

The development JAR is written to `minecraft_machines/neoforge/build/libs/`.

If the submodule was not cloned, initialize it with:

```bash
git submodule update --init --recursive
```

## Run the development client

```bash
./gradlew :minecraft_machines:neoforge:runClient
```

Commands are registered under `/mm` with `/minecraft_machines` as an alias. Useful entry points include:

```text
/mm spawn_servo_test
/mm spawn_worm
/mm train status
/mm train duopod cem status
/mm train bridge status
```

The complete command tree lives in `MinecraftMachinesCommands.java` and is exposed through Minecraft's command suggestions.

## Test

Run the Java and Python suites:

```bash
./gradlew :minecraft_machines:common:test :minecraft_machines:neoforge:compileJava
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
```

Run the stateful NeoForge integration tests separately:

```bash
./gradlew :minecraft_machines:neoforge:runGameTest
```

## Repository layout

```text
minecraft_machines/common    Shared blocks, control, training, and tests
minecraft_machines/neoforge NeoForge integration, commands, spawners, and GameTests
training/python             Gymnasium client and training utilities
vendor/Simulated-Project    Pinned upstream physics source
```

See [training/python/README.md](training/python/README.md) for bridge setup, PPO training, evaluation, and live-test commands.

## Contributing

Contributions and bug reports are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request and use [SECURITY.md](SECURITY.md) for private vulnerability reports.

Minecraft Machines is licensed under the [MIT License](LICENSE.md). The pinned [`Creators-of-Aeronautics/Simulated-Project`](https://github.com/Creators-of-Aeronautics/Simulated-Project) submodule retains its own license.
