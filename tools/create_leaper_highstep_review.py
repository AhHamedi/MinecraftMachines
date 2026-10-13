#!/usr/bin/env python3
"""Create a new empty review save. Never copy regions, entities, or Sable bodies."""
from pathlib import Path
import hashlib
import json
import shutil
import nbtlib

ROOT = Path(__file__).resolve().parents[1]
NAME = "Leaper Highstep Dev26 Review 20260907"
CLIENT = ROOT / "minecraft_machines/neoforge/runs/client"
ART = ROOT / "artifacts/leaper-highstep"
WORLD = CLIENT / "saves" / NAME

if WORLD.exists():
    raise SystemExit(f"Refusing to overwrite existing review save: {WORLD}")
level = nbtlib.load(CLIENT / "saves/Leaper Articulation Lab/level.dat")
data = level["Data"]
data["LevelName"] = nbtlib.String(NAME)
data["ScheduledEvents"] = nbtlib.List[nbtlib.Compound]()
data["DataPacks"]["Enabled"] = nbtlib.List[nbtlib.String](
    [v for v in data["DataPacks"]["Enabled"] if not str(v).startswith("file")])
data["SpawnX"], data["SpawnY"], data["SpawnZ"] = map(nbtlib.Int, (1705, 31, 554))
data["GameType"] = nbtlib.Int(1)
data["allowCommands"] = nbtlib.Byte(1)
data["Time"] = nbtlib.Long(0)
data["DayTime"] = nbtlib.Long(6000)
data["raining"] = nbtlib.Byte(0)
data["thundering"] = nbtlib.Byte(0)
for key in ("doMobSpawning", "doDaylightCycle", "doWeatherCycle", "doPatrolSpawning", "doTraderSpawning"):
    data["GameRules"][key] = nbtlib.String("false")
player = data.get("Player")
if player is not None:
    player["Pos"] = nbtlib.List[nbtlib.Double]([nbtlib.Double(v) for v in (1705, 31, 554)])
    player["Motion"] = nbtlib.List[nbtlib.Double]([nbtlib.Double(0)] * 3)
    player["Rotation"] = nbtlib.List[nbtlib.Float]([nbtlib.Float(-90), nbtlib.Float(27)])
    player["Inventory"] = nbtlib.List[nbtlib.Compound]()
    player["playerGameType"] = nbtlib.Int(1)
    player["FallDistance"] = nbtlib.Float(0)
    player["abilities"]["flying"] = nbtlib.Byte(1)
    player["abilities"]["mayfly"] = nbtlib.Byte(1)
    player["abilities"]["invulnerable"] = nbtlib.Byte(1)
WORLD.mkdir()
level.save(WORLD / "level.dat")
runtime = CLIENT / "leaper_learning"
runtime.mkdir(exist_ok=True)
backup = ART / "review-runtime-backup"
backup.mkdir(exist_ok=True)
bindings = {}
for kind, name in (("model", "horrific-predator-candidate.json"), ("settings", "horrific-predator-highstep-settings.json")):
    target = runtime / name
    if target.exists() and not (backup / name).exists():
        shutil.copy2(target, backup / name)
    source = ART / f"development-26-{kind}.json"
    shutil.copy2(source, target)
    bindings[name] = hashlib.sha256(target.read_bytes()).hexdigest()
(ART / "review-world-manifest.json").write_text(json.dumps({
    "world": str(WORLD), "qualified": False, "candidate": 26,
    "initial_files": ["level.dat"], "copied_regions_entities_or_physics": False,
    "runtime_inputs_sha256": bindings,
    "fixture": "F2C1, native builder slot 7; solitary visual trial, not identical batch trajectory",
    "setup": "Standalone setup agent builds native geometry and a fresh standard-mass walker."
}, indent=2) + "\n")
print(WORLD)
