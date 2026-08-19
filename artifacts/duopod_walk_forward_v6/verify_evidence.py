#!/usr/bin/env python3
"""Verify the packaged checkpoint and both immutable benchmark commits."""

from __future__ import annotations

import csv
import hashlib
import json
import math
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parent


def read_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def verify_file(path: Path, expected: dict) -> None:
    require(path.is_file(), f"missing {path.relative_to(ROOT)}")
    require(path.stat().st_size == expected["bytes"], f"byte count differs for {path.name}")
    require(sha256(path) == expected["sha256"], f"SHA-256 differs for {path.name}")


def genome_sha256(genome: list[float]) -> str:
    encoded = b"".join(struct.pack(">d", float(value)) for value in genome)
    return hashlib.sha256(encoded).hexdigest()


def verify_benchmark(directory: Path, expected: dict, checkpoint: dict) -> dict:
    manifest_path = directory / "duopod_cem_walk_forward_benchmark_latest_manifest.json"
    require(sha256(manifest_path) == expected["manifest_sha256"], f"manifest hash differs in {directory.name}")
    manifest = read_json(manifest_path)

    require(manifest["commit_complete"] is True, f"incomplete benchmark commit in {directory.name}")
    require(manifest["benchmark_id"] == expected["benchmark_id"], f"unexpected benchmark id in {directory.name}")

    immutable = manifest["immutable_artifacts"]
    json_path = directory / immutable["json"]["filename"]
    csv_path = directory / immutable["csv"]["filename"]
    verify_file(json_path, immutable["json"])
    verify_file(csv_path, immutable["csv"])

    benchmark = read_json(json_path)
    acceptance = benchmark["acceptance"]
    require(benchmark["benchmark_id"] == manifest["benchmark_id"], f"JSON id mismatch in {directory.name}")
    require(acceptance["accepted"] is True, f"benchmark rejected in {directory.name}")
    require(acceptance["coverage_valid"] is True, f"incomplete coverage in {directory.name}")
    require(acceptance["invariants_valid"] is True, f"pairing invariant failed in {directory.name}")
    require(not acceptance["failed_criteria"], f"failed acceptance criterion in {directory.name}")

    policy = benchmark["policy"]
    require(policy["checkpoint_run_id"] == checkpoint["runId"], f"run id mismatch in {directory.name}")
    require(policy["checkpoint_generation"] == checkpoint["generation"], f"generation mismatch in {directory.name}")
    require(policy["genome_sha256"] == genome_sha256(checkpoint["genome"]), f"genome mismatch in {directory.name}")

    learned = [episode for episode in benchmark["episodes"] if episode["controller"] == "learned"]
    require({episode["requested_forward_speed"] for episode in learned} == {0.5, 0.8, 1.1}, f"speed coverage differs in {directory.name}")
    require(all(math.isfinite(episode["forward_displacement_blocks"]) for episode in learned), f"non-finite displacement in {directory.name}")
    require(all(episode["forward_displacement_blocks"] > 0.0 for episode in learned), f"non-positive speed result in {directory.name}")
    require(all(not episode["machine_failure"] for episode in learned), f"learned failure in {directory.name}")
    require(all(not episode["arena_escape"] for episode in learned), f"learned escape in {directory.name}")

    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    require(len(rows) == benchmark["protocol"]["episode_count"], f"CSV row count differs in {directory.name}")
    require(all(row["benchmark_id"] == benchmark["benchmark_id"] for row in rows), f"CSV id mismatch in {directory.name}")
    return benchmark


def main() -> None:
    evidence = read_json(ROOT / "evidence_manifest.json")
    build = read_json(ROOT / "build_manifest.json")
    verify_file(ROOT / build["jar"]["path"], build["jar"])
    selected = evidence["selected_checkpoint"]
    checkpoint_path = ROOT / selected["filename"]
    verify_file(checkpoint_path, selected)
    verify_file(ROOT / evidence["training_summary"]["filename"], evidence["training_summary"])
    checkpoint = read_json(checkpoint_path)

    require(checkpoint["formatVersion"] == 4, "checkpoint format is not v4")
    require(checkpoint["runId"] == selected["run_id"], "checkpoint run id differs")
    require(checkpoint["runSeed"] == selected["run_seed"], "checkpoint seed differs")
    require(checkpoint["generation"] == selected["generation"], "checkpoint generation differs")
    require(checkpoint["successRate"] == 1.0, "training success rate differs")
    require(checkpoint["failureRate"] == 0.0, "training failure rate differs")
    require(genome_sha256(checkpoint["genome"]) == selected["genome_sha256"], "checkpoint genome hash differs")

    results = []
    for expected in evidence["accepted_benchmarks"]:
        results.append(verify_benchmark(ROOT / expected["directory"], expected, checkpoint))

    require(len({result["benchmark_id"] for result in results}) == 2, "benchmark ids are not distinct")
    signatures = [
        [
            episode["forward_displacement_blocks"]
            for episode in result["episodes"]
            if episode["controller"] == "learned"
        ]
        for result in results
    ]
    require(signatures[0] == signatures[1], "repeat benchmark displacement differs")

    observed = results[0]["acceptance"]["observed"]
    print("PASS: verified NeoForge JAR hash and byte count")
    print("PASS: checkpoint hash, seed, run, generation, and genome provenance")
    print("PASS: 2/2 immutable benchmark commits and CSV pairings")
    print(
        "PASS: accepted gait — "
        f"mean {observed['learned_mean_forward_blocks']:.4f} blocks, "
        f"adjusted margin {observed['learned_minus_stronger_baseline_stability_adjusted_comparison_blocks']:.4f}, "
        f"minimum body-up {observed['learned_minimum_body_up']:.4f}"
    )


if __name__ == "__main__":
    main()
