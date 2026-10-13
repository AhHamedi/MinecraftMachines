#!/usr/bin/env python3
"""Promote the precise-collision terrain controller from matching native evidence."""
from pathlib import Path
import hashlib
import json
import math
import re
import statistics
import xml.etree.ElementTree as ET
from package_leaper_predator import source_hash, ROOT

OUT = ROOT / 'artifacts/leaper-terrain-v2'
OLD = ROOT / 'artifacts/leaper-predator'
RES = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'
JAVA = ROOT / 'minecraft_machines/neoforge/src/main/java/dev/ahmedhamedi/minecraft_machines/neoforge'
read = lambda p: json.loads(p.read_text())
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    policy = re.search(r'VERSION = "([^"]+)"', (JAVA/'MinecraftMachinesLeaperPredatorPolicy.java').read_text())[1]
    contract = re.search(r'PREDATOR_CONTRACT = "([^"]+)"', (JAVA/'MinecraftMachinesLeaper.java').read_text())[1]
    candidate = read(OUT/'training-v1-candidate.json')
    genome = candidate['genome']
    assert len(genome) == 20 and all(math.isfinite(v) for v in genome)
    implementation = source_hash()
    counts = {'certification':72, 'interaction':6, 'wilderness':6, 'natural-tour':13, 'challenge':18}
    reports = {name: read(OUT/f'final-{name}.json') for name in counts}
    collision = sha(RES/'collision_geometry.json')
    for name, report in reports.items():
        assert report['policy'] == policy, (name,'policy mismatch')
        assert report['genome'] == genome, (name,'checkpoint mismatch')
        assert report['implementation_sha256'] == implementation, (name,'source mismatch')
        assert report['collision_sha256'] == collision, (name,'collision mismatch')
        assert len(report['trials']) == counts[name], (name,'incomplete suite')
        assert report['contact_grip'] and report['physics_substeps'] == 4 and report['balance'] == 1
        assert 'BUILD SUCCESSFUL' in (OUT/f'final-{name}.log').read_text()
        if name != 'challenge':
            assert all(t['success'] for t in report['trials']), (name,'regression')
    baseline = read(OUT/'baseline-challenge.json')['trials']
    challenge = reports['challenge']['trials']
    assert [t['course'] for t in challenge] == [t['course'] for t in baseline]
    # New stress cases remain visible; they are not silently dropped from qualification.
    assert sum(t['success'] for t in challenge) >= 17, 'Terrain improvement gate failed'
    routes = reports['certification']['trials']
    for t in routes:
        assert t['error'] < 1 and t['anchor_error'] <= .12 and t['limit_violation'] <= .15
        assert t['minimum_up'] >= .5 and 0 < t['arrival_seconds'] <= 100
    stop = next(t for t in reports['interaction']['trials'] if t['case']=='stop-resume-rough')
    assert stop['stop_drift'] < 1.5
    assert 'BUILD SUCCESSFUL' in (OUT/'final-surface-sensing.log').read_text()
    suites = [ET.parse(p).getroot() for p in (ROOT/'minecraft_machines/common/build/test-results/test').glob('TEST-*.xml')]
    units = sum(int(s.attrib['tests']) for s in suites)
    assert units >= 129 and all(int(s.attrib['failures'])+int(s.attrib['errors'])==0 for s in suites)
    geometry_check = read(OUT/'collision-verification.json')
    assert geometry_check['collision_sha256'] == collision
    assert all(c['mismatches'] == 0 for c in geometry_check['cells'])
    oldmodel = read(OLD/'model.json')
    bindings = {name:sha(RES/name) for name in oldmodel['resource_sha256']}
    assert bindings == oldmodel['resource_sha256'], 'Legacy geometry or flat policy changed'
    bindings['collision_geometry.json'] = collision
    training = read(OUT/'training-v1.json')
    assert len(training['episodes']) == 256
    selected = [t for t in training['episodes'] if t['generation']==3 and t['candidate']==3]
    assert len(selected)==8 and all(t['success'] and t['genome']==genome for t in selected)
    oldroutes = read(OLD/'final-certification.json')['trials']
    assert [t['course'] for t in routes] == [t['course'] for t in oldroutes]
    speeds = {}
    for pace in ['horrific','fast','careful']:
        pairs = [(a,b) for a,b in zip(oldroutes,routes) if b['course']['pace']==pace]
        # Journey starts after 80 settle ticks, and completion includes 39 hold ticks.
        before = [a['ticks']-119 for a,b in pairs]
        after = [b['ticks']-119 for a,b in pairs]
        speeds[pace] = {'routes':len(pairs),
            'median_paired_speed_gain_percent':100*(statistics.median(a/b for a,b in zip(before,after))-1),
            'total_journey_time_reduction_percent':100*(1-sum(after)/sum(before)),
            'mean_journey_speed':statistics.mean(b['progress_speed'] for a,b in pairs),
            'maximum_error':max(b['error'] for a,b in pairs)}
    evidence_names = [f'final-{n}.json' for n in counts] + ['training-v1.json','training-v1-candidate.json','collision-verification.json','baseline-challenge.json']
    model = {
        'validated':True, 'policy':policy, 'contract':contract,
        'genome':genome,'parameters':candidate['parameters'],
        'balance_gain':1,'physics_substeps':4,'contact_grip':True,
        'implementation_sha256':implementation,'resource_sha256':bindings,
        'evidence_sha256':{n:sha(OUT/n) for n in evidence_names},
        'architecture':'Layered footprint A*, selected-foot corridor sensing, continuous terrain-conditioned IK gait, bounded native servos and finite gameplay contact/attitude assistance',
        'learning':{'method':'Native Sable cross-entropy optimization of 20 bounded gait and actuator parameters',
            'completed_new_episodes':256,'population':8,'generations':4,'training_seed':202609071337,
            'selected_generation_zero_based':3,'selected_candidate_zero_based':3,'selected_training_tasks_passed':8,
            'training_implementation_sha256':training['implementation_sha256'],
            'qualification_note':'Cadence conditioning, braking and gameplay lifecycle were refined after optimization and then separately qualified. Planning, sensing and IK are engineered; this is not end-to-end neural RL.'},
        'physics':{**oldmodel['physics'],
            'max_joint_motor_force':150000*candidate['parameters']['motor_power'],
            'joint_slew_rad_s':candidate['parameters']['motor_slew'],
            'precise_collision':'Disjoint cuboid union of the authored blocky model; independently checked against OBJ occupancy',
            'collision_partition_checks':geometry_check['total_samples']},
        'qualification':{'routes':72,'interaction_cases':6,'recorded_wilderness_journeys':6,'natural_dune_circuit_legs':13,
            'common_unit_tests':units,'stress_cases':18,'stress_passed':sum(t['success'] for t in challenge),
            'baseline_stress_passed':sum(t['success'] for t in baseline),
            'stress_failures':[{'course':t['course'],'reason':t['reason'],'error':t['error']} for t in challenge if not t['success']],
            'arrival_hold_seconds':2,'mean_error':statistics.mean(t['error'] for t in routes),
            'maximum_error':max(t['error'] for t in routes),'maximum_anchor_error':max(t['anchor_error'] for t in routes),
            'maximum_joint_violation':max(t['limit_violation'] for t in routes),'stop_drift':stop['stop_drift'],
            'pace_comparison':speeds,
            'scope':'Original 97 native cases plus 18 new stress routes: three-block risers/ridges, irregular rubble, cross-slopes and tightly spaced broken ground.',
            'limits':'Stress suite is a development benchmark, not unseen validation. No leap, swimming, wall climbing or universal terrain guarantee; loaded bounded-horizon route planning and large physical footprint.'}
    }
    payload=json.dumps(model,indent=2)+'\n'
    (OUT/'model.json').write_text(payload)
    (RES/'predator.json').write_text(payload)
    print(json.dumps(model['qualification'],indent=2))

if __name__=='__main__':
    main()
