#!/usr/bin/env python3
"""Run native hunting checks and reject failed JSON trials, even if Gradle exits zero."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

from prepare_leaper_preview import prepare_preview


ROOT = Path(__file__).resolve().parents[1]
NEO = ROOT / "minecraft_machines/neoforge"
COMMON = ROOT / "minecraft_machines/common"
SUITES = [
    ("predator-hunting-regression", 5, True),
    ("predator-follow-regression", 3, True),
    ("predator-pursuit-regression-candidate", 9, True),
    ("predator-pounce-commands", 8, True),
    ("predator-agile-walk", 6, False),
]


APEX_SUITES = [
    ("predator-apex-qualify", 6, True),
    ("predator-pounce-elevation", 12, True),
    ("predator-apex-collision", 13, True),
    ("predator-partial-support", 4, True),
    ("predator-shore-loop", 1, True),
    ("predator-departure-clearance", 1, True),
]


def source_fingerprint():
    paths = list((NEO / "src/main/java").rglob("MinecraftMachinesLeaper*.java"))
    paths += list((NEO / "src/main/java").rglob("MinecraftMachinesQuadrupedChunkLease.java"))
    paths += list((COMMON / "src/main/java").rglob("Leaper*.java"))
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda p: p.relative_to(ROOT).as_posix()):
        digest.update((path.relative_to(ROOT).as_posix() + "\n").encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", action="store_true", help="Also verify follow, arrival, pounce commands and stairs")
    parser.add_argument("--apex", action="store_true", default=True, help="Compatibility flag; Apex is always used")
    parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies without network lookups")
    parser.add_argument("--suite", action="append", choices=[s[0] for s in SUITES + APEX_SUITES],
                        help="Run only this suite; repeat to select multiple suites")
    args = parser.parse_args()
    if args.suite and not args.apex and any(s in {row[0] for row in APEX_SUITES} for s in args.suite):
        parser.error("Apex-specific suites require --apex")
    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        homes = sorted((Path.home() / ".local/jdks").glob("*21*/Contents/Home"))
        if homes:
            env["JAVA_HOME"] = str(homes[-1])
        elif Path("/usr/libexec/java_home").exists():
            env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    env.setdefault("RAYON_NUM_THREADS", "4")
    prepare_preview(NEO / "build/leaper-scratch-gametest/leaper_learning")
    output = ROOT / ("artifacts/leaper-apex" if args.apex else "artifacts/leaper-hunting") / datetime.datetime.now().strftime("verification-%Y%m%d-%H%M%S")
    output.mkdir(parents=True, exist_ok=False)
    fingerprint = source_fingerprint()
    summary = {"implementation_sha256": fingerprint, "version": "apex", "suites": [], "complete": False}

    def persist():
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")

    def gradle(name, arguments):
        print(f"Running {name}; log: {output / (name + '.log')}", flush=True)
        with (output / (name + ".log")).open("w") as log:
            subprocess.run([str(ROOT / "gradlew"), *(['--offline'] if args.offline else []), *arguments], cwd=ROOT, env=env,
                           stdout=log, stderr=subprocess.STDOUT, check=True)

    persist()
    gradle("build", [":minecraft_machines:common:test", ":minecraft_machines:neoforge:jar"])
    unit = [ET.parse(p).getroot().attrib for p in (COMMON / "build/test-results/test").glob("TEST-*.xml")]
    summary["unit_tests"] = {k: sum(int(r.get(k, 0)) for r in unit) for k in ("tests", "failures", "errors", "skipped")}
    suites = list(SUITES if args.full else SUITES[:1])
    if args.apex and args.full:
        suites += APEX_SUITES
    if args.suite:
        available = {row[0]: row for row in SUITES + APEX_SUITES}
        suites = [available[name] for name in dict.fromkeys(args.suite)]
    for mode, expected, leap in suites:
        report = NEO / "build/leaper-scratch-gametest/leaper_learning" / f"horrific-{mode}.json"
        previous_modified = report.stat().st_mtime_ns if report.exists() else None
        gradle(mode, [":minecraft_machines:neoforge:runGameTest",
                      "-PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning",
                      "-PleaperScratch=true", f"-PleaperMode={mode}", "-PleaperProfile=horrific",
                      "-PleaperHighStepCandidate=true", "-PleaperAgile=true", f"-PleaperLeap={str(leap).lower()}",
                      f"-PleaperApex={str(args.apex).lower()}"])
        if not report.exists() or report.stat().st_mtime_ns == previous_modified:
            raise RuntimeError(f"No fresh native report for {mode}")
        shutil.copy2(report, output / report.name)
        data = json.loads(report.read_text())
        trials = data.get("trials", [])
        failures = [t.get("case") for t in trials if t.get("success") is not True]
        passed = (len(trials) == expected and not failures and data.get("complete", True)
                  and data.get("implementation_sha256") == fingerprint and source_fingerprint() == fingerprint)
        summary["suites"].append({"mode": mode, "expected": expected, "passed": passed, "failed_cases": failures})
        persist()
        if not passed:
            raise RuntimeError(f"Incomplete, stale, or failed native suite: {mode}; inspect {output}")
        print(f"Passed {mode}: {expected}/{expected}", flush=True)
    summary["complete"] = True
    persist()
    print(f"Verified. Results: {output / 'summary.json'}", flush=True)


if __name__ == "__main__":
    main()
