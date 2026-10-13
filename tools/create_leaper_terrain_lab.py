#!/usr/bin/env python3
"""Create a separate review world; preserve the existing flat lab and all robot checkpoints."""
from pathlib import Path
import copy
import json
import nbtlib

ROOT=Path(__file__).resolve().parents[1]
SAVES=ROOT/'minecraft_machines/neoforge/runs/client/saves'
WORLD=SAVES/'Leaper Terrain Lab'
WORLD.mkdir(exist_ok=True)
if not (WORLD/'level.dat').exists():
    level=nbtlib.load(SAVES/'Leaper Articulation Lab/level.dat')
    data=level['Data']
    data['LevelName']=nbtlib.String('Leaper Terrain Lab')
    data['SpawnX']=nbtlib.Int(0);data['SpawnY']=nbtlib.Int(-58);data['SpawnZ']=nbtlib.Int(16)
    data['ScheduledEvents']=nbtlib.List[nbtlib.Compound]()
    enabled=[nbtlib.String(str(v)) for v in data['DataPacks']['Enabled'] if not str(v).startswith('file:')]
    data['DataPacks']['Enabled']=nbtlib.List[nbtlib.String](enabled+[nbtlib.String('file/leaper_terrain')])
    player=data.get('Player')
    if player is not None:
        player['Pos']=nbtlib.List[nbtlib.Double]([nbtlib.Double(10),nbtlib.Double(-53),nbtlib.Double(-13)])
        player['Motion']=nbtlib.List[nbtlib.Double]([nbtlib.Double(0)]*3)
    level.save(WORLD/'level.dat')

pack=WORLD/'datapacks/leaper_terrain'
functions=pack/'data/leaper_terrain/function';functions.mkdir(parents=True,exist_ok=True)
tags=pack/'data/minecraft/tags/function';tags.mkdir(parents=True,exist_ok=True)
(pack/'pack.mcmeta').write_text(json.dumps({'pack':{'pack_format':48,'description':'Leaper terrain navigation review'}}))
(tags/'load.json').write_text(json.dumps({'values':['leaper_terrain:load']}))
(functions/'load.mcfunction').write_text('scoreboard objectives add leaper_lab dummy\nschedule function leaper_terrain:start 5s replace\n')
build=['forceload add -96 -128 96 40','fill -96 -60 -128 96 -60 40 minecraft:smooth_stone']
# Four broad full-block risers, a plateau, then the matching descent.
for i in range(4):
    build.append(f'fill -20 {-59+i} {-84+6*i} 20 {-59+i} {-16-6*i} minecraft:stone_bricks')
# A separate six-block stepped hill.
for i in range(6):
    build.append(f'fill -76 {-59+i} {-82+5*i} -36 {-59+i} {-16-5*i} minecraft:polished_andesite')
# Half-block irregular terrain with deterministic wide pads.
for ix in range(10):
    for iz in range(10):
        x=38+4*ix;z=-18-4*iz;h=(ix*31+iz*17+7)%3
        if h:
            block='minecraft:smooth_stone_slab[type=bottom]' if h==1 else 'minecraft:mossy_stone_bricks'
            build.append(f'fill {x} -59 {z-3} {x+3} -59 {z} {block}')
build += ['fill 46 -59 -90 59 -54 -85 minecraft:deepslate_bricks','time set day','weather clear','gamerule doDaylightCycle false','gamerule doWeatherCycle false','gamerule doMobSpawning false','scoreboard objectives add leaper_lab dummy','scoreboard players set built leaper_lab 1']
(functions/'build.mcfunction').write_text('\n'.join(build)+'\n')
start=['execute unless entity @a run schedule function leaper_terrain:start 2s replace','execute if entity @a unless score built leaper_lab matches 1 run function leaper_terrain:build','execute if entity @a run gamemode creative @a','execute if entity @a run tp @a 0 -58 16 180 0','execute as @a at @s run mm leaper spawn','execute as @a run mm leaper pace horrific','execute as @a run mm leaper terrain auto','execute as @a run mm leaper stick','execute if entity @a run tp @a 10 -53 -13 38 20','execute as @a run mm leaper unwatch','tellraw @a {"text":"Leaper Terrain Lab — right-click a supported surface; Shift-right-click to stop.","color":"gold"}']
buttons=[('Careful','/mm leaper pace careful','aqua'),('Standard','/mm leaper pace standard','aqua'),('Horrific','/mm leaper pace horrific','gold'),('Hilltop','/mm leaper goto 0.5 -50.5','green'),('Across the hill','/mm leaper goto 0.5 -98.5','green'),('Follow','/mm leaper watch','aqua'),('Free camera','/mm leaper unwatch','aqua'),('Stop','/mm leaper stop','red')]
start.append('tellraw @a '+json.dumps([{'text':f'[{label}] ','color':color,'clickEvent':{'action':'run_command','value':command}} for label,command,color in buttons]))
(functions/'start.mcfunction').write_text('\n'.join(start)+'\n')
source=ROOT/'tools/leaper_terrain_lab';source.mkdir(exist_ok=True)
for file in functions.glob('*.mcfunction'):(source/file.name).write_bytes(file.read_bytes())
print(WORLD)
