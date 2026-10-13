#!/usr/bin/env python3
"""Create a fresh, isolated two-up/one-across review save; never copy terrain or physics."""
from pathlib import Path
import json
import nbtlib as n
ROOT=Path(__file__).resolve().parents[1]
SAVES=ROOT/'minecraft_machines/neoforge/runs/client/saves'
WORLD=SAVES/'Leaper Uphill Review 20260908'
if WORLD.exists(): raise SystemExit(f'Refusing to overwrite {WORLD}')
f=n.load(SAVES/'Leaper Articulation Lab/level.dat');d=f['Data']
d['LevelName']=n.String(WORLD.name)
d['ScheduledEvents']=n.List[n.Compound]();d['CustomBossEvents']=n.Compound()
d['GameRules']=n.Compound({k:n.String('false') for k in ['doMobSpawning','doDaylightCycle','doWeatherCycle','doPatrolSpawning','doTraderSpawning']})
d['GameType']=n.Int(1);d['allowCommands']=n.Byte(1)
d['Time']=n.Long(0);d['DayTime']=n.Long(6000)
d['raining']=n.Byte(0);d['thundering']=n.Byte(0)
for key,value in [('SpawnX',0),('SpawnY',8),('SpawnZ',16)]:d[key]=n.Int(value)
d['DataPacks']['Enabled']=n.List[n.String]([v for v in d['DataPacks']['Enabled'] if not str(v).startswith('file')])
gen=d['WorldGenSettings'];gen['seed']=n.Long(2026090802);gen['generate_features']=n.Byte(0)
gen['dimensions']['minecraft:overworld']['generator']=n.Compound({'type':n.String('minecraft:flat'),'settings':n.Compound({'biome':n.String('minecraft:plains'),'features':n.Byte(0),'lakes':n.Byte(0),'structure_overrides':n.List[n.String](), 'layers':n.List[n.Compound]([n.Compound({'block':n.String(block),'height':n.Int(height)}) for block,height in [('minecraft:bedrock',1),('minecraft:dirt',69),('minecraft:smooth_stone',1)]])})})
p=d.get('Player')
if p is not None:
 p['Pos']=n.List[n.Double]([n.Double(v) for v in (.5,15,16.5)])
 p['Motion']=n.List[n.Double]([n.Double(0)]*3);p['Rotation']=n.List[n.Float]([n.Float(180),n.Float(25)])
 p['Inventory']=n.List[n.Compound]();p['playerGameType']=n.Int(1);p['FallDistance']=n.Float(0)
 for k in ['flying','mayfly','invulnerable']:p['abilities'][k]=n.Byte(1)
WORLD.mkdir();f.save(WORLD/'level.dat')
(ROOT/'artifacts/leaper-uphill/world-manifest.json').write_text(json.dumps({'world':str(WORLD),'initial_files':['level.dat'],'copied_regions_entities_or_physics':False,'floor':7,'fixture':'three risers, each2blocks tall/1block tread, broad safe aprons','normal_field_world_modified':False},indent=2)+'\n')
print(WORLD)
