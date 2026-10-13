#!/usr/bin/env python3
"""Create a fresh naturally generated review save without terrain/physics copies."""
from pathlib import Path
import json
import nbtlib as n
ROOT=Path(__file__).resolve().parents[1]
SAVES=ROOT/'minecraft_machines/neoforge/runs/client/saves'
WORLD=SAVES/'Leaper Field Test 20260908'
if WORLD.exists():
    raise SystemExit(f'Refusing to overwrite {WORLD}')
level=n.load(SAVES/'Leaper Articulation Lab/level.dat'); d=level['Data']
d['LevelName']=n.String(WORLD.name)
d['ScheduledEvents']=n.List[n.Compound]()
d['CustomBossEvents']=n.Compound()
d['GameRules']=n.Compound() # Minecraft supplies normal defaults.
d['GameType']=n.Int(1); d['allowCommands']=n.Byte(1)
d['Difficulty']=n.Byte(2); d['DifficultyLocked']=n.Byte(0)
d['Time']=n.Long(0); d['DayTime']=n.Long(1000)
d['raining']=n.Byte(0);d['thundering']=n.Byte(0)
d['SpawnX']=n.Int(-64);d['SpawnY']=n.Int(81);d['SpawnZ']=n.Int(33)
d['DataPacks']['Enabled']=n.List[n.String]([v for v in d['DataPacks']['Enabled'] if not str(v).startswith('file')])
gen=d['WorldGenSettings'];gen['seed']=n.Long(2026090717);gen['generate_features']=n.Byte(1)
gen['dimensions']['minecraft:overworld']['generator']=n.Compound({'type':n.String('minecraft:noise'),'settings':n.String('minecraft:overworld'),'biome_source':n.Compound({'type':n.String('minecraft:multi_noise'),'preset':n.String('minecraft:overworld')})})
d.pop('simulated:world_preset',None)
p=d.get('Player')
if p is not None:
    p['Pos']=n.List[n.Double]([n.Double(v) for v in (-64,105,33)])
    p['Motion']=n.List[n.Double]([n.Double(0)]*3)
    p['Rotation']=n.List[n.Float]([n.Float(180),n.Float(35)])
    p['Inventory']=n.List[n.Compound]()
    p['playerGameType']=n.Int(1);p['FallDistance']=n.Float(0)
    for key in ['flying','mayfly','invulnerable']:p['abilities'][key]=n.Byte(1)
WORLD.mkdir();level.save(WORLD/'level.dat')
(ROOT/'artifacts/leaper-follow/world-manifest.json').write_text(json.dumps({'world':str(WORLD),'seed':2026090717,'generator':'minecraft:noise / minecraft:overworld','initial_files':['level.dat'],'copied_regions_entities_or_physics':False,'terrain_edits':False,'mode':'creative; normal world gamerules'},indent=2)+'\n')
print(WORLD)
