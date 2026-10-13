# Physical Leaper scaling

With cheats/operator permission and your own spawned Leaper:

```text
/mm leaper scale          # Show current size
/mm leaper scale 1.5      # 50% larger in each dimension
/mm leaper scale 2        # Twice as tall, wide and long
/mm leaper scale 1        # Restore normal size
```

Sizes are absolute. Repeating `scale 2` leaves the Leaper at 2×. The supported sizes are **1, 1.5 and 2**; other values are rejected. These are authored physical variants, not a visual transform.

Resize immediately, including while moving, airborne, obstructed or charging an attack. No stop or settled footing is required. The body rebuilds into its neutral pose; a committed burst keeps its original timing and explosion size. Subsequent attacks use the new size.

## What scales

- All 13 physical bodies, their visible meshes, collision solids, joint anchors and foot positions.
- Gait terrain queries and world-space reach. The trained gait still operates in its original model coordinates; conversions occur at its physics and terrain boundaries.
- Pathfinding stance samples, chassis clearance, foot-pad checks, leap collision envelopes and optical origin.
- Angular actuator capacity and foot grips. Larger bodies request higher contact resolution: six substeps at 1.5× and eight at 2×, respecting a higher existing setting. The shared physics lease restores the prior setting when no Leapers need it.
- Attack trigger, launch and block-destruction radii, explosion power and rendered shockwave dimensions. Attack timing, player damage and launch velocity retain their existing tuning.
- The player-damage radius scales too; the damage amount retains its existing distance-falloff tuning.

Native mass and inertia are rebuilt from the actual Minecraft body cells. Collision volume is exactly 3.375× at size 1.5 and 8× at size 2; Minecraft's cell-based mass need not equal those ratios.

## Replacement and state

The command builds the replacement using empty temporary assembly cells away from destination obstructions, without overwriting terrain. It prepares the new controller before changing ownership and removes the old body after the replacement succeeds. Destination clearance, motion and footing do not prevent resizing. An enlarged body can overlap nearby terrain and the physics controller handles the resulting contacts.

Apex remains enabled. Behaviour memory, patrol home, eye mode, pace, paused/manual mode, attack cooldown and attack settings remain on the same live controller record. A destination is replanned for the larger stance; the old route's clearance is not reused.

The scaling implementation adds a block registration and updates the shockwave packet. **Restart Minecraft/server with the rebuilt mod**; resource reload or class hot-swap alone cannot install this change. Multiplayer clients and the server need the same build.

## Verification

`python3 tools/verify_leaper_scales.py` checks generated collision volumes, bounds, cell-local mesh coordinates and eye material placement. The `predator-scale` native GameTest exercises actual command resizing through 1 → 1.5 → 2 → 1, movement, joint stability, obstructed and unsupported resizing, invalid sizes, attack commitment, preserved state and a giant's attack on a ground-level player.

Run the native fixture with the same Apex/high-step preview settings as the gameplay launchers. The existing behaviour and hunting verification scripts cover normal-size regressions.

The [13 October 2026 scaling report](evidence/leaper-scale-20261013.json)
records a fresh resource and native command-resizing verification run.
