# Security policy

## Supported versions

Minecraft Machines is an experimental research preview. Security fixes are applied to the latest `0.1.x` release and the default branch; older snapshots are not supported.

## Reporting a vulnerability

Please do not open a public issue for a vulnerability that could expose a Minecraft server, local files, credentials, or network services. Use GitHub's private **Report a vulnerability** form in this repository's Security tab.

Include the affected version or commit, reproduction steps, impact, and any suggested mitigation. Reports will be acknowledged as soon as practical and coordinated before public disclosure.

For non-sensitive correctness problems, use the public issue tracker.

## Operational scope

The Python bridge binds to loopback and pauses physics for lockstep operation. Do not expose it or RCON directly to untrusted networks. Use a dedicated world or dimension for training, keep a world backup for publication runs, and review the documented arena-recovery limitations before unattended operation.
