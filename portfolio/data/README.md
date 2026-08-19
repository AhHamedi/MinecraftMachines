# Duopod locomotion evidence catalog

This directory is the human-facing catalog for the Minecraft Machines walk-forward evidence. It intentionally keeps rejected runs, accepted-but-invalidated results, and the superseded one-candidate v6 recertification. A strong metric never overwrites a causal failure.

Current publication state: **`ACCEPTED` for the recorded world and fixed protocol.**

The canonical release claim comes only from the fresh seeded 12-generation evidence bundle under [`artifacts/duopod_walk_forward_v6`](../../artifacts/duopod_walk_forward_v6/README.md). The copies below are byte-exact presentation copies; the source bundle and its verifier remain authoritative.

## Final fresh-search v6 certification

| Evidence | Public copy | State |
|---|---|---|
| Selected checkpoint | [duopod_walk_forward_checkpoint_final_v6.json](duopod_walk_forward_checkpoint_final_v6.json) | checkpoint v4 · fitness v6 · generation 5 |
| Benchmark run 01 | [JSON](walk_forward_benchmark_final_v6_run_01.json) · [CSV](walk_forward_benchmark_final_v6_run_01.csv) · [manifest](walk_forward_benchmark_final_v6_run_01_manifest.json) | accepted · 8/8 · hashes match |
| Benchmark run 02 | [JSON](walk_forward_benchmark_final_v6_run_02.json) · [CSV](walk_forward_benchmark_final_v6_run_02.csv) · [manifest](walk_forward_benchmark_final_v6_run_02_manifest.json) | accepted · 8/8 · hashes match |

| Provenance | Value |
|---|---|
| Optimizer seed | `2026080301` |
| Checkpoint run / generation | `7bf2b216-3414-4030-88a8-3e6316823358` / `5` |
| Population / elites / generations | `32 / 8 / 12` |
| Fitness contract | `minecraft_machines:duopod_cem_fitness_v6` |
| Arena / slot layout | `minecraft_machines:temporary_flat_duopod_lane_groups_v2` / `minecraft_machines:isolated_three_lane_groups_v1` |
| World / seed / dimension | `world` / `-3368720904110701394` / `minecraft:overworld` |
| Genome SHA-256 | `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f` |
| Run 01 benchmark ID | `c647d862-f7e4-4d34-8b69-2fcd0591edaf` |
| Run 01 JSON / CSV SHA-256 | `4b5b2d4459ea8b81b15a863635a41b88080abffd879205379c9611643365d69e` / `15d1c0bcc9442ff47316ec0d916ae8c74677fec1958e4ebc1c7763097a3675d3` |
| Run 02 benchmark ID | `6b5506a5-e24b-4df2-b972-49cbe6ed1a51` |
| Run 02 JSON / CSV SHA-256 | `71ffc0583cb90ea4bf10d2700e4e18d4e9ba0ab8614278e977d04491727e380f` / `5a8747d1e4f622c0c8444f290af7b2cd13c79372e38e45a745b3c51f0135dbac` |

Both repeats recorded the same measurements:

| Acceptance measurement | Result | Gate |
|---|---:|---:|
| Learned forward at speed 0.50 | `3.659454` blocks | positive |
| Learned forward at speed 0.80 | `2.941055` blocks | positive |
| Learned forward at speed 1.10 | `2.632053` blocks | positive |
| Learned mean / adjusted margin | `3.077521` / `3.077521` blocks | margin `≥ 0.5` |
| Minimum body-up | `0.608267` | `≥ 0.60` |
| Peak vertical excursion | `2.127411` blocks | `≤ 3.0` |
| Machine failures / arena escapes | `0 / 0` | `0 / 0` |
| Criteria | `8 / 8` | `8 / 8` |

Generation 8 achieved the higher training aggregate (`36.5139`) but failed held-out posture at `0.59605 < 0.60`. The unchanged gate selected generation 5. This is why optimizer fitness is not the publication decision.

## Historical benchmark lineage

| Evidence | Classification | What it established |
|---|---|---|
| [Attempt 01 JSON](walk_forward_benchmark_attempt_01_failed.json) / [CSV](walk_forward_benchmark_attempt_01_failed.csv) | Rejected v1 diagnostic | Positive mean hid backward motion at one required speed. |
| [Attempt 02 JSON](walk_forward_benchmark_attempt_02_failed.json) / [CSV](walk_forward_benchmark_attempt_02_failed.csv) | Rejected v1 diagnostic | Negative learned mean, insufficient margin, and posture failure. |
| [Stage-one v2 run 01](walk_forward_benchmark_stage1_run_01_failed.json) / [run 02](walk_forward_benchmark_stage1_run_02_failed.json) | Rejected repeatability diagnostic | Controlled lanes reproduced the same failure instead of hiding it. |
| [Stage-one v3 run 01](walk_forward_benchmark_stage1_v3_run_01_failed.json) / [run 02](walk_forward_benchmark_stage1_v3_run_02_failed.json) | Rejected stability diagnostic | Large forward displacement still failed minimum body-up. |
| [Per-tick v3 JSON](walk_forward_benchmark_per_tick_candidate_01_failed.json) / [CSV](walk_forward_benchmark_per_tick_candidate_01_failed.csv) / [manifest](walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json) | Rejected sampling audit | The apparent controller fell, escaped, and launched between control-boundary samples. |
| [v5 run 01](walk_forward_benchmark_v5_pre_fix_run_01_accepted.json) / [run 02](walk_forward_benchmark_v5_pre_fix_run_02_accepted.json) | Benchmark-accepted; provenance-invalid | Real benchmark passes could not repair a mismatch between grouped arena construction and contiguous morphology placement. |
| [One-candidate v6 checkpoint](duopod_walk_forward_checkpoint_v6_recertification.json) and [run 01](walk_forward_benchmark_v6_recertification_run_01.json) / [run 02](walk_forward_benchmark_v6_recertification_run_02.json) | Superseded recertification | Re-certified the legacy genome after the topology fix, but did not constitute a fresh optimizer search. |
| Fresh-search v6 final copies above | Certified final evidence | Fresh 12-generation search; generation 5 passed the frozen gate twice with complete, hash-verified provenance. |

The v5 exact genome genuinely passed benchmark IDs `239f85b4-4fee-418c-906f-0ef770d74407` and `4c6b374a-0505-4cb4-a724-fa03229ce5dc`. It remains non-promotable because candidate 25 was trained in local slots 3–5, where the arena's grouped offsets and the spawner's old contiguous offsets diverged. The later one-candidate v6 recertification is preserved with its [run 01 manifest](walk_forward_benchmark_v6_recertification_run_01_manifest.json) and [run 02 manifest](walk_forward_benchmark_v6_recertification_run_02_manifest.json), but the fresh-search bundle supersedes it as the publication result.

## Checkpoint lineage

| Checkpoint | Contract | Disposition |
|---|---|---|
| [Original compatibility checkpoint](duopod_walk_forward_checkpoint.json) | legacy | Superseded early attempt. |
| [Attempt 01](duopod_walk_forward_checkpoint_attempt_01.json) | legacy | Historical alias. |
| [Attempt 02 candidate](duopod_walk_forward_checkpoint_candidate_02.json) | fitness v1 | Rejected. |
| [Stage-one v2](duopod_walk_forward_checkpoint_stage1_v2.json) | fitness v2 | Rejected. |
| [Stage-one v3](duopod_walk_forward_checkpoint_stage1_v3.json) | fitness v3 | Rejected. |
| [Final v3 candidate](duopod_walk_forward_checkpoint_final_v3.json) | fitness v3 | Rejected by per-tick audit. |
| [v5 anchor](duopod_walk_forward_checkpoint_v5_gen2_anchor.json) / [final v5](duopod_walk_forward_checkpoint_final_v5.json) | fitness v5 | Benchmark-accepted, provenance-invalid. |
| [One-candidate v6 recertification](duopod_walk_forward_checkpoint_v6_recertification.json) | fitness v6 | Valid intermediate audit, superseded. |
| [Fresh-search final v6](duopod_walk_forward_checkpoint_final_v6.json) | fitness v6 | Current publication checkpoint. |

Checkpoint format and fitness-contract version are separate. A compatible genome may seed a widened restart, but an incompatible or provenance-incomplete optimizer state must never retain its old fitness rank.

## Integrity and interpretation

The publication model has three layers:

1. UUID-named JSON and CSV artifacts are written and forced as immutable evidence.
2. Compatibility “latest” copies may be written for operators.
3. A manifest is atomically committed last and binds the immutable pair, shared benchmark ID, byte counts, hashes, checkpoint lineage, world, and protocol.

Verify the canonical bundle and the public copies from the repository root:

```bash
python3 artifacts/duopod_walk_forward_v6/verify_evidence.py
python3 tools/verify_publication.py
```

Raw and adjusted displacement answer different questions. Raw mean is always shown. Stability-adjusted credit only prevents a failed, escaped, tipped, or ballistic baseline from setting a misleading positive comparator. It never erases a learned-controller failure.

Two fixed-protocol repeats demonstrate operational repeatability in this world and protocol. They are not a statistical multi-seed robustness study. Both runtime artifacts explicitly record `code_revision: null` because Git history did not exist when the benchmark ran.

Implementation verification consists of **93 common Java tests**, **75 Python tests passed with 7 optional skips**, and **29 NeoForge GameTests**. These establish implementation confidence; only the fixed live benchmark establishes locomotion acceptance.
