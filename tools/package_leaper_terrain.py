#!/usr/bin/env python3
"""Promote a terrain controller only with matching native qualification evidence."""
from pathlib import Path
import hashlib
import json
import math
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/leaper-terrain'
RES = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'
JAVA = ROOT / 'minecraft_machines/neoforge/src/main/java/dev/ahmedhamedi/minecraft_machines/neoforge'
policy = re.search(r'VERSION = "([^"]+)"', (JAVA/'MinecraftMachinesLeaperTerrainControl.java').read_text())[1]
read = lambda p: json.loads(p.read_text())
reports = {name: read(OUT/f'v5-{name}.json') for name in ['development','qualification','holdout','routes']}
counts = {'development':24,'qualification':16,'holdout':24,'routes':8}
genome = reports['holdout']['genome']
trials = []
for name, report in reports.items():
    assert report['policy'] == policy and report['genome'] == genome
    assert len(report['trials']) == counts[name]
    for t in report['trials']:
        assert t['success'] and not t['failed'] and t['error'] <= .48
        assert t['minimum_up'] >= .85 and t['anchor_error'] <= .04 and t['limit_violation'] <= .04
        assert math.isfinite(t['arrival_seconds']) and t['arrival_seconds'] > 0
    trials.extend(report['trials'])
interaction = read(OUT/'v5-interaction.json')
assert interaction['validated'] and len(interaction['trials']) == 4 and all(t['passed'] for t in interaction['trials'])
assert 'BUILD SUCCESSFUL' in (OUT/'surface-sensing.log').read_text()
assert len(genome) == 8 and all(math.isfinite(x) for x in genome)
bindings = {name: hashlib.sha256((RES/name).read_bytes()).hexdigest() for name in ['geometry.json','navigation.json','fast_navigation.json','horrific_navigation.json']}
model = {
    'validated': True, 'contract': 'leaper_12dof_v1', 'policy': policy,
    'genome': genome, 'parameters': reports['holdout']['parameters'], 'resource_sha256': bindings,
    'architecture': 'collision-surface route planning and perceptive foot placement around fixed trained gait checkpoints',
    'selection': {'method':'qualified reference parameters; terrain search candidates did not improve independent robustness',
                  'completed_search_episodes':192, 'search_seed':2026090731, 'promoted_search_candidate':False},
    'physics': {'control_seconds':.05,'navigation_seconds':.1,'free_bodies':13,'limited_joints':12,
                'foot_friction':1.65,'climb_power':2,'climb_slew_rad_s':2.2,'motor_force_cap':300000},
    'qualification': {'passed':len(trials),'count':len(trials),'suite_counts':counts,
                      'interaction_tests':4,'hold_seconds':5,
                      'mean_error_blocks':sum(t['error'] for t in trials)/len(trials),
                      'max_error_blocks':max(t['error'] for t in trials),
                      'maximum_tilt_degrees':math.degrees(math.acos(min(t['minimum_up'] for t in trials))),
                      'max_anchor_error_blocks':max(t['anchor_error'] for t in trials),
                      'max_joint_violation_rad':max(t['limit_violation'] for t in trials),
                      'scope':'broad steps up to one block per riser, tested total elevation changes up to six blocks, half slabs, broken ground, cross slopes and wall/pit detours',
                      'limitations':'no jumping, vertical wall climbing, swimming, narrow bridges or general stacked cave navigation'}
}
payload = json.dumps(model, indent=2)+'\n'
(RES/'terrain.json').write_text(payload)
(OUT/'model.json').write_text(payload)
print(json.dumps(model['qualification'],indent=2))
