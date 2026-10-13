#!/usr/bin/env python3
"""Create an isolated, normally generated world for physical predator field testing."""
from pathlib import Path
import json
import nbtlib as n

ROOT=Path(__file__).resolve().parents[1]
SAVES=ROOT/'minecraft_machines/neoforge/runs/client/saves'
WORLD=SAVES/'Leaper Predator Wilderness'
WORLD.mkdir(exist_ok=True)
if not (WORLD/'level.dat').exists():
    level=n.load(SAVES/'Leaper Articulation Lab/level.dat');data=level['Data']
    data['LevelName']=n.String(WORLD.name)
    data['SpawnX']=n.Int(0);data['SpawnY']=n.Int(90);data['SpawnZ']=n.Int(16)
    data['ScheduledEvents']=n.List[n.Compound]()
    world=data['WorldGenSettings'];world['seed']=n.Long(2026090717);world['generate_features']=n.Byte(1)
    world['dimensions']['minecraft:overworld']['generator']=n.Compound({'type':n.String('minecraft:noise'),'settings':n.String('minecraft:overworld'),'biome_source':n.Compound({'type':n.String('minecraft:multi_noise'),'preset':n.String('minecraft:overworld')})})
    enabled=[n.String(str(v)) for v in data['DataPacks']['Enabled'] if not str(v).startswith('file:')]
    data['DataPacks']['Enabled']=n.List[n.String](enabled+[n.String('file/leaper_predator')])
    player=data.get('Player')
    if player is not None:
        player['Pos']=n.List[n.Double]([n.Double(0),n.Double(100),n.Double(16)])
        player['Motion']=n.List[n.Double]([n.Double(0)]*3)
        player['abilities']['flying']=n.Byte(1)
    level.save(WORLD/'level.dat')
pack=WORLD/'datapacks/leaper_predator';functions=pack/'data/leaper_predator/function';functions.mkdir(parents=True,exist_ok=True)
tags=pack/'data/minecraft/tags/function';tags.mkdir(parents=True,exist_ok=True)
(pack/'pack.mcmeta').write_text(json.dumps({'pack':{'pack_format':48,'description':'Predator field test in normally generated Minecraft terrain'}}))
(tags/'load.json').write_text(json.dumps({'values':['leaper_predator:load']}))
(functions/'load.mcfunction').write_text('scoreboard objectives add predator_lab dummy\nschedule function leaper_predator:start 5s replace\n')
build=['forceload add -64 -96 64 64','gamerule doMobSpawning false','gamerule doDaylightCycle false','gamerule doWeatherCycle false','time set day','weather clear','execute positioned 0 0 0 positioned over motion_blocking_no_leaves run fill -10 ~ -10 10 ~ 10 minecraft:stone_bricks','execute positioned 0 0 0 positioned over motion_blocking_no_leaves run fill -10 ~ -10 10 ~10 10 minecraft:air','scoreboard players set built predator_lab 1']
(functions/'build.mcfunction').write_text('\n'.join(build)+'\n')
start=['execute unless entity @a run schedule function leaper_predator:start 2s replace','execute if entity @a unless score built predator_lab matches 1 run function leaper_predator:build','gamemode creative @a','execute positioned 0 0 0 positioned over motion_blocking_no_leaves run tp @a 0 ~26 16 180 35','tellraw @a {"text":"Predator Wilderness — normal world generation. A small starting platform is the only terrain edit.","color":"gold"}']
(functions/'start.mcfunction').write_text('\n'.join(start)+'\n')
print(WORLD)
