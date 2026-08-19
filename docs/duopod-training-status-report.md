# Duopod Training Status Report

Last updated: 2026-08-03

This is the current evidence ledger for the repaired Duopod walk-forward experiment. It supersedes the June 2026 acceptance narrative. Historical point-goal metrics, obsolete checkpoint hashes, and visual replays are not current locomotion evidence.

## Current status

The controlled pre-final-gate v2 training and benchmark path produced a repeatable diagnostic failure. Two benchmark runs over the same stage-one checkpoint have identical lane provenance, episode metrics, and acceptance fields; only their UTC generation timestamps differ.

The post-warmup fitness-v3 repair then produced a generation-6 checkpoint that looked 3/3 under its old control-boundary training sampler. Hardened per-physics-tick benchmark `eb0b3949-159a-4ad3-a7ae-92f991977b07` rejected it. At requested speed `0.50`, the learned policy travelled far but failed once, escaped once, reached minimum body-up `-0.004774`, and peaked `5.492683` blocks vertically. Its `0.80` and `1.10` episodes passed. The aggregate learned mean and adjusted margin were both `4.529146` blocks, but four safety criteria failed.

Fitness v5 repaired per-tick sampling and generated one exact genome that passed two hardened benchmarks: `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc`. An independent audit nevertheless invalidated their publication provenance. Selected generation-2 candidate 25 occupied local slots 3–5; arena construction inserted the group gap before those lanes, but morphology spawning still requested contiguous offsets. The benchmark measurements are genuine, but the claim that this genome was selected under the prepared isolated-triplet topology is false.

The final protocol is pinned as fitness `minecraft_machines:duopod_cem_fitness_v6`, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, slot layout `minecraft_machines:isolated_three_lane_groups_v1`, checkpoint format `minecraft_machines_duopod_cem_checkpoint_v4`, benchmark format `minecraft_machines_duopod_walk_forward_benchmark_v3`, and acceptance rule `post_warmup_stability_adjusted_anti_ballistic_v2` (rule version 2). Arena and spawner now share one grouped mapping and exact prepared-center spawn. Canonical publication eligibility additionally pins spacing, cadence, warmup, horizon, exactly three scenarios, and environment compatibility.

The current result comes from a fresh, explicitly seeded v6 search rather than the earlier one-candidate certification. Seed `2026080301`, run `7bf2b216-3414-4030-88a8-3e6316823358`, evaluated population `32` with `8` elites for `12` generations. Generation 5 was promoted with aggregate `30.296754`, mean `16.582846`, worst speed `14.855634`, success rate `1.0`, failure rate `0.0`, and genome `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`. Benchmark runs `c647d862-f7e4-4d34-8b69-2fcd0591edaf` and `6b5506a5-e24b-4df2-b972-49cbe6ed1a51` both passed 8/8, and both last-written manifests commit hash-matching immutable JSON/CSV pairs.

Final live evidence status: **`ACCEPTED` for the recorded world and fixed protocol.** Final verification passed with 93 common Java tests, 75 Python tests plus 7 environment-dependent skips, and 29/29 NeoForge GameTests.

## Evidence ledger

| Stage | Policy / contract | Evidence | Outcome |
|---|---|---|---|
| v1 attempt 01 | `duopod_phase_gait_v1`; no pinned fitness contract | [checkpoint](../portfolio/data/duopod_walk_forward_checkpoint_attempt_01.json), [JSON](../portfolio/data/walk_forward_benchmark_attempt_01_failed.json), [CSV](../portfolio/data/walk_forward_benchmark_attempt_01_failed.csv) | Failed positive progress at every speed; learned mean `+0.0711` |
| v1 attempt 02 | `duopod_phase_gait_v1`; `duopod_cem_fitness_v1` | [checkpoint](../portfolio/data/duopod_walk_forward_checkpoint_candidate_02.json), [JSON](../portfolio/data/walk_forward_benchmark_attempt_02_failed.json), [CSV](../portfolio/data/walk_forward_benchmark_attempt_02_failed.csv) | Failed margin, per-speed progress, and uprightness; learned mean `-0.4011` |
| Pre-final-gate v2 stage one | `duopod_phase_gait_v2`; `minecraft_machines:duopod_cem_fitness_v2` | [checkpoint](../portfolio/data/duopod_walk_forward_checkpoint_stage1_v2.json), [run 01](../portfolio/data/walk_forward_benchmark_stage1_run_01_failed.json), [run 02](../portfolio/data/walk_forward_benchmark_stage1_run_02_failed.json) | Repeatable failure: mean `+1.5073`, adjusted margin `-1.1117`, 1 failure, min-up `0.0825` |
| Post-warmup v3 generation 6 | `duopod_phase_gait_v2`; `minecraft_machines:duopod_cem_fitness_v3`; arena lanes v1 | [checkpoint](../portfolio/data/duopod_walk_forward_checkpoint_final_v3.json), hardened [JSON](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.json), [CSV](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.csv), and [manifest](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json) | Rejected at speed `0.50`: mean/margin `+4.529146`, 1 failure, 1 escape, min-up `-0.004774`, peak vertical `5.492683` |
| Fitness-v5 generation 1 | `duopod_phase_gait_v2`; `minecraft_machines:duopod_cem_fitness_v5` | Historical runtime log | Best aggregate `33.0632`, mean `19.4321`, worst `14.5244`, 3/3 gate success, 0 failures; training evidence only |
| Fitness-v5 generation 2 | `duopod_phase_gait_v2`; `minecraft_machines:duopod_cem_fitness_v5`; lane groups v2 as claimed | [checkpoint](../portfolio/data/duopod_walk_forward_checkpoint_final_v5.json), accepted [run 01](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_01_accepted.json) + [manifest](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_01_accepted_manifest.json), accepted [run 02](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_02_accepted.json) + [manifest](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_02_accepted_manifest.json) | Candidate 25 aggregate `48.048369`, failure rate `0`; benchmark-accepted twice but non-promotable because actual slot mapping contradicted grouped-arena provenance |
| Fresh seeded per-tick v6 | `duopod_phase_gait_v2`; `minecraft_machines:duopod_cem_fitness_v6`; arena v2; slot layout v1; checkpoint v4 | [bundle](../artifacts/duopod_walk_forward_v6/README.md), selected [checkpoint](../artifacts/duopod_walk_forward_v6/checkpoint/duopod_cem_fresh_v6_seed2026080301_accepted.json), accepted [run 01](../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.json), accepted [run 02](../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.json) | Fresh 12-generation search; generation 5 accepted twice under canonical v6 provenance; both immutable pairs hash-verified |

The v1, v2, rejected v3, and accepted-but-provenance-invalid v5 files are immutable historical evidence. They should not be overwritten by “latest” output.

## Causal diagnosis

The project did not suffer from one insufficient training duration. Multiple layers weakened the original evidence:

- **Site confounding:** candidates and controllers ran at different arbitrary world positions. Neutral displacement varied by roughly three blocks across attempts, larger than the learned signal.
- **Objective mismatch:** dense training return was not aligned with terminal forward displacement, minimum uprightness, and machine failure used at publication.
- **Compressed speed input:** phase-gait v1 consumed the observation's speed divided by four as though it were the unscaled command.
- **Exploration collapse:** a longer run contracted its distribution while sampling near-identical candidates.
- **Ballistic credit:** raw displacement could make failed launches look competitive.
- **Mixed warmup frames:** the pre-final-gate v2 path restarted stability tracking after warmup but retained a pre-warmup displacement origin.
- **Temporal aliasing:** fitness v3 checked pose extrema at control boundaries, so a failure, launch, or lane escape between samples could be missed.
- **Lane-topology mismatch:** candidates trained on an almost-contiguous 24-lane floor, while the benchmark exposed the edge topology of one three-lane group.
- **Prepared/spawned mapping mismatch:** v5 arena construction grouped lanes, but morphology placement remained contiguous after the first triplet.
- **Insufficient provenance eligibility:** a benchmark-compatible genome could start even when its checkpoint could not prove the exact canonical training environment.

## Repairs in the current v6 contract

- Arena v2 provides fixed, bounded speed-indexed lanes in isolated groups of three, with one unused lane-width between groups; layout ID `minecraft_machines:isolated_three_lane_groups_v1` makes this topology explicit.
- Arena preparation and morphology spawning call the same grouped-offset mapping; each Duopod spawns at the exact prepared lane center.
- Publication-mode walk-forward training requires exactly three lanes/scenarios per candidate.
- Candidate scenarios use common generation-and-speed seeds.
- Learned, neutral, and scripted benchmark batches run sequentially over the same lane origins, IDs, and seeds.
- Per-controller environments are destroyed before the next batch; the arena snapshots and restores the original world region.
- Before any arena mutation, a compressed NBT restoration journal is fsynced and atomically promoted. Unfinished journals are replayed across loaded dimensions at server start; errors retain the journal and keep new arenas fail-closed. One P2 durability limitation remains: after an in-memory restore succeeds, the journal can be removed before affected chunks are explicitly forced to disk, so a power loss in that interval could lose the recovery record.
- A run-scoped chunk lease covers every lane and is released on cleanup.
- JSON/CSV evidence records world seed and dimension, arena geometry and changed-block count, lane manifest, actual spawn origin, episode ID, and seed.
- Pairing equality is validated before an artifact can be written.
- Immutable benchmark-ID JSON/CSV files are SHA-256-bound by an atomically written latest manifest; JSON and manifest use a canonical trailing line feed so tracked copies remain byte-identical and verifiable. Compatibility “latest” files are not a commit marker by themselves.
- Checkpoints use a forced temporary write, atomic promotion, and recovery backup rather than in-place truncation.
- Checkpoint format v4 preserves an immutable `trainingConfig` snapshot, including the exact arena and slot-layout IDs used when the candidate was selected.
- Benchmark start requires canonical fitness, arena, slot layout, spacing, cadence, warmup, horizon, scenario count, and environment compatibility; checkpoint resume enforces spacing/environment compatibility too.
- Checkpoint loading is rejected while training, evaluation, benchmark, replay, or bridge work is active.
- Failed baselines keep their raw metrics, but positive failed displacement is capped at zero for the stability-adjusted comparison.
- The versioned final rule is `post_warmup_stability_adjusted_anti_ballistic_v2`; benchmark output format is `minecraft_machines_duopod_walk_forward_benchmark_v3`.
- Phase-gait v2 corrects speed decoding, uses deterministic structured probes, widens fresh search, keeps a `0.12` standard-deviation floor, and expands a collapsed distribution.
- Population `32` selects `8` elites rather than `2`.
- Walk-forward fitness `minecraft_machines:duopod_cem_fitness_v6` uses post-warmup terminal forward displacement, machine failure, arena escape, minimum body-up, peak vertical excursion, a success bonus, upright-shortfall and vertical-excess penalties, and worst-speed weighting.
- Trainer and benchmark both accumulate body-up, vertical excursion, and arena escape every server physics tick during each held-action interval and require exactly `control_steps * control_ticks` samples.
- Missing or non-finite body-up telemetry fails closed as unsafe.
- Immutable artifact temporaries and their containing directories are forced around atomic promotion before a manifest can commit the pair.

## Controlled v2 diagnostic

The stage-one checkpoint used population `32`, elite count `8`, maximum generations `12`, `800` episode ticks, `4` ticks per control step, `3` scenarios per candidate, spacing `12`, and at most `24` active slots. Its benchmark ran three sequential controller batches over the same three lanes.

| Controller | Raw mean forward | Stability-adjusted comparison mean | Failures | Minimum body-up |
|---|---:|---:|---:|---:|
| Learned | `1.507332` | `1.507332` | `1` | `0.082457` |
| Neutral | `-2.724691` | `-2.724691` | `0` | about `0.5625` |
| Scripted alternating sine | `3.882395` | `2.619032` | `1` | `-0.420972` |

The learned controller failed because it was `1.111700` blocks behind the stronger adjusted baseline, failed one speed, and fell far below the required `0.60` minimum body-up. The repeated artifact reproduced those values exactly.

The same evidence informed the vertical gate: neutral peak excursion was about `2.07` blocks, while the failed scripted launch reached about `7.01` blocks. The final maximum is `3.0` blocks.

## Hardened v3 generation-6 diagnostic

The generation-6 v3 checkpoint is preserved because it demonstrates why control-boundary success was insufficient. Under the old training sampler it appeared stable at all three speeds. The hardened benchmark sampled four physics poses per control step and froze the first terminal state.

| Requested speed | Learned forward | Failure | Escape | Minimum body-up | Peak vertical | Gate |
|---:|---:|---:|---:|---:|---:|---|
| `0.50` | `5.594749` | Yes | Yes | `-0.004774` | `5.492683` | Failed |
| `0.80` | `3.964264` | No | No | `0.667782` | `2.144356` | Passed |
| `1.10` | `4.028423` | No | No | `0.645385` | `2.149689` | Passed |

The benchmark's learned mean and margin were both `4.529146`, and every speed had positive displacement. It was still correctly rejected for one failure, one escape, inadequate minimum body-up, and excessive vertical excursion. Fitness v5 repaired the sampling cadence and changed the arena floor topology; the next audit then found that morphology placement had not adopted the same grouped mapping.

## Accepted-but-non-promotable v5 diagnostic

Both v5 benchmark runs evaluated the same genome, `be80280e365ebb756e2f9041655f11377e94e68674c68ab2db6b701c9abf2d3e`, and passed all eight benchmark criteria.

| Run | Benchmark ID | Learned mean | Minimum body-up | Peak vertical | Failures / escapes |
|---|---|---:|---:|---:|---:|
| 01 | `239f85b4-4fee-418c-906f-0ef770d74407` | `4.145461` | `0.658708` | `2.167221` | `0 / 0` |
| 02 | `4c6b374a-0505-4cb4-a724-fa03229ce5dc` | `4.071139` | `0.655156` | `2.167618` | `0 / 0` |

They remain successful benchmark diagnostics, not final evidence. Candidate 25 was selected from local slots 3–5, precisely where the grouped arena's first gap diverged from the spawner's contiguous requested offsets. Fitness v6 invalidates resume from that lineage and requires a clean retrain with shared prepared/spawned coordinates.

## Final fresh seeded v6 result

Both final paired benchmark repeats satisfied all of these conditions:

1. a canonically eligible checkpoint with fitness v6, arena v2, slot layout v1, checkpoint v4, spacing `12`, control ticks `4`, warmup `20`, horizon `200`, and exactly three speed scenarios;
2. complete finite coverage of learned, neutral, and scripted controllers at speeds `0.50`, `0.80`, and `1.10`;
3. exact paired episode ID, seed, lane, prepared-center spawn, and actual spawn provenance;
4. learned mean forward displacement margin of at least `0.50` blocks over the stronger stability-adjusted baseline;
5. positive learned forward displacement at each speed;
6. zero learned machine failures;
7. learned minimum body-up of at least `0.60`;
8. learned peak vertical excursion no greater than `3.0` blocks;
9. zero learned arena escapes;
10. forced immutable JSON/CSV files whose IDs and hashes match each run's last-written manifest;
11. the same genome, checkpoint run/generation, training snapshot, world seed, and protocol across both repeats.

| Final metric | Run 01 | Run 02 |
|---|---:|---:|
| Benchmark ID | `c647d862-f7e4-4d34-8b69-2fcd0591edaf` | `6b5506a5-e24b-4df2-b972-49cbe6ed1a51` |
| Accepted criteria | `8/8` | `8/8` |
| Learned mean forward | `3.077521006266276` | `3.077521006266276` |
| Adjusted stronger-baseline margin | `3.077521006266276` | `3.077521006266276` |
| Learned per-speed forward (`0.50`, `0.80`, `1.10`) | `3.659454`, `2.941055`, `2.632053` | `3.659454`, `2.941055`, `2.632053` |
| Learned failures | `0` | `0` |
| Learned minimum body-up | `0.6082669526583021` | `0.6082669526583021` |
| Learned peak vertical excursion | `2.127410888671875` | `2.127410888671875` |
| Learned arena escapes | `0` | `0` |
| JSON SHA-256 | `4b5b2d4459ea8b81b15a863635a41b88080abffd879205379c9611643365d69e` | `71ffc0583cb90ea4bf10d2700e4e18d4e9ba0ab8614278e977d04491727e380f` |
| CSV SHA-256 | `15d1c0bcc9442ff47316ec0d916ae8c74677fec1958e4ebc1c7763097a3675d3` | `5a8747d1e4f622c0c8444f290af7b2cd13c79372e38e45a745b3c51f0135dbac` |
| Manifest | `commit_complete: true`; hashes verified | `commit_complete: true`; hashes verified |

Both runs point to fresh-search run `7bf2b216-3414-4030-88a8-3e6316823358`, seed `2026080301`, generation `5`, aggregate fitness `30.296754493224093`, mean `16.582846076420527`, worst score `14.855633667214256`, fitness v6, checkpoint v4, and genome SHA-256 `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f`. Generation 8 had the higher training aggregate `36.5139`, but its held-out minimum body-up was `0.59605`, below the fixed `0.60` threshold, so generation 5 was selected without weakening the contract. Both accepted runs use level `world`, seed `-3368720904110701394`, dimension `minecraft:overworld`, spacing `12`, control cadence `4`, warmup `20`, horizon `200`, and exactly three lanes. Generation-one structured stable-basin probes mean the run is a fresh optimizer search, not a purely uninformed random initialization; a single training seed still does not establish optimizer reliability.

## Commands

Start the declared fresh run from a server-capable command source:

```mcfunction
/mm train duopod cem start_fresh_at 31 80 63 north 32 12 800 4 3 12 max_slots 24 walk_forward seed 2026080301
```

Benchmark the selected current checkpoint:

```mcfunction
/mm train duopod cem benchmark_walk_forward_at 31 80 63 north 200
```

Clean up an interrupted run:

```mcfunction
/mm train duopod cem stop
/mm train clear
/mm train bridge stop
```

## Exact verification snapshot

| Layer | Result |
|---|---:|
| Java common deterministic tests | `93/93` passed |
| Python protocol/evaluation tests | `75` passed, `7` environment-dependent skips |
| NeoForge compile/check | Passed |
| NeoForge GameTests under the final v6 contract | `29/29` passed |
| Controlled stage-one v2 benchmark repeat | Identical episode and acceptance metrics across two runs |
| Hardened v3 generation-6 benchmark | Rejected; immutable artifact `eb0b3949-159a-4ad3-a7ae-92f991977b07` |
| Fitness-v5 exact-genome repeats | Accepted twice; provenance-invalid and non-promotable |
| Fresh seeded fitness-v6 repeats | Accepted twice; both 8/8 manifests and immutable hashes verified |
| Post-run cleanup inspection | No Minecraft/Gradle run process, RCON listener, or recovery journal; RCON disabled with blank password |

The live benchmark and final verification suite are complete.

## Remaining limitations

- The promoted controller is a structured CEM phase gait rather than a PPO result.
- The accepted v5 benchmark behavior is not evidence of valid v6 training provenance.
- The two nine-cell benchmarks demonstrate operational repeatability only in level `world`, seed `-3368720904110701394`, dimension `minecraft:overworld`, under the fixed recorded protocol. They are not statistical or multi-seed robustness evidence.
- Only one morphology and one controlled walk-forward setting are currently portfolio-facing.
- Arbitrary Minecraft terrain and held-out point-goal navigation remain separate future milestones.
- Checkpoint schema compatibility does not guarantee identical physics across upstream versions or hardware.
- Both runtime artifacts report `code_revision: null`; source revision was unavailable to the game runtime.
- The restore journal closes ordinary crash recovery but has a narrow power-loss window after in-memory block restore and before affected chunks are explicitly forced to disk.

See the [engineering case study](engineering-case-study.md) for the full causal narrative and the [acceptance audit](duopod-training-acceptance-audit.md) for the promotion checklist.
