# Leaper controls

Start the current preview with `tools/run-client.sh --preview` from the
repository root. The launcher prepares the versioned controller inputs and
enables high-step, agile movement and leaps. With cheats/operator permission,
run `/mm leaper spawn`, then `/mm leaper stick`.
The Leaper spawns at your position and elevation and begins a blue-eyed patrol within 24 blocks.
`/mm leaper patrol [radius]` resets its patrol area around its current position (8–48 blocks).
See [awareness, eye colours and investigation](leaper-behaviour.md).
Use `/mm leaper scale 1.5` or `/mm leaper scale 2` for physical enlargement, and `/mm leaper scale 1` to restore normal size. Resize immediately, including during movement or an attack; [scaling details](leaper-scaling.md).
Use `/mm leaper remove` before replacing an existing Leaper.

Right-click a block with the stick to set a destination. Right-click empty air to follow you. Shift-right-click stops it.
`/mm leaper hunt` explicitly starts a hunt; `/mm leaper stop` pauses autonomous behaviour and future attacks. A charge already underway still explodes; `/mm leaper remove` disposes it.
Manual destinations and inspection poses also pause behaviour; `/mm leaper patrol` resumes it.
`/mm leaper pace super_horrific` selects maximum running speed.

Apex is the only gameplay version. Both [Run Leaper Apex](../tools/Run%20Leaper%20Apex.command) and the Hunt launcher use it, and existing live rigs are upgraded automatically. The Classic selector has been removed; stale Classic commands are rejected. `/mm leaper status` identifies the active controller. Apex adds bounded traction, braking, steering and suspension, with predictive terrain leaps. It can prepare and launch from one steady supporting foot and keeps assisted turning active through jump preparation and landing. See [Apex behavior and verification](leaper-apex.md).

Hunting samples visible prey every four ticks (0.2 seconds), predicts a bounded intercept, and refreshes routes after 0.6 blocks of target movement. Horrific hunting uses the existing Super Horrific sprint gait where the ground and turning controller permit it; explicit Standard and Careful selections retain their pace. Moving prey does not trigger stationary destination braking. The Leaper eases its drive as it closes in. An identified target can be visually tracked up to 192 blocks away; ordinary acquisition remains limited. Flight positions resolve to supported ground destinations. Lost sight freezes its last seen destination; after twenty seconds it investigates with orange eyes. Unproductive hunts and unsuccessful searches also expire.

Clear ground gets a sampled direct-corridor check before A*. Both preserve work across ticks, using a soft 5 ms planning budget. Background routes reconnect at the body's current progress. Ground pursuit continues while a usable route exists; elevation, blocked routes and stalls can still require a pounce. Actual supporting terrain determines target elevation, avoiding false jumps from player jumping or crouching.

Run `tools/run-client.sh --preview` or, on macOS, `tools/Run Leaper Hunt.command` to open the current development preview, select a world, then spawn and hunt. It uses `-PleaperMode=predator-preview -PleaperProfile=horrific -PleaperHighStepCandidate=true -PleaperAgile=true -PleaperLeap=true` and prepares missing candidate/settings files from `tools/profiles/leaper-preview/`. Existing local experimental inputs are preserved. The ordinary packaged mode is an earlier baseline. See [hunting measurements and reproduction](leaper-hunting.md).

### Upward leap profile

Apex prefers takeoff angles near 70° instead of minimizing flight time. It checks arcs of at least 55° first and can shorten a hop to obtain a higher trajectory. If terrain blocks every steep option, it considers lower collision-clear arcs. Native gravity, drag, landing checks and impulse limits still apply.
