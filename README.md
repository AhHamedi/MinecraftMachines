# Minecraft Machines

A Minecraft mod that adds an enemy inspired by the Leaper from ARC Raiders.
It walks and jumps using Sable physics, patrols its surroundings, investigates
sounds and hunts players it spots. Its charged shockwave throws players and
breaks nearby blocks.

![The Leaper charging its attack](docs/media/leaper-demo.gif)

[Movement](docs/media/leaper-movement.mp4) · [Attack](docs/media/leaper-attack.mp4)

## Try it

In development for Minecraft 1.21.1 and NeoForge. Requires Java 21 and Python 3.

```sh
git clone --recursive https://github.com/AhHamedi/MinecraftMachines.git
cd MinecraftMachines
tools/run-client.sh --preview
```

Enable cheats in your world, then use:

| Command | Action |
| --- | --- |
| `/mm leaper spawn` | Spawn a Leaper that patrols the area. |
| `/mm leaper hunt` | Start hunting you. |
| `/mm leaper stick` | Get the targeting stick. |
| `/mm leaper stop` | Stop movement and hunting. |
| `/mm leaper remove` | Remove the Leaper. |
| `/mm leaper scale 1.5` | Make it larger; use `scale 1` to restore its size. |

Right-click the top of a block with the stick to choose a destination.
Right-click empty air to start a hunt; Shift-right-click stops movement.

Leapers are currently spawned through commands and removed when their owner
disconnects. Attacks damage terrain, and an attack already charging still fires
after Stop.

## Development

Build with `./gradlew :minecraft_machines:neoforge:build`.
The JAR is written to `minecraft_machines/neoforge/build/libs/`.
See [CONTRIBUTING.md](CONTRIBUTING.md) for tests and development setup.

Original code and assets by Ahmed Hamed, licensed under [MIT](LICENSE.md).
Dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
