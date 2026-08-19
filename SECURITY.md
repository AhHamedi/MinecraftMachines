# Security policy

## Supported versions

Minecraft Machines is an experimental prototype. Only the current default branch receives security fixes.

## Reporting a vulnerability

Do not open a public issue for a vulnerability that could expose a Minecraft server, local files, credentials, or network services. Use GitHub's private **Report a vulnerability** form in the repository's Security tab.

Include the affected commit, reproduction steps, impact, and any suggested mitigation. Use the public issue tracker for non-sensitive correctness problems.

## Operational safety

The training bridge is intended for loopback use and can pause physics for lockstep operation. Do not expose it or RCON directly to untrusted networks. Use a disposable development world, keep backups, and stop training tasks before removing the mod.
