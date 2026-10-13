#!/usr/bin/env python3
"""Promote only a sprint with matching physical-gait and destination qualifications."""
from pathlib import Path
import hashlib
import json
import math
import shutil

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / 'minecraft_machines/neoforge/build/leaper-scratch-gametest/leaper_learning'
OUT = ROOT / 'artifacts/leaper-horrific'
RES = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'


def read(path):
    return json.loads(path.read_text())


gait = read(RUN / 'horrific-gait-candidate.json')
nav = read(RUN / 'horrific-navigation-candidate.json')
points = read(RUN / 'horrific-points-trained.json')
motor = read(OUT / 'gait-qualification.json')
assert len(motor['trials']) == 16 and all(t['passed'] for t in motor['trials'])
assert all(t['speed'] > 3.5 for t in motor['trials']
           if t['scale'] == 1 and (t['command'][0] or t['command'][1]))
assert points['validated'] and points['count'] == points['successes'] == 48
assert gait['gait'] == nav['gait'] == points['gait'] == motor['gait']
assert nav['navigation'] == points['navigation']
assert motor['gait_policy'] == gait['policy'] == 'leaper_radial_sprint_ik_v1'
assert (motor['cadence'], motor['slew'], motor['power']) == (.8, 3.5, 2)
assert points['envelope'] == {k: motor[k] for k in ['gait_policy', 'cadence', 'slew', 'power']}
assert all(math.isfinite(x) for x in gait['gait'] + nav['navigation'])
trials = points['trials']
assert all(t['success'] and not t['failed'] and t['final_error'] <= .48
           and t['max_hold_error'] <= .48 for t in trials)
model = {
    'validated': True,
    'contract': gait['contract'],
    'gait_policy': gait['policy'],
    'navigation_policy': nav['policy'],
    'pace': 'horrific',
    'foot_friction': 1.65,
    'cadence_multiplier': motor['cadence'],
    'joint_slew_rad_s': motor['slew'],
    'motor_power_multiplier': motor['power'],
    'motor_stiffness': 200000,
    'motor_damping': 8000 * math.sqrt(2),
    'motor_force_cap': 300000,
    'minimum_up': .90,
    'geometry_sha256': hashlib.sha256((RES / 'geometry.json').read_bytes()).hexdigest(),
    'gait': gait['gait'],
    'navigation': nav['navigation'],
    'training': {
        'method': 'seeded cross-entropy parameter search in native Sable physics',
        'gait_seed': gait['seed'],
        'gait_generations': gait['generation'] + 1,
        'gait_population': gait['population'],
        'navigation_seed': nav['seed'],
        'navigation_generations': nav['generation'] + 1,
        'navigation_population': 8,
        'physics_control_seconds': .05,
        'destination_control_seconds': .1,
    },
    'qualification': {
        'arrivals': 48, 'passed': 48, 'five_second_hold': True,
        'mean_error_blocks': sum(t['final_error'] for t in trials) / 48,
        'max_error_blocks': max(t['final_error'] for t in trials),
        'mean_arrival_seconds': sum(t['arrival_seconds'] for t in trials) / 48,
        'max_tilt_degrees': math.degrees(math.acos(min(t['minimum_up'] for t in trials))),
        'max_anchor_error_blocks': max(t['anchor_error'] for t in trials),
        'gait_primitive_trials': 16,
        'ground': 'clear flat ground',
    },
}
OUT.mkdir(parents=True, exist_ok=True)
for name in ['gait-candidate', 'navigation-candidate', 'points-trained']:
    shutil.copy2(RUN / f'horrific-{name}.json', OUT / f'{name}.json')
payload = json.dumps(model, indent=2) + '\n'
(RES / 'horrific_navigation.json').write_text(payload)
(OUT / 'model.json').write_text(payload)
print(json.dumps(model['qualification'], indent=2))
