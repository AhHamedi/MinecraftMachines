#!/usr/bin/env python3
"""Verify the public dashboard against the canonical fresh-search evidence."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "artifacts" / "duopod_walk_forward_v6"
PORTFOLIO = ROOT / "portfolio"


def fail(message: str) -> None:
    raise AssertionError(message)


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_local_links(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    raw_links = re.findall(r"(?:href|src)=[\"']([^\"']+)", text)
    if path.suffix.lower() == ".md":
        raw_links.extend(re.findall(r"!?\[[^]]*]\(([^)]+)\)", text))

    checked = 0
    for raw_link in raw_links:
        link = raw_link.strip().split()[0].strip("<>")
        if not link or link.startswith(("#", "data:", "http://", "https://", "mailto:")):
            continue
        local = unquote(link.split("#", 1)[0].split("?", 1)[0])
        if not local:
            continue
        target = (path.parent / local).resolve()
        if not target.exists():
            fail(f"broken local link in {path.relative_to(ROOT)}: {link}")
        checked += 1
    return checked


def main() -> int:
    bundle = load_json(EVIDENCE / "evidence_manifest.json")
    selected = bundle["selected_checkpoint"]
    expected_runs = [
        (
            "01",
            "benchmark_run_01",
            "c647d862-f7e4-4d34-8b69-2fcd0591edaf",
        ),
        (
            "02",
            "benchmark_run_02",
            "6b5506a5-e24b-4df2-b972-49cbe6ed1a51",
        ),
    ]

    checkpoint_copy = PORTFOLIO / "data" / "duopod_walk_forward_checkpoint_final_v6.json"
    if sha256(checkpoint_copy) != selected["sha256"]:
        fail("portfolio checkpoint does not match the selected fresh-search checkpoint")

    for suffix, directory, benchmark_id in expected_runs:
        manifest_path = EVIDENCE / directory / "duopod_cem_walk_forward_benchmark_latest_manifest.json"
        manifest = load_json(manifest_path)
        if not manifest.get("commit_complete"):
            fail(f"run {suffix} manifest is not committed")
        if manifest["benchmark_id"] != benchmark_id:
            fail(f"run {suffix} benchmark ID does not match the evidence bundle")

        for kind in ("json", "csv"):
            immutable = manifest["immutable_artifacts"][kind]
            canonical = EVIDENCE / directory / immutable["filename"]
            public_copy = PORTFOLIO / "data" / f"walk_forward_benchmark_final_v6_run_{suffix}.{kind}"
            expected_hash = immutable["sha256"]
            if sha256(canonical) != expected_hash:
                fail(f"canonical run {suffix} {kind.upper()} hash mismatch")
            if sha256(public_copy) != expected_hash:
                fail(f"public run {suffix} {kind.upper()} is not byte-exact")

        result = load_json(
            EVIDENCE / directory / manifest["immutable_artifacts"]["json"]["filename"]
        )
        if result["acceptance"]["accepted"] is not True:
            fail(f"run {suffix} is not accepted")
        if not all(result["acceptance"]["criteria"].values()):
            fail(f"run {suffix} does not pass every acceptance criterion")
        if result["policy"]["genome_sha256"] != selected["genome_sha256"]:
            fail(f"run {suffix} genome does not match the selected checkpoint")

    dashboard = (PORTFOLIO / "index.html").read_text(encoding="utf-8")
    required_claims = (
        "c647d862-f7e4-4d34-8b69-2fcd0591edaf",
        "6b5506a5-e24b-4df2-b972-49cbe6ed1a51",
        "7bf2b216-3414-4030-88a8-3e6316823358",
        "6762d9ede735d81b1d7e7dd19ba7d8714d979f9953d3b7c500200dc1a6f0195f",
        "+3.0775",
        "29 NeoForge GameTests",
    )
    for claim in required_claims:
        if claim not in dashboard:
            fail(f"dashboard is missing canonical claim: {claim}")

    stale_claims = (
        "c4712041-cf04-4f40-8f64-d4cff04f9bc0",
        "72f715bd-9be1-4e3e-8427-a3a4d82d505a",
        "e63c9b27-d19f-4255-bcae-abd543b3f222",
        "be80280e365ebb756e2f9041655f11377e94e68674c68ab2db6b701c9abf2d3e",
    )
    for claim in stale_claims:
        if claim in dashboard:
            fail(f"dashboard still presents superseded v6 provenance: {claim}")

    preview = PORTFOLIO / "media" / "duopod-demo.gif"
    if not preview.exists() or preview.stat().st_size > 8 * 1024 * 1024:
        fail("animated preview is missing or larger than 8 MiB")

    checked_links = 0
    for public_file in (ROOT / "README.md", PORTFOLIO / "index.html", PORTFOLIO / "data" / "README.md"):
        checked_links += verify_local_links(public_file)

    print("PASS: portfolio checkpoint and both benchmark pairs match canonical evidence")
    print("PASS: dashboard presents fresh-search v6 provenance and no superseded final IDs")
    print(f"PASS: animated preview is {preview.stat().st_size:,} bytes")
    print(f"PASS: {checked_links} local public links resolve")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
