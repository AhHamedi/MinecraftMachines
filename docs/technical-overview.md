# Minecraft Machines — Technical Overview

Prepared on 13 October 2026 as a standalone context brief for describing the project to an employer or providing background to another AI.

## Project summary

Minecraft Machines is an experimental robotics and autonomous-agent project implemented as a Minecraft mod. It uses Minecraft as an interactive environment for assembling articulated machines, optimizing their movement, and testing how they navigate and react to a changing world.

The project combines rigid-body simulation, joint actuation, inverse kinematics, feedback control, simulation-driven optimization, perception, behavior state machines and terrain-aware planning. It also includes a synchronized Python interface for reinforcement-learning experiments.

The project implements the machines and their control, training and gameplay systems. The underlying physics solver and Minecraft platform come from external dependencies.

## Technology and architecture

- **Java 21:** machine construction, controllers, perception, planning, training environments and gameplay integration.
- **Minecraft 1.21.1 and NeoForge:** game integration, server events, commands, blocks, networking and native integration tests.
- **Sable:** external rigid-body physics, constraints, joint motors and collision handling.
- **Create, Simulated and Create Aeronautics:** mechanical and physical assembly infrastructure, particularly for the earlier servo and Duopod prototypes. Their code and ownership are separate from Minecraft Machines.
- **Gradle:** builds, dependency management and development/test launch configurations.
- **Python, NumPy, Gymnasium, Stable-Baselines3 and PyTorch:** environment wrappers, PPO training and evaluation tooling.
- **JUnit, NeoForge GameTests and GitHub Actions:** automated verification and continuous integration.

The repository separates shared control and training logic from NeoForge-specific integration, with a separate Python training package. The external Simulated/Create Aeronautics source is pinned as a Git submodule.

## How the runtime works

The control loop is:

```text
Observe world and physical body state
    → choose behavior and destination
    → plan a supported route
    → generate foot trajectories and joint targets
    → apply motor commands and bounded physics assistance
    → advance the physics simulation
    → measure the resulting motion and repeat
```

The terrain Leaper controller runs at 20 Hz, with four native physics substeps per game tick, equivalent to 80 Hz physics integration. Planning work is spread over server ticks to limit disruption to gameplay. Earlier machines have their own controller update schedules.

### Physical machine construction

The project progressed through several machine designs:

| Machine | Physical structure | Role |
| --- | --- | --- |
| Duopod | Chassis and two limbs, driven through two custom servo channels | Early actuator, mechanical-integration and training prototype |
| Quadruped | Nine rigid bodies and eight directly actuated hip/knee joints | Trained walking and point-navigation platform |
| Leaper | Thirteen rigid bodies and twelve actuated joints | More articulated terrain-navigation and autonomous-behavior platform |

Each Leaper leg has a hip swivel, shoulder and knee. The original quadruped has a chassis free to translate and rotate in all six degrees of freedom. Motor targets and forces are bounded, so gravity, contact, collisions and imperfect joint tracking affect movement.

The custom servo abstraction supports target angles, target slew limits, stiffness, damping, torque ceilings and telemetry. The later quadruped and Leaper rigs actuate native Sable joints directly rather than relying on the original Create drivetrain.

### Gait generation and inverse kinematics

Controllers generate periodic Cartesian foot trajectories. During stance, a foot supports the body; during swing, it lifts, advances and returns to support. Inverse kinematics converts desired foot positions into joint angles.

Measured heading, body attitude and motion provide feedback. Terrain-aware control adjusts footholds, body pose and swing clearance based on collision geometry and available support. Joint targets have movement limits, and physical motors must track them under load.

The current Apex Leaper adds bounded traction impulses, suspension, steering and stabilization. These forces pass through the native physics system. Apex is therefore a hybrid of an optimized articulated gait and engineered gameplay assistance. Its performance should not be described as entirely unassisted robotic locomotion or entirely learned joint coordination.

### Simulation-driven optimization

The successful deployed walking controllers use the **cross-entropy method (CEM)** to optimize compact parameterized controllers.

The training process:

1. Sample candidate parameter vectors from a probability distribution.
2. Instantiate physical machines in Minecraft and run native simulation episodes.
3. Score measured movement, stability and tracking.
4. Retain elite candidates.
5. Update the distribution's mean and spread, then repeat.

The original quadruped optimizes ten parameters, including stride, foot clearance, cycle period, stance duration, standing height, leg phases and pitch/roll feedback gains. Its recorded training used sixteen candidates per generation, four elites and forty generations.

Fitness rewards useful progress, penalizes drift and heading error, and rejects falls or invalid physical states. Leaper experiments also optimize navigation, terrain-gait and actuator settings, including braking and obstacle-clearance timing.

This is derivative-free policy search using the actual physics engine. Sensing, planning, trajectory generation and inverse kinematics are engineered components. The deployed controller is not an end-to-end neural reinforcement-learning policy.

### Perception and autonomous behavior

The Leaper uses a server-owned state machine with **Patrolling, Alert and Hunting** states.

Vision uses raycasts from the physical sensor location, with detection affected by occlusion, lighting, crouching and invisibility. Hearing searches connected, loaded, collision-free air spaces and produces a coarse location clue. Hearing alone does not confirm a hunt.

When visual contact is lost, the agent follows frozen last-seen evidence rather than continuously accessing the hidden target's coordinates. It can investigate, give up and return to its patrol area. Moving-target prediction is bounded and handles stops, reversals and teleports.

### Terrain planning, leaps and recovery

The Leaper uses layered **A\*** planning. Different walkable heights at the same horizontal position are distinct states, allowing the planner to distinguish a tunnel floor from the surface above it.

Route validation considers body clearance, foothold support, elevation transitions and swept movement. A direct-corridor check can avoid unnecessary graph expansion on clear terrain. Searches are bounded and resumable; an existing usable route can remain active while a replacement is planned. New routes reconnect to the robot's actual moving position.

Ballistic leaps solve candidate trajectories under gravity and drag, validate departure, flight and landing clearance, then apply a native launch impulse. Landing returns through support and stability checks to ground control.

Recovery includes validated retreats, short recovery leaps and a charged shockwave that can clear actual nearby obstructions. Terrain changes invalidate routes and trigger replanning. Shockwave gameplay state is controlled by the server, with client-side visual and audio effects.

### Python reinforcement-learning interface

A local Java–Python bridge exposes observations, actions, rewards, resets and episode outcomes through Gymnasium-compatible interfaces. It supports single-environment and Stable-Baselines3 vector-environment wrappers, schema compatibility checks, checkpoints and evaluation tooling.

The documented Duopod interface has 45 observation values and two actions corresponding to left and right servo targets. Observations include requested motion, measured motion, orientation, support information and servo telemetry.

The bridge's key synchronization feature is **lockstep simulation**: it pauses the dimension's physics while Python chooses an action, advances for the advertised number of control ticks, then pauses before returning the next observation. Python inference speed therefore does not change action duration. This pause applies to the entire dimension, so training sessions need an appropriate isolated setup.

The PPO training pipeline is implemented, but the repository does not establish a meaningful successful locomotion model under the repaired current bridge contract. The successful quadruped and Leaper controllers described above use CEM. The policy-export utility writes metadata; native Java neural inference remains a separate requirement.

## Recorded results and their scope

These are existing experiment records, not simulations rerun to prepare this brief. Results belong to their recorded controller versions and fixtures.

| Experiment | Recorded result | Scope |
| --- | --- | --- |
| Original quadruped paired gait evaluation | 2.59× mean forward-distance improvement over the initial gait | Six paired one-minute trials across three stride settings, with and without disturbance |
| Independent quadruped replay | 274.07 blocks during 180 seconds of walking; 0.068 blocks final lateral error | Fresh assembly and world, with stopping, restarting and a stronger disturbance; 204 simulated seconds including settling and stop time |
| Historical Classic/Apex comparison | 25.13 versus 9.72 blocks/s mean cruise speed, or 2.58× | Same 110-block course, three headings; cruise measured over blocks 20–80; no committed leaps |
| Same Classic/Apex comparison | 5.12 versus 15.75 seconds to enter destination range | Recorded September 2026 controller comparison |

The original quadruped's additional qualification passed seven of ten conditions. Blue ice and two half-block obstacle conditions failed. Leaper traversal remains constrained by body size, available support, headroom, loaded terrain and planning bounds. These results do not establish universal traversal across arbitrary worlds or real-world robotic transfer.

## Engineering validation

The project combines deterministic unit tests, native Minecraft GameTests, Python protocol/environment tests and live-game recordings. Verification includes destination arrival, stopping, disturbance recovery, joint tracking, assembly integrity and behavior transitions.

Experiment records preserve controller parameters, implementation fingerprints, physics settings, trajectories and failures. Some packaging tools check that models, geometry and reports agree before promotion. Historical test counts and benchmarks qualify the recorded build, not every later development change.

## Employer-facing description

> I developed Minecraft Machines, a Java project that uses Minecraft as an interactive robotics simulation environment. It combines articulated physical machines, inverse-kinematics controllers, terrain-aware path planning and perception-driven behavior. I optimized gait parameters through cross-entropy search using rollouts in the actual physics engine, and built repeatable evaluation with baselines, disturbances and recorded failure cases. I also implemented a synchronized Python/Gymnasium interface for reinforcement-learning experiments. The main engineering challenge was making planning, control and physics work together under real-time constraints while measuring improvements reliably.

The project demonstrates control and optimization, real-time software architecture, Java–Python integration, algorithmic planning, debugging of physical systems and experimental validation.

When using this brief to generate a CV or interview response, preserve the distinction between project code and external physics libraries, CEM-trained controllers and the separate PPO interface, and original unassisted quadruped walking and assisted Apex behavior. Attribute numerical results to their specific recorded experiments. The repository identifies Ahmed Hamed as the author and maintainer of Minecraft Machines.

## Repository evidence

The brief is self-contained. These relative links provide supporting records when read inside the repository:

- [Project overview and dependency ownership](../README.md)
- [Quadruped training, results and limitations](quadruped.md)
- [Leaper articulation](leaper.md)
- [Leaper gait and navigation training](leaper-navigation.md)
- [Historical terrain-controller engineering record](leaper-predator.md)
- [Swing-clearance optimization and qualification](leaper-clearance.md)
- [Apex assistance and paired benchmark](leaper-apex.md)
- [Perception, behavior and recovery](leaper-behaviour.md)
- [Moving-target pursuit](leaper-hunting.md)
- [Python training bridge and PPO status](../training/python/README.md)

Some guides describe historical versions. For current runtime behavior, prefer the implementation over an older guide; for performance claims, prefer the report and source fingerprint for the specific experiment.
