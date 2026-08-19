# Duopod Live Inspection Checklist

Last updated: 2026-08-03

This is the operator checklist for the corrected v6 walk-forward evidence path. The active contract is policy `duopod_phase_gait_v2`, fitness `minecraft_machines:duopod_cem_fitness_v6`, checkpoint format v4, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, slot layout `minecraft_machines:isolated_three_lane_groups_v1`, benchmark format v3, and acceptance rule v2. Historical June, v1/v2/v3, and pre-fix v5 artifacts are forensic evidence, not current publication evidence.

Final paired current-contract result: accepted twice from a fresh seeded search. Seed `2026080301`, run `7bf2b216-3414-4030-88a8-3e6316823358`, used population `32`, elites `8`, and `12` generations; generation `5` is the promoted checkpoint. Run 01 is UUID `c647d862-f7e4-4d34-8b69-2fcd0591edaf`; run 02 is UUID `6b5506a5-e24b-4df2-b972-49cbe6ed1a51`. Both pass all eight criteria with zero learned failures and zero learned arena escapes.

## 1. Physical Build And Cleanup

Run:

```mcfunction
/mm duopod spawn
/mm duopod telemetry on
```

Pass signals:

- The machine is low, wide, and symmetric about its forward axis.
- There is one central/base physics body and one independently assembled limb body on each side.
- The left and right Robotic Servo Joints are visible/discoverable and independently controlled.
- Neutral spawn settles without an immediate launch or body separation.
- Telemetry/config reflects ±60-degree targets, 90 degrees/second, stiffness 800, damping 350, and a 75,000 torque cap.

Fail signals:

- A servo, cog, swivel bearing, honey tip, or child limb is missing.
- Both sides visibly move from one shared actuator command.
- The body launches, clips deeply into terrain, or leaves orphaned bodies after removal.

Cleanup:

```mcfunction
/mm duopod remove_nearest
/mm duopod telemetry off
```

## 2. Control Authority

Player-positioned:

```mcfunction
/mm duopod control_sweep
```

Console/headless:

```mcfunction
/mm duopod control_sweep_at <x> <y> <z> <north|south|east|west>
```

Require finite valid stage telemetry, useful positive forward authority, both yaw directions where relevant, and no assembly separation. Record fresh log values. June control-sweep numbers used different actuator/schema semantics and must remain labelled historical.

## 3. Prepare A Controlled Publication Run

Use a dedicated-server area with no block entities in the bounded experiment corridor. Java walk-forward training temporarily replaces that corridor with identical smooth-stone lanes, clears headroom, groups each candidate's three speed lanes together, and inserts one unused lane-width between candidate groups. The shared grouped offset is `(slot + floor(slot / 3)) * spacing`: with spacing 12, slots 0–2 are at right offsets 0/12/24, slots 3–5 are at 48/60/72, and offset 36 is the unused separator. Controlled morphology spawn search must resolve exactly to the prepared center `requested.above(3)`; any nearby relocation is a hard failure. The task retains the arena for the complete run and restores the original block states on close. Preparation rejects block-entity positions and rolls back a partial mutation.

Before mutation, the server durably writes `<world>/minecraft_machines/duopod_arena_recovery/arena_<uuid>.nbt`. Normal restoration removes it only after every original block is restored. On startup, the server recovers outstanding journals; malformed or unrestorable journals remain in place and fail startup/training closed. Keep a world backup: restoration changes blocks in memory but does not explicitly force the affected chunks to disk before deleting the forced journal, so abrupt host power loss in that narrow P2 interval is not covered by an absolute durability guarantee.

The trainer acquires transient runtime chunk tickets for the complete corridor, waits for position-ticking readiness, and retains the lease across all batches. It does not create persistent `/forceload` saved state.

Start the full fresh protocol from console/headless operation:

```mcfunction
/mm train duopod cem start_fresh_at <x> <y> <z> <north|south|east|west> 32 12 800 4 3 12 max_slots 24 walk_forward seed <seed>
/mm train duopod cem status
```

This means population 32, eight derived elites, 12 generations, 800 episode ticks, four ticks per action, all three speed scenarios, 12-block lane spacing, and 24 concurrent slots. It evaluates eight isolated three-lane candidate groups per batch and matches the benchmark's 200-control-step horizon. `start_fresh_at` bypasses the in-memory checkpoint but deliberately leaves checkpoint and backup files unchanged.

Do not substitute the argument-free `start_fresh`; that command uses the default broad-curriculum config. Do not use `start_at` for a new v6 search because it may initialize from a compatible loaded artifact. If the purpose is to certify an explicitly selected legacy genome, record that as certification and do not describe the resulting one-candidate checkpoint as fresh optimizer convergence.

## 4. Inspect Training, Then Save Deliberately

Walk-forward candidates at the same generation/speed receive common scenario seeds and identical isolated lane-group topology. Generation zero also includes deterministic conservative gait probes. Fitness v6 samples pose extrema every physics tick while an action is held and fails closed on missing/non-finite body-up data. Watch the per-slot log fields:

```text
denseReturn
selectionReturn
postWarmupForward
postWarmupLateral
minimumBodyUp
peakVerticalExcursion
arenaEscape
gateSuccess
terminalFitnessAdjustment
```

The gate success definition is exactly:

```text
no machine failure
&& !arenaEscape
&& minimumBodyUp >= 0.60
&& peakVerticalExcursion <= 3.0
&& postWarmupForward > 0
```

Unstable positive displacement earns no positive terminal-forward credit. Selection also penalizes upright shortfall/failure and gives explicit pressure to the worst speed. Reject a run that merely raises dense reward while post-warmup displacement, per-tick posture/vertical extrema, escape, or speed coverage remain poor.

Checkpoint intentionally:

```mcfunction
/mm train duopod cem save stage1
/mm train duopod cem save
```

An exactly compatible continuation uses the same protocol with `start_at`:

```mcfunction
/mm train duopod cem start_at <x> <y> <z> <north|south|east|west> 32 12 800 4 3 12 max_slots 24 walk_forward seed <seed>
```

Exact optimizer resume requires current policy/fitness identity and matching episode ticks, control ticks, scenario count, spacing, warmup, and target protocol. Publication eligibility separately validates the frozen current arena and slot-layout identity. An old/unknown fitness contract restarts around the genome with widened exploration instead of preserving an incomparable generation/fitness ranking.

Current checkpoint saves use `minecraft_machines_duopod_cem_checkpoint_v4`. They freeze a deep copy of the training config, including arena and slot-layout identity, so a later save cannot relabel an older candidate with current settings. Saves are forced, parsed, and atomically promoted. The previous valid primary is retained as `<checkpoint>.bak`; load automatically recovers that backup if the primary is missing or corrupt. A backup recovery is explicit in the server log and must be recorded in run provenance. Compatible v3/v2/v1 files may be read as restart material, but loading or resaving them does not make their old fitness/provenance current.

Visual replay is diagnostic only:

```mcfunction
/mm train duopod cem replay_best
```

Replay commands neutral for the checkpoint's recorded warmup ticks, captures a fresh post-warmup handoff observation, then uses the stored control cadence, gait frequency, and five-step action ramp. Visible motion in replay is not acceptance.

## 5. Run The Paired Benchmark V3

The active checkpoint must use curriculum `walk_forward`, policy `duopod_phase_gait_v2`, the current schema hashes, fitness `minecraft_machines:duopod_cem_fitness_v6`, training arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, and slot layout `minecraft_machines:isolated_three_lane_groups_v1`. Publication eligibility is exact: spacing 12, control interval 4, warmup 20, horizon 200, and exactly three scenarios. A different explicit `maxControlSteps` value is rejected rather than silently producing a nonstandard publication artifact.

Player-positioned:

```mcfunction
/mm train duopod cem benchmark_walk_forward 200
```

Console/headless:

```mcfunction
/mm train duopod cem benchmark_walk_forward_at <x> <y> <z> <north|south|east|west> 200
```

Benchmark v3 prepares one three-speed lane group and runs learned, neutral, and scripted controllers as three sequential batches over those same lanes. It destroys one controller environment before spawning the next. Artifact writing fails if same-speed records do not reuse the same episode id, seed, physical spawn origin, and post-warmup baseline within `1e-6`, or if pose sampling does not cover every held-action physics tick.

Each run creates a UUID-addressed immutable pair plus compatibility files and a latest commit manifest:

```text
<world>/minecraft_machines/duopod_cem_walk_forward_benchmark_<benchmark_id>.json
<world>/minecraft_machines/duopod_cem_walk_forward_benchmark_<benchmark_id>.csv
<world>/minecraft_machines/duopod_cem_walk_forward_benchmark_latest.json
<world>/minecraft_machines/duopod_cem_walk_forward_benchmark_latest.csv
<world>/minecraft_machines/duopod_cem_walk_forward_benchmark_latest_manifest.json
```

Archive the immutable UUID pair and the manifest. Require `commit_complete=true`; one matching `benchmark_id` in JSON, every CSV row, and the manifest; and matching SHA-256/byte counts for both files. JSON and manifest are canonical UTF-8 with their trailing LF included in the hashes. Treat the manifest as the commit marker because it is atomically written only after the immutable and compatibility pairs. Do not infer completeness from `latest.json` alone.

Require:

- `format = minecraft_machines_duopod_walk_forward_benchmark_v3` and `format_version = 3`;
- `acceptance.rule = post_warmup_stability_adjusted_anti_ballistic_v2` and `rule_version = 2`;
- `protocol.arena_id = minecraft_machines:temporary_flat_duopod_lane_groups_v2`;
- `protocol.slot_layout_id = minecraft_machines:isolated_three_lane_groups_v1`;
- `protocol.slot_spacing_blocks = 12`, `control_ticks = 4`, `spawn_warmup_ticks = 20`, and `maximum_control_steps = 200`;
- `policy.checkpoint_fitness_contract = minecraft_machines:duopod_cem_fitness_v6`;
- world, arena, lane, seed, requested-origin, actual-spawn-origin, policy, and checkpoint provenance;
- exact nine-episode finite coverage;
- each episode's `physics_tick_pose_samples = control_steps * control_ticks`;
- learned forward displacement positive at 0.50, 0.80, and 1.10 blocks/second;
- zero learned machine failures;
- zero learned arena escapes;
- learned minimum body-up at least 0.60;
- learned peak post-warmup vertical excursion no greater than 3.0 blocks;
- learned mean forward displacement at least 0.50 blocks above the stronger stability-adjusted comparison baseline;
- `acceptance.accepted = true` with an empty `failed_criteria` array.

The v2 acceptance comparator caps positive displacement from a baseline episode that fails, escapes, drops below the posture gate, or exceeds the anti-ballistic vertical gate at zero, while preserving the raw measurement. Raw baseline displacement and controller summaries remain in the artifact; do not replace or hide them.

Run the benchmark a second time from the same origin/direction and retain its distinct UUID pair and manifest. The paired provenance and controller means should repeat closely. Two deterministic repeats improve operational confidence, but one episode per controller/speed cell is still not a statistical robustness claim.

The tracked final pair is in the portable [fresh-search evidence bundle](../artifacts/duopod_walk_forward_v6/README.md):

- [run 01 JSON](../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.json), [CSV](../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.csv), and [manifest](../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_latest_manifest.json): learned mean/margin `+3.0775210`, minimum body-up `0.6082670`, peak vertical excursion `2.1274109`, UUID `c647d862-f7e4-4d34-8b69-2fcd0591edaf`; JSON SHA-256 `4b5b2d4459ea8b81b15a863635a41b88080abffd879205379c9611643365d69e`, CSV SHA-256 `15d1c0bcc9442ff47316ec0d916ae8c74677fec1958e4ebc1c7763097a3675d3`;
- [run 02 JSON](../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.json), [CSV](../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.csv), and [manifest](../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_latest_manifest.json): learned mean/margin `+3.0775210`, minimum body-up `0.6082670`, peak vertical excursion `2.1274109`, UUID `6b5506a5-e24b-4df2-b972-49cbe6ed1a51`; JSON SHA-256 `71ffc0583cb90ea4bf10d2700e4e18d4e9ba0ab8614278e977d04491727e380f`, CSV SHA-256 `5a8747d1e4f622c0c8444f290af7b2cd13c79372e38e45a745b3c51f0135dbac`.

Both manifests report `commit_complete=true`, both runs pass all eight criteria, and both use genome SHA-256 `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`, checkpoint run `7bf2b216-3414-4030-88a8-3e6316823358`, generation `5`, world seed `-3368720904110701394`, and `minecraft:overworld`. The selected training candidate recorded aggregate `30.296754`, mean `16.582846`, worst `14.855634`, success rate `1.0`, and failure rate `0.0`. Generation 8's higher aggregate `36.5139` did not override the held-out contract: its minimum body-up `0.59605` failed the fixed `0.60` gate. The allowed claim is repeatable straight crawl in this fixed world/protocol; do not generalize it to unseen worlds, seeds, terrain, turning, navigation, or independent-seed optimizer reliability.

## 6. Historical Failures To Preserve

- [Attempt 01](../portfolio/data/walk_forward_benchmark_attempt_01_failed.json) averaged `+0.0710925` learned blocks but was negative at speed 0.80, so it correctly failed.
- [Attempt 02](../portfolio/data/walk_forward_benchmark_attempt_02_failed.json) averaged `-0.4011416` learned blocks, reached only `0.2394109` minimum body-up, and exposed a raw `+10.3321908` scripted mean from three failed/ballistic episodes.
- [V2 stage-1 run 01](../portfolio/data/walk_forward_benchmark_stage1_run_01_failed.json) and its exact repeat used controlled lanes and fitness v2 but failed comparator, machine-failure, and posture criteria.
- [V3 stage-1 run 01](../portfolio/data/walk_forward_benchmark_stage1_v3_run_01_failed.json) and its exact repeat averaged `+6.2062302` learned blocks but failed the `0.60` posture gate at `0.3424959`.
- [Per-tick candidate-01](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.json), its [CSV](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.csv), and [manifest](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json) preserve benchmark UUID `eb0b3949-159a-4ad3-a7ae-92f991977b07`. It averaged `+4.5291456` learned blocks, but per-tick sampling exposed one failure, minimum body-up `-0.0047745`, `5.4926834` blocks of vertical excursion, and one escape.
- [Pre-fix v5 run 01](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_01_accepted.json) and [run 02](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_02_accepted.json) passed their benchmark gate, but UUIDs `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc` are non-promotable. The arena placed candidate groups at grouped offsets while the morphology requested contiguous `slot * spacing` origins. The selected generation-2 candidate occupied slots 3–5, the first triplet where those definitions diverged.

The first two are v1 artifacts. The stage-1 artifacts are v2/v3 protocol history. Per-tick candidate-01 still used fitness v3 and arena `temporary_flat_duopod_lanes_v1`. The v5 benchmark observations were real, but their claimed matched-training-topology provenance was false. V6 corrected the shared offsets and exact-center requirement, treated the genome as an unranked candidate, re-certified it under the corrected topology, and only then published the final pair. Never relabel historical evidence as a current pass.

## 7. Optional Broad-Policy Checks

Only a current v6/v3 `linear_tanh` point-goal checkpoint may use the point-goal evaluator/retargeting tools. A walk-forward phase-gait checkpoint is intentionally rejected.

```mcfunction
/mm train duopod cem evaluate_best
/mm train reward_viz on
/mm train duopod cem replay_best
/mm train duopod cem replay_target 8 4
/mm train duopod cem replay_target 8 -4
/mm train duopod cem validate_target_change
```

Current evaluation must embed `duopod_planar_evaluation_v1`, `duopod_held_out_point_goals_v3`, `summary.evaluation_valid=true`, and the measured geometry/path invariants. Continuity alone is not steering success.

## 8. Final Cleanup

```mcfunction
/mm train reward_viz off
/mm train duopod cem stop
/mm train clear
/mm train bridge stop
/mm duopod telemetry off
```

Final pass signals:

- No owned Duopod bodies, child sublevels, replay bodies, or collision-registry entries remain.
- The temporary lane corridor has its original blocks restored.
- No task-owned runtime chunk ticket or persistent `/forceload` entry remains.
- No bridge session/socket remains active.
- Any temporarily enabled RCON configuration is restored to disabled.
- `<world>/minecraft_machines/duopod_arena_recovery/` contains no outstanding `arena_*.nbt` journal for this run.

If arena restoration reports an error, treat cleanup as failed and inspect the logged coordinate; do not publish the run until the world and runtime ownership are reconciled.

Latest implementation verification is 93 passing common Java tests, 75 passing Python tests with 7 optional skips, and 29 passing NeoForge GameTests. Those checks cover the v6 protocol machinery; the final live benchmark pair above provides current `acceptance.accepted=true` evidence.
