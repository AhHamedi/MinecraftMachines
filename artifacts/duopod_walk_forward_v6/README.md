# Fresh seeded Duopod walk-forward result

This directory is the portable evidence bundle for the selected Duopod policy. It came from a new 12-generation CEM optimizer run, not a one-candidate re-certification of an old genome.

## Selected result

| Field | Value |
|---|---:|
| Run seed | `2026080301` |
| Run ID | `7bf2b216-3414-4030-88a8-3e6316823358` |
| Selected generation | `5` |
| Population / elites | `32 / 8` |
| Training scenarios | `0.50`, `0.80`, `1.10` requested forward speed |
| Training mean / worst fitness | `16.5828 / 14.8556` |
| Training success / failure rate | `1.0 / 0.0` |
| Genome SHA-256 | `6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f` |

The selected generation moved forward by `3.6595`, `2.9411`, and `2.6321` blocks at the three benchmark speeds. Both benchmark runs produced the same learned measurements:

| Acceptance measurement | Result | Gate |
|---|---:|---:|
| Mean post-warmup forward displacement | `3.0775` blocks | positive at every speed |
| Margin over stronger stability-adjusted baseline | `3.0775` blocks | at least `0.5` |
| Minimum body-up | `0.60827` | at least `0.60` |
| Peak vertical excursion | `2.12741` blocks | at most `3.0` |
| Machine failures / arena escapes | `0 / 0` | `0 / 0` |
| Acceptance criteria | `8 / 8` | `8 / 8` |

The generation-by-generation training curve is in `training_generations.csv`. It records the transition from a generation-1 best of `-0.9641` with 58 falls to a population with 82 successful scenario evaluations and 4 falls by generation 12. The global training maximum occurred at generation 8; held-out acceptance, not training fitness alone, determined deployment.

Run `python3 artifacts/duopod_walk_forward_v6/verify_evidence.py` from anywhere to verify the checkpoint hash, genome provenance, both immutable JSON/CSV pairs, their commit manifests, and the acceptance result.

The final verified NeoForge JAR is recorded in `build_manifest.json`; its SHA-256 is `e17f2a845fdbfed7736fd16a68625bc44720624413b527c25db5182171a77624`. The runtime benchmark schema could not bind evidence to a Git revision in the then-uncommitted workspace, so the manifest correctly reports that limitation. At release time, an independent rebuild from tag `v0.1.0` reproduced the same SHA-256, establishing byte-for-byte equality without rewriting the runtime artifact's provenance.

## Reproduce the search

Start a NeoForge server, use a bounded empty area, and run this from the server console (omit the leading slash in a console):

```mcfunction
/mm train duopod cem start_fresh_at 31 80 63 north 32 12 800 4 3 12 max_slots 24 walk_forward seed 2026080301
```

The explicit seed reproduces an uninterrupted fresh run. Checkpoint continuation preserves the distribution and generation but does not persist the Java RNG's internal cursor, so a stop/save/restart continuation is not bit-for-bit equivalent to leaving the same run uninterrupted.

After completion, benchmark the selected checkpoint twice:

```mcfunction
/mm train duopod cem benchmark_walk_forward_at 31 80 63 north 200
```

To deploy the packaged policy into the recorded world, copy the checkpoint to `<world>/minecraft_machines/duopod_cem_best.json`, then run `/mm train duopod cem load`. `replay_best` is useful for visual inspection; only the fixed benchmark is acceptance evidence.

## Why generation 5 was selected

Generation 8 had the highest training aggregate (`36.5139`) and also had 100% training success. The held-out benchmark correctly rejected it: its minimum body-up was `0.59605`, just below the fixed `0.60` gate. Generation 5 was slightly slower but passed the unmodified contract twice, so it is the promoted checkpoint. The rejected checkpoint and benchmark are retained under `diagnostics/`.

## Scope

This establishes deterministic operational repeatability for one morphology, world seed, flat arena, and three-speed protocol. It does not establish general terrain locomotion, cross-version physics robustness, or statistical performance across independent training seeds. Generation-one structured stable-basin probes are part of the optimizer, so this is a fresh optimizer search but not a purely uninformed random initialization.
