# Leaper pursuit and Super Horrific — September 2026

> Development record. Measurements, source hashes and historical commands below describe the recorded implementation. They are not fresh verification or a supported release. See the [documentation index](index.md) for current guides. Unlinked `artifacts/` and `release/` paths refer to local output omitted from this repository.

This development update keeps the trained base26 gait and adds an engineered sprint profile, continuous posture transitions, cooperative path/arc search, and automatic launch-room recovery. It does not constitute a newly trained neural policy.

## Controls

`/mm leaper pace super_horrific` enables Super Horrific. `/mm leaper pace horrific`, `fast` (Standard), and `careful` select the retained ordinary paces. `/mm leaper stick` gives the target selector; right-click a supported surface, Shift-right-click to stop.

Super Horrific accelerates into the faster stride on clear ground. It reduces speed for sharp turns, rough terrain and the final approach. It normally runs moderate distances and reserves automatic far-distance pounces for 96 blocks, while difficult elevation changes and stalled pursuit can still trigger pounces sooner. Ordinary Horrific keeps its 32-block far-pounce threshold.

## Changes

- Stopping captures bounded 3D foot targets. Unfinished swings lower over 4–7 control ticks with coordinated Cartesian slew. Hold braking blends in; phase freezes once held.
- A stopping decision persists through vertical settling. A sustained grounded departure is required before pursuit restarts. Near-target correction cannot demand a backward radial step. A 2-block stopping radius and 2.5-block settling band remain.
- Crouch/landing/standing transitions preserve target continuity; standing extension returns over 0.8 seconds rather than abruptly launching the body upward.
- Terrain A* and leap collision sweeps preserve work between server ticks. Terrain reads stay on the server thread, with 2 ms cooperative work slices and per-phase search bounds. These are soft time budgets; individual collision/terrain operations, JIT and garbage collection can exceed them.
- If a high-bank pounce lacks launch clearance, the Leaper checks a dry, supported retreat corridor and backs up under leg power while retaining the requested goal. It retries the jump automatically. Unsupported retreat corridors are rejected.
- Super Horrific uses stride 5.4, period 0.48 seconds, power 14 and slew 16 at full drive. Its finite actuator envelope allows up to 20 rad/s target slew and power 16; ordinary paces remain capped at 8/8. Joint angle limits, body masses, gravity and physics tick rate are unchanged. The structural governor remains active. No additional translation impulse or teleport supplies running speed.

## Evidence

Source14: `d0f66e38b93d7c605bbcf366592ae5e29fe080c5e32a4e2a5bc9d2f6f8e88c06`.

`artifacts/leaper-leap/development-14-super-speed.json`: eight native runs, four matched comparisons, standard 87-mass rig, 4 physics substeps and 20 Hz control. Ground cruise averaged 3.9323 blocks/s for Horrific and 12.1525 for Super Horrific (3.0904x). All eight completed with finite state, no joint-limit violation, no added native leap impulses, and maximum Super joint-anchor separation below 0.070 blocks. These are flat-ground cruise results, not a promise of 3x speed through every obstacle or turn.

Recorded source18: `1fd3edaecb11766c4ce9c416ed6e643470f2cff7b0bd8448605f93b0e44e0530`.

`artifacts/leaper-leap/development-18-pursuit.json`: nine native regression cases passed. Nearby Horrific, diagonal Horrific, Careful and Super approaches each entered hold once, never released it, and had no zero-support estimates during the arrival observation window. Maximum upward speed in those nearby cases was 1.20 blocks/s, compared with 6.49 in the earlier straight-approach failure. Small reverse settling displacement remained (up to 0.25 blocks); this is not a claim of perfectly stationary feet.

The 96-block flat/+12-block submissions measured 0.190/0.064 ms; maximum full navigation calls were 2.30/2.89 ms. Across all nine cases, the maximum was 9.86 ms and no call exceeded 50 ms. This excludes fixture setup/reporting and is not a bound on the entire Minecraft server tick.

The close-wall +8 case physically backed away 12.57 blocks after one command, launched once and settled 0.55 blocks from the original goal. No follow-up target or forced body movement was supplied. The open-runup control reached the bank directly.

Super's 64-block point course entered the arrival region 10.9 seconds after submission, without leaping, and finished 1.95 blocks from the point with 115 consecutive stable ticks. Point navigation now ramps translation and cadence together, faces the route direction on clear sprint sections, measures deviation against the traversed route instead of the distant lookahead segment, and disables sprint boost for the final approach. Cruise ratio and start-to-stop trip time are distinct measurements.

`artifacts/leaper-leap/development-18-commands.json`: all eight interaction cases passed, including stop/resume, airborne retargeting, invalid-target preservation, cooldown, changed crouch clearance and stalled-gap recovery.

`artifacts/leaper-leap/development-18-elevation.json`: all 12 elevation courses passed, covering +4/+8/+12 and -4/-8/-16 blocks over 64-block routes, straight and diagonal. Some require recovery or an additional launch.

`artifacts/leaper-leap/development-18-walk.json`: all six walking-only courses passed (flat, one-block stairs and two-block stairs, each straight and angled), with leaping disabled.

The source14 matched cruise benchmark uses the same sprint gait and actuator settings as source18. Source18 adds navigation steering/approach gating; the direct-control benchmark defaults to allowing sprint. Reports deliberately retain `qualified: false`: these are development fixtures, not a promoted release or a general terrain qualification.

The independent critic found a residual settling bounce in the 96-block flat pursuit: a 0.78-block rise with 3.63 blocks/s peak upward speed and nine zero-support-estimate ticks. It still holds the goal and settles, but the near-case hop gate does not apply to this long-distance case. Repeated pursuit restarting is fixed in these fixtures; all arrival bounce is not eliminated.

Super running has intermittent airtime and can rise substantially during a stride. Leap impacts can still rebound; support diagnostics use foot proximity to native collision surfaces rather than native contact manifolds. Airborne route clearance uses an approximate articulation envelope. These controlled tests do not establish universal terrain reliability.

## Development launch

With Java 21 configured, from the repository root:

```sh
./gradlew :minecraft_machines:neoforge:runClient -PleaperMode=predator-preview -PleaperProfile=horrific -PleaperHighStepCandidate=true -PleaperAgile=true -PleaperLeap=true '-PquickPlayWorld=Leaper Elevation Dev Review 20260907'
```

The runtime base26 candidate/settings files are required. The old packaged v10 model/JAR remains preserved; this update has not replaced that packaged release.

## Live review — source18

The clean review world was reopened with the current implementation. `artifacts/leaper-leap/development-18-live-setup.json` records the standard 87-mass rig, original goal and source hash. One command from 6.5 blocks in front of an eight-block bank produced a physical retreat, pounce and arrival 1.85 blocks from the original upper target. A subsequent Super Horrific point request across the plateau arrived 1.88 blocks from its target. The current client log contains both arrival events and no server catch-up warning during these requests.

Recordings preserve actual wall-clock frame timing:

- `artifacts/leaper-leap/review-automatic-wall-backoff.mp4`: retreat, upper-bank launch and arrival. The camera partially occludes the lower legs behind the bank initially.
- `artifacts/leaper-leap/review-super-horrific.mp4`: live sprint, turning and final approach on the plateau; distinct from the isolated cruise benchmark.

At the end of that recorded session, the game was left open in this review world with Super Horrific selected, HUD visible and the targeting stick equipped.
