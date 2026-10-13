#!/usr/bin/env python3
"""Promote one checkpoint only after matching native route, interaction and field checks."""
from pathlib import Path
import hashlib
import json
import math
import re
import statistics
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/leaper-predator'
RES = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'
JAVA = ROOT / 'minecraft_machines/neoforge/src/main/java/dev/ahmedhamedi/minecraft_machines/neoforge'

def source_files():
    native = [p for p in (ROOT/'minecraft_machines/neoforge/src/main/java').rglob('*.java')
              if p.name.startswith('MinecraftMachinesLeaper') or p.name == 'MinecraftMachinesQuadrupedChunkLease.java']
    common = list((ROOT/'minecraft_machines/common/src/main/java').rglob('Leaper*.java'))
    return sorted(native+common, key=lambda p: p.relative_to(ROOT).as_posix())

def source_hash():
    digest = hashlib.sha256()
    for path in source_files():
        digest.update((path.relative_to(ROOT).as_posix()+'\n').encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()

def main():
    read = lambda p: json.loads(p.read_text())
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    policy = re.search(r'VERSION = "([^"]+)"', (JAVA/'MinecraftMachinesLeaperPredatorPolicy.java').read_text())[1]
    candidate = read(OUT/'qualified-candidate.json')
    genome = candidate['genome']
    assert len(genome) == 20 and all(math.isfinite(v) for v in genome)
    implementation = source_hash()
    reports = {name: read(OUT/f'final-{name}.json') for name in ['certification','interaction','wilderness','natural-tour']}
    counts = {'certification':72, 'interaction':6, 'wilderness':6,'natural-tour':13}
    for name, report in reports.items():
        assert report['policy'] == policy, (name,'policy mismatch')
        assert report['genome'] == genome, (name,'checkpoint mismatch')
        assert report['implementation_sha256'] == implementation, (name,'source mismatch')
        assert len(report['trials']) == counts[name], (name,'incomplete suite')
        assert all(t['success'] for t in report['trials']), (name,'failed trials')
        assert report['contact_grip'] and report['physics_substeps']==4 and report['balance']==1
        assert 'BUILD SUCCESSFUL' in (OUT/f'final-{name}.log').read_text()
    routes = reports['certification']['trials']
    for t in routes:
        assert t['error'] < 1 and t['anchor_error'] <= .12 and t['limit_violation'] <= .15
        assert t['minimum_up'] >= .5 and 0 < t['arrival_seconds'] <= 100
    assert reports['certification']['physics_substeps'] == 4
    assert reports['certification']['balance'] == 1
    for t in reports['wilderness']['trials']:
        assert t['error'] < 1 and t['anchor_error'] <= .12 and t['limit_violation'] <= .15
    interaction = reports['interaction']['trials']
    stop = next(t for t in interaction if t['case']=='stop-resume-rough')
    assert stop['stop_drift'] < 1.5
    assert 'BUILD SUCCESSFUL' in (OUT/'final-surface-sensing.log').read_text()
    suites = [ET.parse(p).getroot() for p in (ROOT/'minecraft_machines/common/build/test-results/test').glob('TEST-*.xml')]
    unit_count = sum(int(s.attrib['tests']) for s in suites)
    assert unit_count >= 127 and all(int(s.attrib['failures'])+int(s.attrib['errors'])==0 for s in suites)
    baseline = read(OUT/'baseline-model.json')['resource_sha256']
    bindings = {name:sha(RES/name) for name in baseline}
    assert bindings == baseline, 'Previously approved model resources changed'
    training_names = ['unassisted-v2-horrific-predator-training.json','partial-v4-two-substep-horrific-predator-training.json','completed-v5-horrific-predator-training.json','completed-slope-training.json','completed-contact-training.json']
    searches = [read(OUT/name) for name in training_names]
    episodes = sum(len(d['episodes']) for d in searches)
    assert episodes == 1416
    selected = [t for t in searches[-1]['episodes'] if t['generation']==0 and t['candidate']==0]
    assert len(selected)==8 and all(t['genome']==genome and t['success'] for t in selected)
    evidence = {f'final-{name}.json':sha(OUT/f'final-{name}.json') for name in counts}
    evidence.update({name:sha(OUT/name) for name in training_names})
    pace_summary = {}
    for pace in ['horrific','fast','careful']:
        rows = [t for t in routes if t['course']['pace']==pace]
        pace_summary[pace] = {'routes':len(rows), 'mean_journey_speed':statistics.mean(t['progress_speed'] for t in rows),
                              'max_arrival_error':max(t['error'] for t in rows)}
    model = {
        'validated':True, 'policy':policy, 'contract':'leaper_12dof_predator_native_balance_v1',
        'genome':genome, 'parameters':candidate['parameters'], 'balance_gain':1, 'physics_substeps':4,'contact_grip':True,
        'implementation_sha256':implementation, 'resource_sha256':bindings, 'evidence_sha256':evidence,
        'architecture':'Layered footprint A*, continuous perceptive IK gait, finite joint motors, contact grip and gameplay attitude assistance',
        'learning':{'method':'Native Sable cross-entropy search over 20 bounded gait and actuator parameters',
                    'completed_optimization_episodes':episodes,'final_search_episodes':256,'final_search_population':8,
                    'final_search_generations':4,'training_seed':20260907911,'selected_generation_zero_based':0,
                    'selected_candidate_zero_based':0,'selected_training_tasks_passed':8,
                    'trained_base_policy':candidate['policy'],
                    'qualification_note':'The contact-search incumbent was retained after wider physical qualification. Later search winners remain archived. Route following, stopping and pace conditioning are engineered; this is not end-to-end neural RL.'},
        'physics':{'control_hz':20,'native_substeps_hz':80,'body_count':13,'limited_native_joints':12,
                   'yaw_joint_limit_rad':.9,'shoulder_joint_limit_rad':1.05,'knee_joint_limit_rad':1.2,
                   'foot_friction':1.65,'max_joint_motor_force':150000*candidate['parameters']['motor_power'],
                   'joint_slew_rad_s':candidate['parameters']['motor_slew'],
                   'attitude_assistance':'Finite native pitch/roll torque, all translation and yaw free; no vertical support motor or travel teleportation',
                   'foot_grip_constraints_enabled':True,'foot_grip_horizontal_force_cap':100000,
                   'foot_grip_stiffness':200000,'foot_grip_damping':10000,
                   'foot_grip_contact_tolerance':.32,'foot_grip_vertical_axis_free':True},
        'qualification':{'routes':72,'interaction_cases':6,'recorded_wilderness_journeys':6,'natural_dune_circuit_legs':13,'common_unit_tests':unit_count,
                         'arrival_hold_seconds':2,'mean_error':statistics.mean(t['error'] for t in routes),
                         'maximum_error':max(t['error'] for t in routes),'maximum_anchor_error':max(t['anchor_error'] for t in routes),
                         'maximum_joint_violation':max(t['limit_violation'] for t in routes),'stop_drift':stop['stop_drift'],
                         'pace_summary':pace_summary,
                         'scope':'One- and two-block risers at 45-degree overall grade, broken ground, hills, ridges, tree/pond/cliff detours, a ravine bridge and recorded natural dunes; three paces',
                         'limits':'No leap, swimming, vertical wall climbing or guaranteed dense-forest/narrow-cave access; plans use loaded terrain within a bounded local horizon.'}
    }
    payload=json.dumps(model,indent=2)+'\n'
    (RES/'predator.json').write_text(payload)
    (OUT/'model.json').write_text(payload)
    print(json.dumps(model['qualification'],indent=2))

if __name__=='__main__':
    main()
