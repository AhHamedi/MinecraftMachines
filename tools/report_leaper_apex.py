#!/usr/bin/env python3
"""Compare completed Apex/Classic native verification folders and plot their measurements."""

import argparse
import json
from pathlib import Path
import statistics


def read(path):
    return json.loads(path.read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apex", type=Path)
    parser.add_argument("classic", type=Path)
    parser.add_argument("--output", type=Path, default=Path("artifacts/leaper-apex"))
    args = parser.parse_args()
    summaries = [read(p / "summary.json") for p in (args.apex, args.classic)]
    if not all(s.get("complete") for s in summaries):
        raise ValueError("Both verification runs must be complete")
    if summaries[0]["implementation_sha256"] != summaries[1]["implementation_sha256"]:
        raise ValueError("Compare controllers from the same source")
    paired = read(args.apex / "horrific-predator-apex-qualify.json")["trials"]
    hunts = {
        version: read(path / "horrific-predator-hunting-regression.json")["trials"]
        for version, path in (("apex", args.apex), ("classic", args.classic))
    }
    if not all(t["success"] for t in paired + hunts["apex"] + hunts["classic"]):
        raise ValueError("Comparison contains unsuccessful trials")
    measures = ("cruise_speed", "arrival_seconds", "cruise_height_range", "total_navigation_millis")
    speed = {
        variant: {k: statistics.mean(t[k] for t in paired if t["variant"] == variant) for k in measures}
        for variant in ("classic", "apex")
    }
    compare = {
        "implementation_sha256": summaries[0]["implementation_sha256"],
        "apex_verification": str(args.apex.resolve()),
        "classic_verification": str(args.classic.resolve()),
        "paired_means": speed,
        "cruise_speed_ratio": speed["apex"]["cruise_speed"] / speed["classic"]["cruise_speed"],
        "hunts": {
            variant: [{k: v for k, v in t.items() if k != "history"} for t in rows]
            for variant, rows in hunts.items()
        },
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "comparison.json").write_text(json.dumps(compare, indent=2) + "\n")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
    fig, axes = plt.subplots(2, 2, figsize=(12, 8), layout="constrained")
    colors = {"classic": "#8296aa", "apex": "#168a65"}
    labels = ["Walk", "Sprint", "Weave", "90° turn", "Wall"]
    for variant, offset in (("classic", -.18), ("apex", .18)):
        values = [t["cruise_speed"] for t in paired if t["variant"] == variant]
        axes[0, 0].bar([i + offset for i in range(3)], values, width=.35, color=colors[variant], label=variant.title())
        axes[0, 1].bar([i + offset for i in range(5)], [t["mean_second_half_gap"] for t in hunts[variant]], width=.35, color=colors[variant])
        sprint = next(t for t in hunts[variant] if t["case"] == "sprinting-prey")
        history = [h for h in sprint["history"] if h["tick"] >= 80]
        axes[1, 0].plot([(h["tick"] - 80) / 20 for h in history], [h["gap"] for h in history], color=colors[variant], linewidth=2)
        axes[1, 1].bar([i + offset for i in range(5)], [t["p95_navigation_millis"] for t in hunts[variant]], width=.35, color=colors[variant])
    axes[0, 0].set(title="Running speed across three directions", ylabel="Cruise speed · blocks/s", xticks=range(3), xticklabels=["0°", "+20°", "−40°"], ylim=(0, 32))
    axes[0, 0].legend(frameon=False, loc="upper center", ncol=2)
    axes[0, 1].set(title="Following distance · final eight seconds", ylabel="Mean gap · blocks", xticks=range(5), xticklabels=labels)
    axes[1, 0].set(title="Separation from a sprinting target", xlabel="Seconds after pursuit begins", ylabel="Gap · blocks", xlim=(0, 16))
    axes[1, 0].axhline(4, color="#bbbbbb", linewidth=1, linestyle="--")
    axes[1, 1].set(title="Navigation call time during hunting", ylabel="p95 · milliseconds", xticks=range(5), xticklabels=labels)
    for ax in axes.flat:
        ax.set_axisbelow(True)
        ax.grid(axis="y", color="#e7e9eb", linewidth=.7)
    fig.suptitle("Leaper Apex · native physics measurements", fontsize=18, weight="bold")
    fig.supxlabel("Same trained candidate · 13 native bodies · 20 Hz control · four physics substeps\nControlled courses; navigation timings exclude physics, rendering and fixture telemetry.", fontsize=9, color="#53606b")
    fig.savefig(args.output / "apex-comparison.png", dpi=180)
    fig.savefig(args.output / "apex-comparison.svg")
    print(json.dumps({"paired_means": speed, "cruise_speed_ratio": compare["cruise_speed_ratio"]}, indent=2))


if __name__ == "__main__":
    main()
