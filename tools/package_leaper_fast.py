#!/usr/bin/env python3
"""Promote a Leaper candidate only after physical gait and destination qualification."""
from pathlib import Path
import hashlib
import json
import math

ROOT=Path(__file__).resolve().parents[1]
RUN=ROOT/'minecraft_machines/neoforge/build/leaper-scratch-gametest/leaper_learning'
OUT=ROOT/'artifacts/leaper-brisk'
RES=ROOT/'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'
read=lambda p: json.loads(p.read_text())
gait=read(RUN/'brisk-gait-candidate.json')
nav=read(RUN/'brisk-navigation-candidate.json')
qualification=read(RUN/'brisk-points-trained.json')
primitives=read(OUT/'gait-qualification.json')
assert len(primitives['trials'])==16 and all(t['passed'] for t in primitives['trials'])
assert all(t['speed'] > 1.0 for t in primitives['trials'] if t['scale']==1 and (t['command'][0] or t['command'][1]))
assert qualification['validated'] and qualification['count']==48 and qualification['successes']==48
assert qualification['gait']==gait['gait']==nav['gait']==primitives['gait']
assert qualification['navigation']==nav['navigation']
assert all(math.isfinite(x) for x in gait['gait']+nav['navigation'])
trials=qualification['trials']
assert all(t['success'] and not t['failed'] and t['final_error']<=.48 and t['max_hold_error']<=.48 for t in trials)
mean=lambda values:sum(values)/len(values)
model={
    'validated':True,
    'contract':gait['contract'],
    'gait_policy':gait['policy'],
    'navigation_policy':nav['policy'],
    'foot_friction':1.65,
    'pace':'fast',
    'cadence_multiplier':3.0,
    'joint_slew_rad_s':1.8,
    'geometry_sha256':hashlib.sha256((RES/'geometry.json').read_bytes()).hexdigest(),
    'gait':gait['gait'],
    'navigation':nav['navigation'],
    'training':{'method':'seeded cross-entropy parameter search in native Sable physics',
                'gait_seed':gait['seed'],'gait_generations':gait['generation']+1,'gait_population':gait['population'],
                'navigation_seed':nav['seed'],'navigation_generations':nav['generation']+1,'navigation_population':8,
                'physics_control_seconds':.05,'destination_control_seconds':.1},
    'qualification':{'arrivals':48,'passed':48,'five_second_hold':True,
                     'mean_error_blocks':mean([t['final_error'] for t in trials]),
                     'max_error_blocks':max(t['final_error'] for t in trials),
                     'mean_arrival_seconds':mean([t['arrival_seconds'] for t in trials]),
                     'max_tilt_degrees':math.degrees(math.acos(min(t['minimum_up'] for t in trials))),
                     'max_anchor_error_blocks':max(t['anchor_error'] for t in trials),
                     'gait_primitive_trials':16,'ground':'clear flat ground'}
}
(RES/'fast_navigation.json').write_text(json.dumps(model,indent=2)+'\n')
OUT.mkdir(parents=True,exist_ok=True)
for name in ['gait-candidate','navigation-candidate','points-trained']:
    (OUT/(name+'.json')).write_bytes((RUN/('brisk-'+name+'.json')).read_bytes())
(OUT/'model.json').write_text(json.dumps(model,indent=2)+'\n')
print(json.dumps(model['qualification'],indent=2))
