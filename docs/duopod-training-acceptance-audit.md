# Duopod Training Acceptance Audit

Last updated: 2026-08-03

This audit defines the evidence required to promote a Duopod walk-forward result. It applies to benchmark format `minecraft_machines_duopod_walk_forward_benchmark_v3`, fitness contract `minecraft_machines:duopod_cem_fitness_v6`, arena `minecraft_machines:temporary_flat_duopod_lane_groups_v2`, slot layout `minecraft_machines:isolated_three_lane_groups_v1`, checkpoint format `minecraft_machines_duopod_cem_checkpoint_v4`, and acceptance rule `post_warmup_stability_adjusted_anti_ballistic_v2` (rule version 2). June-era point-goal artifacts, v1/v2 artifacts, the hardened v3 rejection, and both accepted-but-provenance-invalid v5 runs are historical diagnostics, not final acceptance evidence.

Current final status: **`ACCEPTED` for the recorded world and fixed protocol.**

## Promotion rule

A result may be called accepted locomotion only when all of the following refer to the same current checkpoint and protocol:

- compatible morphology, observation, action, policy, and fitness-contract identifiers;
- an immutable checkpoint-v4 training snapshot that proves the canonical v6 environment;
- two complete paired benchmark artifacts with finite metrics from the same eligible genome;
- exact lane, episode ID, seed, prepared-center spawn, and actual spawn-origin equality across controllers at each speed;
- complete per-physics-tick pose sampling and the isolated three-lane topology declared by arena v2;
- all code-enforced acceptance criteria set to true;
- forced immutable benchmark-ID JSON/CSV pairs whose SHA-256 hashes and provenance match their atomically committed manifests;
- a passing post-repair deterministic, compile, and GameTest verification record;
- clean shutdown with no owned machine, chunk lease, bridge session, temporary arena, or temporary RCON configuration left behind.

Training fitness, a replay, a screenshot, raw forward displacement, or a favorable single speed is not acceptance evidence.

Even a benchmark acceptance object is insufficient when training provenance is false. The v5 exact genome passed twice, but it remains ineligible because its selected candidate's morphology offsets did not match the grouped arena offsets recorded by the checkpoint.

## Historical evidence classification

| Evidence | Classification | Reason |
|---|---|---|
| [v1 attempt 01](../portfolio/data/walk_forward_benchmark_attempt_01_failed.json) | Immutable failed diagnostic | Learned controller moved backwards at one requested speed |
| [v1 attempt 02](../portfolio/data/walk_forward_benchmark_attempt_02_failed.json) | Immutable failed diagnostic | Learned controller failed margin, per-speed progress, and uprightness; scripted raw motion was ballistic |
| [pre-final-gate v2 run 01](../portfolio/data/walk_forward_benchmark_stage1_run_01_failed.json) | Immutable failed diagnostic | Controlled result failed margin, learned-failure, and upright criteria |
| [pre-final-gate v2 run 02](../portfolio/data/walk_forward_benchmark_stage1_run_02_failed.json) | Immutable repeatability diagnostic | Metrics exactly reproduce run 01, but still use the mixed warmup frame |
| [fitness-v3 generation-6 hardened benchmark](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed.json) and [manifest](../portfolio/data/walk_forward_benchmark_per_tick_candidate_01_failed_manifest.json) | Immutable failed diagnostic | Per-tick sampling rejected the `0.50` episode: 1 failure, 1 escape, min-up `-0.004774`, peak vertical `5.492683` |
| [fitness-v5 run 01](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_01_accepted.json) / [manifest](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_01_accepted_manifest.json) | Immutable benchmark-accepted diagnostic | ID `239f85b4-4fee-418c-906f-0ef770d74407`; genuine pass, invalid training provenance |
| [fitness-v5 run 02](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_02_accepted.json) / [manifest](../portfolio/data/walk_forward_benchmark_v5_pre_fix_run_02_accepted_manifest.json) | Immutable benchmark-accepted repeat | ID `4c6b374a-0505-4cb4-a724-fa03229ce5dc`; same genome, same provenance defect |
| Fresh seeded [v6 checkpoint](../artifacts/duopod_walk_forward_v6/checkpoint/duopod_cem_fresh_v6_seed2026080301_accepted.json), [run 01](../artifacts/duopod_walk_forward_v6/benchmark_run_01/duopod_cem_walk_forward_benchmark_c647d862-f7e4-4d34-8b69-2fcd0591edaf.json), and [run 02](../artifacts/duopod_walk_forward_v6/benchmark_run_02/duopod_cem_walk_forward_benchmark_6b5506a5-e24b-4df2-b972-49cbe6ed1a51.json) | Certified final evidence | Fresh 12-generation search; generation 5 selected; both runs accepted 8/8; both immutable JSON/CSV hashes match committed manifests |
| Fresh-search [generation-8 diagnostic](../artifacts/duopod_walk_forward_v6/diagnostics/highest_aggregate_benchmark_rejected.json) | Immutable failed diagnostic | Highest training aggregate (`36.5139`), but held-out minimum body-up `0.59605` failed the unchanged `0.60` gate |

## Current criteria

| # | Criterion | Current evidence | Status |
|---|---|---|---|
| 1 | Java deterministic logic passes. | Final forced rerun: `93/93` common Java tests passed. | Pass |
| 2 | Python protocol and evaluation logic passes. | Final forced rerun: `75` passed and `7` environment-dependent skips. | Pass |
| 3 | Integrated NeoForge source compiles/checks. | Final NeoForge compile/check completed successfully. | Pass |
| 4 | Post-final-patch GameTests pass. | Final isolated run: `29/29` passed. | Pass |
| 5 | Current interfaces are pinned. | Observation v6 has 45 values; action v3 has two semantic targets; phase-gait v2 and fitness `minecraft_machines:duopod_cem_fitness_v6` are explicit. | Implemented |
| 6 | Walk-forward training uses the controlled topology. | Arena v2 and slot-layout v1 declare isolated, benchmark-shaped three-lane groups plus a run-scoped chunk lease. | Implemented |
| 7 | Candidate comparisons use common scenarios. | Generation-and-speed-index seeds are shared across candidates. | Implemented and unit-tested |
| 8 | Prepared and spawned lane mapping is identical. | Arena and morphology call one grouped-offset function; each morphology uses the exact prepared lane center. | Implemented; final GameTests pass |
| 9 | Each publication candidate uses one benchmark-shaped group. | Walk-forward publication mode requires exactly three scenarios/lanes per candidate. | Implemented |
| 10 | Controller comparisons are physically paired. | Sequential batches reuse the same three lanes; provenance equality is validated before output. | Implemented |
| 11 | Dense reward cannot alone define promotion. | Fitness v6 adds terminal forward, stability, failure, upright-shortfall, success, and worst-speed terms sampled at benchmark cadence. | Implemented and unit-tested |
| 12 | Failed ballistic baselines do not set the comparison target. | Positive displacement from a failed baseline is capped at zero in the versioned adjusted comparison; raw values remain recorded. | Implemented and unit-tested |
| 13 | Controller displacement begins after neutral warmup. | Trainer and benchmark capture and pair a post-warmup horizontal baseline. | Implemented |
| 14 | Safety extrema use the same frame and cadence. | Minimum body-up, peak vertical excursion, and lane escape are accumulated every physics tick, with exact sample completeness required. | Implemented |
| 15 | Missing posture data fails closed. | Missing or non-finite body-up telemetry is treated as unsafe. | Implemented |
| 16 | Ballistic motion is rejected. | Training fitness v6 and benchmark acceptance require peak vertical excursion `<= 3.0` blocks. | Implemented |
| 17 | Lane escape is rejected. | Fitness v6 penalizes arena escape; benchmark v3 requires zero learned arena escapes. | Implemented |
| 18 | Arena mutation is crash recoverable. | A compressed NBT journal is fsynced and atomically promoted before mutation, replayed on server start, and retained on recovery error. After a successful in-memory restore, however, the journal can be removed before affected chunks are explicitly forced to disk. | Implemented and GameTest-covered, with documented P2 power-loss limitation |
| 19 | Checkpoint provenance is immutable. | Checkpoint v4 stores the original `trainingConfig` snapshot, arena, and slot-layout IDs. | Implemented |
| 20 | Checkpoint publication is crash safe. | Forced temporary write, atomic promotion, and a valid recovery backup prevent in-place torn checkpoints. | Implemented |
| 21 | Active work blocks checkpoint load. | Loading is rejected during training, evaluation, benchmark, replay, or bridge activity. | Implemented |
| 22 | Resume compatibility is strict. | Spacing and environment protocol must match before optimizer reuse. | Implemented |
| 23 | Publication eligibility is canonical. | Fitness, arena, layout, spacing, cadence, warmup, horizon, environment, and exactly-three-scenario contract are checked before benchmark start. | Implemented |
| 24 | Benchmark evidence is a durable atomic bundle. | Both manifest-committed immutable pairs match their recorded SHA-256 values. | Pass |
| 25 | Complete final benchmark coverage exists twice. | Each accepted repeat contains 3 controllers x 3 speeds with finite bounded metrics. | Pass |
| 26 | Both repeats share exact provenance. | Both name genome `6762d9ed…`, fresh-search run `7bf2b216…`, seed `2026080301`, generation 5, world seed `-3368720904110701394`, and the same protocol. | Pass |
| 27 | Learned gait beats both adjusted baselines. | Both margins over the stronger adjusted baseline are `3.077521006266276` blocks. | Pass |
| 28 | Learned gait moves forward at every speed. | Both repeats record `3.659454`, `2.941055`, and `2.632053` blocks at speeds `0.50`, `0.80`, and `1.10`. | Pass |
| 29 | Learned gait has no machine failure. | Both repeats record learned failures `0`. | Pass |
| 30 | Learned gait stays upright. | Both repeat minima are `0.6082669526583021`. | Pass |
| 31 | Learned gait is non-ballistic. | Both repeat peaks are `2.127410888671875` blocks. | Pass |
| 32 | Learned gait stays inside its lane. | Both repeats record learned arena escapes `0`. | Pass |
| 33 | Cleanup restores all owned state. | Post-run inspection found no Minecraft/Gradle run process, RCON listener, or recovery journal; `enable-rcon=false` and the RCON password is blank. This operational state is not encoded in the manifests. | Pass; not a claim of artifact-level power-loss durability |

## Why the stage-one v2 result is not promotable

The two stage-one v2 artifacts are strong repeatability evidence but failed performance evidence:

- learned mean forward displacement: `1.507332` blocks;
- stronger stability-adjusted baseline mean: `2.619032` blocks;
- learned adjusted margin: `-1.111700` blocks;
- learned machine failures: `1`;
- learned minimum body-up: `0.082457`;
- acceptance: false.

They also mixed frames: minimum-up and vertical tracking began after the 20-tick neutral warmup, while terminal forward displacement still referenced the pre-warmup spawn origin. The final audit therefore requires a fresh artifact produced after all motion metrics share the same post-warmup baseline.

## Why the generation-6 fitness-v3 result is not promotable

The generation-6 checkpoint looked 3/3 only under the old control-boundary training sampler. Hardened benchmark `eb0b3949-159a-4ad3-a7ae-92f991977b07` found positive learned progress at every speed and a mean/margin of `4.529146`, but the `0.50` episode failed and escaped, its minimum body-up fell to `-0.004774`, and its peak vertical excursion reached `5.492683`. The other two speeds passed.

That artifact failed `learned_no_failures`, `learned_minimum_body_up`, `learned_anti_ballistic_peak_vertical_excursion`, and `no_learned_arena_escape`. It also came from the old almost-contiguous training arena rather than isolated benchmark-shaped triplets. It is therefore preserved as evidence for the later sampling/topology repairs, not eligible for promotion.

## Why the accepted fitness-v5 results are not promotable

Generation 1 had already shown healthy training progress: best aggregate `33.0632`, mean `19.4321`, worst `14.5244`, 3/3 gate success, and zero failures. That remains training evidence only.

The exact v5 genome `be80280e365ebb756e2f9041655f11377e94e68674c68ab2db6b701c9abf2d3e` passed both hardened benchmark runs:

- `239f85b4-4fee-418c-906f-0ef770d74407`: learned mean `4.145461`, minimum body-up `0.658708`, peak vertical `2.167221`, zero failures and escapes;
- `4c6b374a-0505-4cb4-a724-fa03229ce5dc`: learned mean `4.071139`, minimum body-up `0.655156`, peak vertical `2.167618`, zero failures and escapes.

The independent audit then proved that selected generation-2 candidate 25 trained in local slots 3–5 while the morphology requested contiguous offsets and the lane-groups-v2 arena inserted its first group gap. Its machines were not placed at the prepared centers represented by the claimed arena provenance. Both acceptance objects truthfully describe benchmark behavior, but neither can prove selection under the claimed training topology. V6 forces a clean lineage after unifying the mapping.

## Final acceptance evidence

The following values come from the two validated, canonically eligible v6 benchmark bundles:

| Field | Run 01 | Run 02 |
|---|---:|---:|
| Checkpoint artifact | [fresh seeded generation-5 v6 checkpoint](../artifacts/duopod_walk_forward_v6/checkpoint/duopod_cem_fresh_v6_seed2026080301_accepted.json) | Same checkpoint |
| Checkpoint format | `minecraft_machines_duopod_cem_checkpoint_v4` | Same |
| Search seed / population / elites / generations | `2026080301` / `32` / `8` / `12` | Same |
| Checkpoint run / generation | `7bf2b216-3414-4030-88a8-3e6316823358` / `5` | Same |
| Training aggregate / mean / worst | `30.296754493224093` / `16.582846076420527` / `14.855633667214256` | Same |
| Genome SHA-256 | `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f` | Same |
| Benchmark ID | `c647d862-f7e4-4d34-8b69-2fcd0591edaf` | `6b5506a5-e24b-4df2-b972-49cbe6ed1a51` |
| Manifest | `commit_complete: true`; hashes verified | `commit_complete: true`; hashes verified |
| JSON SHA-256 | `4b5b2d4459ea8b81b15a863635a41b88080abffd879205379c9611643365d69e` | `71ffc0583cb90ea4bf10d2700e4e18d4e9ba0ab8614278e977d04491727e380f` |
| CSV SHA-256 | `15d1c0bcc9442ff47316ec0d916ae8c74677fec1958e4ebc1c7763097a3675d3` | `5a8747d1e4f622c0c8444f290af7b2cd13c79372e38e45a745b3c51f0135dbac` |
| Accepted | `true` (8/8) | `true` (8/8) |
| Learned mean forward | `3.077521006266276` | `3.077521006266276` |
| Neutral adjusted comparison mean | `-2.160552978515625` | `-2.160552978515625` |
| Scripted adjusted comparison mean | `0.0` | `0.0` |
| Margin over stronger adjusted baseline | `3.077521006266276` | `3.077521006266276` |
| Learned per-speed forward values | `3.659454`, `2.941055`, `2.632053` | `3.659454`, `2.941055`, `2.632053` |
| Learned failures | `0` | `0` |
| Learned minimum body-up | `0.6082669526583021` | `0.6082669526583021` |
| Learned peak vertical excursion | `2.127410888671875` | `2.127410888671875` |
| Learned arena escapes | `0` | `0` |

Shared contract fields are benchmark v3, fitness v6, arena v2, slot layout v1, acceptance rule v2, level `world`, seed `-3368720904110701394`, and dimension `minecraft:overworld`. Generation 8 had the highest training aggregate (`36.5139`) but was rejected by the held-out posture gate (`0.59605 < 0.60`), so generation 5 is the promoted checkpoint. Generation-one structured stable-basin probes are part of this fresh optimizer search, so it is not a purely uninformed random initialization. Both artifacts record `code_revision: null` with `code_revision_status: unavailable_in_runtime_artifact`, so runtime provenance does not identify a Git commit.

## Required commands

Fresh training:

```mcfunction
/mm train duopod cem start_fresh_at 31 80 63 north 32 12 800 4 3 12 max_slots 24 walk_forward seed 2026080301
```

Final paired benchmark:

```mcfunction
/mm train duopod cem benchmark_walk_forward_at 31 80 63 north 200
```

Required cleanup after live work:

```mcfunction
/mm train duopod cem stop
/mm train clear
/mm train bridge stop
```

Also verify that the temporary arena restored its snapshot, no recovery journal remains, the run-scoped chunk lease was released, the Minecraft server and RCON listener stopped, and `enable-rcon=false` with a blank password was restored if RCON was temporarily enabled.

## Claim boundary

The portfolio may claim successful learned locomotion and baseline superiority for these two fixed-protocol repeats in level `world`, seed `-3368720904110701394`, dimension `minecraft:overworld`. It may not claim multi-seed optimizer reliability, arbitrary-terrain generalization, physics-version invariance, runtime Git-revision binding, or absolute power-loss durability of arena restoration.

See the [current status report](duopod-training-status-report.md) for the evidence ledger and the [engineering case study](engineering-case-study.md) for the causal repair narrative.
