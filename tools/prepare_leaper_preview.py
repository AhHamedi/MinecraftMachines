#!/usr/bin/env python3
"""Install the versioned development profile without replacing local experiments."""

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "tools/profiles/leaper-preview"
NEOFORGE = ROOT / "minecraft_machines/neoforge"
TARGETS = {
    "client": NEOFORGE / "runs/client/leaper_learning",
    "scratch": NEOFORGE / "build/leaper-scratch-gametest/leaper_learning",
    "quadruped": NEOFORGE / "build/quadruped-gametest/leaper_learning",
    "natural": NEOFORGE / "build/leaper-natural-gametest/leaper_learning",
}
FILES = (
    "horrific-predator-candidate.json",
    "horrific-predator-highstep-settings.json",
)


def prepare_preview(destination):
    """Seed only missing inputs; return paths created in this call."""
    destination = Path(destination)
    payloads = {name: (PROFILE / name).read_bytes() for name in FILES}
    for payload in payloads.values():
        json.loads(payload)
    destination.mkdir(parents=True, exist_ok=True)
    installed = []
    for name, payload in payloads.items():
        path = destination / name
        try:
            with path.open("xb") as output:
                output.write(payload)
        except FileExistsError:
            continue
        installed.append(path)
    return installed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=[*TARGETS, "all"], default="client")
    args = parser.parse_args()
    targets = TARGETS.items() if args.target == "all" else [(args.target, TARGETS[args.target])]
    for name, destination in targets:
        created = prepare_preview(destination)
        print(f"{name}: installed {len(created)} missing profile files in {destination}")


if __name__ == "__main__":
    main()
