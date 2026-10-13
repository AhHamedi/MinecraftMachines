#!/usr/bin/env python3
"""Validate frozen v11 evidence; write models only with an explicit --promote.

Default inputs: PREFIX-{freeze,model,settings,benchmark,interactions,score}.json,
PREFIX-source.zip, and QUAL_PREFIX-{certification,challenge,interaction,wilderness,
natural-tour}.json with corresponding successful native .log files.

Freeze references are paths relative to the freeze directory (or repo-relative
artifacts/... paths). Require genome, settings, fixture_manifest,
baseline_benchmark, baseline_interactions. Each reference is {path, sha256}, or
a string with a sibling KEY_sha256 field. implementation_sha256 binds the exact
current source ZIP. The critic may create the held-out manifest after source
freeze; this packager never chooses fixtures or substitutes development runs.
Both interaction variants must use pending-support-transition-v1 on that same
source, with explicit baseline-zero or selected highstep_settings respectively.

Promotion also requires PREFIX-critic-approval.json (or --critic-approval), with
release_approved:true, implementation_sha256, all manual_gates from the scorer
set true, and evidence_sha256 binding the evaluated inputs. Numeric scoring
alone deliberately does not claim independent release approval.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.util
import json
import math
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from package_leaper_predator import ROOT, source_files, source_hash

OUT = ROOT / 'artifacts/leaper-highstep'
RES = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'
JAVA = ROOT / 'minecraft_machines/neoforge/src/main/java/dev/ahmedhamedi/minecraft_machines/neoforge'
V10 = ROOT / 'artifacts/leaper-clearance/model.json'
V10_SHA = '8f9359bbbd5d3162ccd59b58a8dcadf224cdc3d9a0eab61a56c0372ebb586f1e'
PADS_SHA = 'd62ace2ee7df28fad25c9f7e63adc3560f11c6a8762149976ddd14c051f0b9a4'
POLICY = 'leaper_predator_highstep_v11'
SCORER_SHA = '46c4fd7254edfbda74ea9e27f27e807127a0d112eadfae29b73acee1767050b2'
RUBRIC_SHA = '09671a0323fe3f3796c73d4e482b1274d0ddc7ff770b5c98b8895e6712e6accf'
INTERACTION_VERSION = 'pending-support-transition-v1'
INTERACTION_SPEC_SHA = '7966faabd9a9e8333f690a6fd1cde047788215f6b7a7b4536b0727e6e2b58aa7'
COUNTS = {'certification': 72, 'challenge': 18, 'interaction': 6,
          'wilderness': 6, 'natural-tour': 13}
BOOL_SETTINGS = ('terrain_aware_sweep', 'adaptive_lift', 'world_swing', 'fresh_support',
                 'post_clearance_lift', 'pace_lift', 'exposed_surfaces',
                 'adaptive_footholds', 'world_swing_height')
SETTINGS = (*BOOL_SETTINGS, 'extra_lift', 'shoulder_minimum', 'shoulder_maximum', 'knee_maximum',
            'slow_damping', 'flat_cadence', 'clear_ground_gain', 'clearance_pause', 'stance_compression')
BASE_SETTINGS = dict.fromkeys(BOOL_SETTINGS, False) | {
    'extra_lift': 0, 'shoulder_minimum': -1.05, 'shoulder_maximum': 1.05, 'knee_maximum': 1.2,
    'slow_damping': 0, 'flat_cadence': 0, 'clear_ground_gain': 0, 'clearance_pause': 0,
    'stance_compression': 0}
PHYSICS_CONFIG = {'solverIterations': 18, 'pgsIterations': 2, 'stabilizationIterations': 2,
                  'contactSpringDampingRatio': 5.0, 'contactSpringFrequency': 40.0,
                  'minDynamicBodiesPerIsland': 128, 'substepsPerTick': 4}
FINAL_APRON = {'x': 'floor(goal.x)-8..floor(goal.x)+8',
               'z': 'floor(goal.z)-8..floor(goal.z)+8',
               'surface_y': 7, 'clear_y': [7, 8, 9, 10]}
METRICS = ('arrival_seconds', 'backward_travel', 'kickbacks_over_4bps',
           'worst_reverse_speed', 'longest_stall_seconds', 'anchor_error', 'limit_violation')
GATES = {'score_at_least_8_5', 'routes_at_least_20_of_21', 'all_nine_ascent_configurations',
         'all_three_continuous_legs', 'no_reported_structural_failure', 'all_six_interactions',
         'stop_drift_below_1_5', 'horrific_terrain_speed_gain_at_least_20_percent',
         'horrific_flat_speed_retention_at_least_85_percent', 'terrain_backward_travel_not_increased',
         'terrain_fast_reverse_events_not_increased'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def label(path):
    path = path.resolve()
    return str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)


def number(value, where, minimum=None):
    require(type(value) in (float, int) and math.isfinite(value), f'{where}: finite number required')
    require(minimum is None or value >= minimum, f'{where}: below {minimum}')
    return value


def finite_tree(value, where='JSON'):
    if isinstance(value, dict):
        for key, child in value.items():
            finite_tree(child, f'{where}.{key}')
    elif isinstance(value, list):
        for i, child in enumerate(value):
            finite_tree(child, f'{where}[{i}]')
    elif type(value) in (int, float):
        number(value, where)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f'Duplicate JSON key: {key}')
        result[key] = value
    return result


def read(path):
    value = json.loads(path.read_text(), object_pairs_hook=unique_object)
    finite_tree(value, label(path))
    require(isinstance(value, dict), f'{path}: expected object')
    return value


def suffixed(prefix, *names):
    paths = [Path(str(prefix) + '-' + name) for name in names]
    existing = [p for p in paths if p.exists()]
    require(len(existing) <= 1, f'Ambiguous input aliases: {existing}')
    return existing[0] if existing else paths[0]


def resolve_reference(freeze_path, freeze, key):
    value = freeze[key]
    filename = value['path'] if isinstance(value, dict) else value
    expected = value.get('sha256') if isinstance(value, dict) else freeze.get(key + '_sha256')
    require(isinstance(filename, str) and re.fullmatch(r'[0-9a-f]{64}', expected or ''),
            f'Freeze must bind {key} path and SHA256')
    path = Path(filename)
    if not path.is_absolute():
        path = (ROOT if filename.startswith('artifacts/') else freeze_path.parent) / path
    require(sha(path) == expected, f'Freeze {key}: file hash mismatch')
    return path.resolve()


def archive_sources(path, expected):
    names = [p.relative_to(ROOT).as_posix() for p in source_files()]
    digest, hashes = hashlib.sha256(), {}
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(names) and set(archive.namelist()) == set(names),
                'Source ZIP has missing, duplicate or extra files')
        for name in names:
            data = archive.read(name)
            require(data == (ROOT / name).read_bytes(), f'Archived/current source differs: {name}')
            digest.update((name + '\n').encode())
            digest.update(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
    require(digest.hexdigest() == expected == source_hash(), 'Frozen implementation hash mismatch')
    return hashes


def settings(data):
    require(set(data) == set(SETTINGS), 'Settings are missing fields or contain unvalidated options')
    require(all(type(data[k]) is bool for k in BOOL_SETTINGS), 'Settings flags must be booleans')
    require(0 <= number(data['extra_lift'], 'extra_lift') <= 3, 'Lift outside 0..3')
    require(0 <= number(data['slow_damping'], 'slow_damping') <= .15, 'slow_damping outside 0..0.15')
    require(0 <= number(data['flat_cadence'], 'flat_cadence') <= .4, 'flat_cadence outside 0..0.4')
    require(0 <= number(data['clear_ground_gain'], 'clear_ground_gain') <= 1, 'clear_ground_gain outside 0..1')
    require(0 <= number(data['clearance_pause'], 'clearance_pause') <= .6, 'clearance_pause outside 0..0.6')
    require(0 <= number(data['stance_compression'], 'stance_compression') <= .6, 'stance_compression outside 0..0.6')
    require(-1.6 <= number(data['shoulder_minimum'], 'shoulder_minimum') <= -1.05,
            'shoulder_minimum outside -1.6..-1.05')
    require(1.05 <= number(data['shoulder_maximum'], 'shoulder_maximum') <= 1.5,
            'shoulder_maximum outside 1.05..1.5')
    require(1.2 <= number(data['knee_maximum'], 'knee_maximum') <= 1.7,
            'knee_maximum outside 1.2..1.7')
    return data


def match_settings(recorded, expected, where):
    require(isinstance(recorded, dict) and set(recorded) == set(SETTINGS), f'{where}: incomplete settings')
    for key in SETTINGS:
        if key in BOOL_SETTINGS:
            require(type(recorded[key]) is bool, f'{where}.{key}: boolean required')
        else:
            number(recorded[key], f'{where}.{key}')
    require(recorded == expected, f'{where}: settings mismatch')


def parameters(model, source):
    def group(pattern):
        match = re.search(pattern, source, re.S)
        require(match is not None, f'Policy source contract missing: {pattern}')
        return match[1]
    genome = model['genome']
    require(isinstance(genome, list) and len(genome) == 20, 'Expected a 20-parameter genome')
    for value in genome:
        number(value, 'genome')
    low, high = [[float(v) for v in group(rf'\b{name}\s*=\s*\{{([^}}]+)\}}').split(',')]
                 for name in ('LOW', 'HIGH')]
    names = re.findall(r'"([^"]+)"', group(r'\bNAMES\s*=\s*\{([^}]+)\}'))
    require(len(low) == len(high) == len(names) == len(set(names)) == 20, 'Policy decoder changed')
    decoded = {n: a + (b - a) * (.5 + .5 * math.tanh(g))
               for n, a, b, g in zip(names, low, high, genome)}
    require(set(model['parameters']) == set(decoded), 'Named parameter keys mismatch')
    require(all(math.isclose(number(model['parameters'][n], n), v, rel_tol=1e-12, abs_tol=1e-12)
                for n, v in decoded.items()), 'Named parameters do not decode from selected genome')
    return decoded


def metadata(report, genome, implementation, collision, expected_settings, benchmark=False):
    require(report['genome'] == genome, 'Report genome differs from selection')
    require(report['implementation_sha256'] == implementation, 'Report source differs from freeze')
    require(report['collision_sha256'] == collision, 'Report collision geometry mismatch')
    require(number(report['physics_substeps'], 'substeps') == 4
            and number(report['balance'], 'balance') == 1 and report['contact_grip'] is True,
            'Report requires four substeps, balance1, grip enabled')
    if not benchmark:
        require(report['policy'] == POLICY, 'Qualification policy must be v11')
    recorded = report.get('highstep_settings')
    if benchmark:
        flat = {k: report[k] for k in SETTINGS}
        match_settings(flat, expected_settings, 'Benchmark')
        match_settings(recorded, flat, 'Actual benchmark control')
        require(report['launch_profile'] == 'horrific' and report['control_hz'] == 20,
                'Benchmark launch/control rate mismatch')
        require(report['physics_config'] == PHYSICS_CONFIG, 'Actual benchmark physics mismatch')
    else:
        match_settings(recorded, expected_settings, 'Qualification')
    if 'physics_config' in report:
        require(report['physics_config'] == PHYSICS_CONFIG, 'Report actual physics mismatch')
    for row in [*report.get('trials', []), *report.get('continuous', [])]:
        if 'highstep_settings' in row or (not benchmark and 'course' in row):
            match_settings(row.get('highstep_settings'), expected_settings, 'Actual child control')
        for key in SETTINGS:
            if key in row:
                require(type(row[key]) is type(expected_settings[key])
                        or type(row[key]) in (int, float) and type(expected_settings[key]) in (int, float),
                        f'Report row setting {key}: wrong type')
                require(row[key] == expected_settings[key], f'Report row setting {key} contradicts frozen settings')
        for key in ('early_clearance', 'coordinated_swing'):
            if key in row:
                require(row[key] is True, f'Report row has disabled {key}')


def vector(value, length, where):
    require(isinstance(value, list) and len(value) == length, f'{where}: length {length} required')
    for item in value:
        number(item, where)


def masses(row):
    data = row['masses']
    expected = [27] + [1, 8, 6] * 4
    require(len(data) == 13, 'Missing body mass records')
    for body, mass in zip(data, expected):
        require(number(body['mass'], 'body mass') == mass, 'Ballast or body mass changed')
        require(math.isclose(number(body['inverse_mass'], 'inverse mass'), 1 / mass, rel_tol=1e-12),
                'Inverse mass mismatch')
        vector(body['center'], 3, 'center of mass')
        vector(body['inertia'], 9, 'inertia')
        require(body.get('finite', True) is True, 'Invalid body mass tracker')
        a = body['inertia']
        require(all(math.isclose(a[i], a[j], abs_tol=1e-8) for i, j in ((1, 3), (2, 6), (5, 7))),
                'Asymmetric inertia tensor')
        determinant = a[0]*(a[4]*a[8]-a[5]*a[7])-a[1]*(a[3]*a[8]-a[5]*a[6])+a[2]*(a[3]*a[7]-a[4]*a[6])
        require(a[0] > 0 and a[0]*a[4]-a[1]*a[3] > 0 and determinant > 0, 'Invalid inertia tensor')
    require(sum(b['mass'] for b in data) == 87, 'Rig total mass differs from 87')


def outcome(row, where, benchmark=False):
    require(type(row['success']) is bool and isinstance(row['reason'], str), f'{where}: missing outcome')
    require((row['reason'] == '') == row['success'], f'{where}: outcome/reason mismatch')
    if benchmark:
        for key in ('attempted', 'physical_failure', 'finite_state', 'valid'):
            require(type(row.get(key)) is bool, f'{where}: missing {key}')
        require(type(row['max_fallen_ticks']) is int and row['max_fallen_ticks'] >= 0, 'Invalid fallen duration')
        if row['finite_state'] is False or row['valid'] is False:
            require(row['physical_failure'] is True and not row['success'], 'Invalid state not marked as failure')
    require(row.get('attempted', True) is True, f'{where}: unattempted case cannot qualify')
    if row['success']:
        require(row.get('physical_failure', False) is False and row.get('finite_state', True) is True
                and row.get('valid', True) is True and row.get('max_fallen_ticks', 0) <= 30,
                f'{where}: success conceals physical failure')
    for key in ('error', 'anchor_error', 'limit_violation', 'arrival_seconds', 'seconds', 'stop_drift', *METRICS):
        if key in row:
            number(row[key], f'{where}.{key}', 0)


def benchmark_rows(report, manifest, candidate):
    require(len(report['trials']) == len(manifest['routes']) == 21 and len(report['continuous']) == 3,
            'Benchmark must retain 21 routes and three continuous legs')
    require(manifest['continuous']['legs'] == ['ascent', 'descent', 'ascent'], 'Continuous order changed')
    expected_keys = {(f, c) for f in range(7) for c in range(3)}
    keys, slots = set(), set()
    for row, fixture in zip(report['trials'], manifest['routes']):
        key = row['family'], row['configuration']
        require(key == (fixture['family'], fixture['configuration']) and key not in keys, 'Fixture identity/order mismatch')
        keys.add(key)
        require(row['slot'] == fixture['slot'] and row['slot'] not in slots, 'Fixture slot mismatch/duplicate')
        slots.add(row['slot'])
        for field in ('kind', 'rise', 'tread', 'angle', 'heading', 'pace', 'distance', 'seed'):
            require(row['course'][field] == fixture[field], f'Fixture {key}: {field} mismatch')
        require(row['course']['distance'] == 48 and row['course']['pace'] == ('careful' if key[1] == 2 else 'horrific'),
                'Frozen score distance/pace assignment changed')
    require(keys == expected_keys, 'Missing family/configuration')
    require(manifest['continuous']['slot'] not in slots, 'Continuous fixture slot reused')
    for index, row in enumerate(report['continuous']):
        require(row['leg'] == index and row['leg_direction'] == manifest['continuous']['legs'][index]
                and row['slot'] == manifest['continuous']['slot'], 'Continuous fixture identity mismatch')
        for field in ('kind', 'rise', 'tread', 'angle', 'heading', 'pace', 'distance', 'seed'):
            require(row['course'][field] == manifest['continuous'][field], f'Continuous {field} mismatch')
    for section in ('trials', 'continuous'):
        for index, row in enumerate(report[section]):
            where = f'{section}[{index}]'
            if row.get('attempted') is False:
                require(not candidate and section == 'continuous' and index > 0 and not row['success']
                        and not report[section][index-1]['success'] and row['reason'] == 'prior continuous leg failed',
                        'Unexpected skipped benchmark case')
                for key in METRICS:
                    number(row[key], where + '.' + key, 0)
                continue
            outcome(row, where, benchmark=True)
            masses(row)
            ticks = row['ticks']
            require(type(ticks) is int and ticks >= 0, 'Invalid control-tick count')
            trace = row['history']
            expected_ticks = list(range(1, ticks + 1))
            if ticks == 0 and row['physical_failure']:
                expected_ticks = [0]
            require([h['tick'] for h in trace] == expected_ticks, 'Missing or duplicated 20Hz history')
            if row['success']:
                start = row['arrival_window_start_tick']
                settle = 80 if section == 'trials' or index == 0 else 0
                require(row['initial_settle_ticks'] == settle and ticks == start + 39,
                        'Arrival hold/initial settling changed')
                require(0 < row['arrival_seconds'] <= 100 and math.isclose(row['arrival_seconds'], (start-settle)*.05, abs_tol=1e-9),
                        'Arrival timestamp mismatch')
                require(all(h['arrived'] is True and number(h['error'], 'arrival error') <= 1
                            and number(h['speed'], 'arrival speed') <= .5 for h in trace[-40:]),
                        'Incomplete actual arrival window')
            # Timeouts still need complete measured/requested/bounded telemetry. Explicit
            # nonfinite physical failures retain their diagnostic strings for the scorer.
            if row['finite_state']:
                for h in trace:
                    vector(h['position'], 3, 'body history')
                    vector(h['core_orientation'], 4, 'core orientation x/y/z/w')
                    for key in ('speed', 'error', 'anchor'):
                        number(h[key], key, 0)
                    require(len(h['feet']) == 4, 'Missing toe history')
                    for foot in h['feet']:
                        vector(foot['position'], 3, 'toe history')
                        vector(foot['joint_angles'], 3, 'actual yaw/shoulder/knee angles')
                        vector(foot['raw_requested_angles'], 3, 'raw IK yaw/shoulder/knee angles')
                        vector(foot['bounded_target_angles'], 3, 'bounded motor yaw/shoulder/knee angles')
                        for key in ('departure', 'landing', 'obstacle'):
                            number(foot[key], key)


def native_log(path):
    text = path.read_text()
    require('BUILD SUCCESSFUL' in text and 'BUILD FAILED' not in text and 'GAME TESTS COMPLETE' in text,
            f'{path}: native run did not complete successfully')


def pending_fixture(report):
    require(report['interaction_fixture_version'] == INTERACTION_VERSION
            and report['fixture_spec_sha256'] == INTERACTION_SPEC_SHA
            and number(report['control_hz'], 'interaction control_hz') == 20,
            'Interactions require the matched native pending fixture v1')
    rows = [r for r in report['trials'] if r['case'] == 'retarget-pond']
    require(len(rows) == 1, 'Missing/duplicate pending fixture case')
    row = rows[0]
    require(row['interaction_fixture_version'] == INTERACTION_VERSION
            and row['fixture_spec_sha256'] == INTERACTION_SPEC_SHA, 'Pending row fixture mismatch')
    for key in ('fixture_preconditions_passed', 'support_removed', 'support_restored',
                'support_restoration_verified', 'one_native_tick_elapsed'):
        require(row[key] is True, f'Incomplete native fixture coverage: {key}')
    require(isinstance(row['fixture_failure_reason'], str) and row['restored_during_cleanup'] is False,
            'Fixture must restore during its ordinary native tick, not cleanup')
    for key in ('event_test_tick', 'restore_test_tick', 'event_server_tick', 'restore_server_tick',
                'modified_block_count'):
        require(type(row[key]) is int and row[key] >= 0, f'Invalid fixture counter: {key}')
    require(row['event_test_tick'] == 100 and row['restore_test_tick'] == 101
            and row['restore_server_tick'] == row['event_server_tick'] + 1
            and row['modified_block_count'] > 0, 'Fixture did not span exactly one native tick')
    require(re.fullmatch(r'[0-9a-f]{64}', row['original_block_sha256'])
            and row['original_block_sha256'] == row['restored_block_sha256'], 'Fixture restoration mismatch')
    if row['success']:
        require(row['fixture_failure_reason'] == '', 'Successful pending fixture reports an invariant failure')
        for key in ('pending_retarget', 'invalid_pending_request_preserved', 'retarget_goal_reached',
                    'valid_request_accepted', 'invalid_request_rejected', 'automatic_pending_resolution'):
            require(row[key] is True, f'Success lacks pending invariant: {key}')
        vector(row['valid_target'], 3, 'retarget destination')
        require(all(row[key] == row['valid_target'] for key in
                    ('pending_after_valid', 'pending_after_invalid', 'resolved_goal')), 'Actual retarget goal mismatch')
        for key in ('pending_created_nav_tick', 'pending_retry_tick', 'pending_deadline',
                    'automatic_resolution_nav_tick', 'arrival_window_start_tick', 'arrival_completion_tick'):
            require(type(row[key]) is int and row[key] >= 0, f'Invalid pending counter: {key}')
        require(row['pending_retry_tick'] == row['pending_created_nav_tick'] + 10
                and row['pending_deadline'] == row['pending_created_nav_tick'] + 200
                and row['pending_retry_tick'] <= row['automatic_resolution_nav_tick'] <= row['pending_deadline'],
                'Pending retry/deadline evidence mismatch')
        require(row['arrival_completion_tick'] == row['arrival_window_start_tick'] + 39
                and row['arrival_completion_tick'] == row['completion_tick']
                and 0 <= number(row['arrival_error'], 'pending arrival error') < 1,
                'Pending destination lacks the qualified arrival hold')


def qualification(name, report, reference):
    rows = report['trials']
    require(len(rows) == COUNTS[name], f'{name}: incomplete suite')
    if name in ('certification', 'challenge'):
        require([r['course'] for r in rows] == [r['course'] for r in reference['trials']],
                f'{name}: qualified courses/order changed')
    elif name in ('wilderness', 'natural-tour'):
        require([(r['stage'], r['pace'], r['goal']) for r in rows]
                == [(r['stage'], r['pace'], r['goal']) for r in reference['trials']],
                f'{name}: qualified field legs changed')
        require(report['world_seed'] == reference['world_seed'] and report['fixture'] == reference['fixture'],
                f'{name}: field world/fixture changed')
    else:
        require(sorted(r['case'] for r in rows) == sorted(r['case'] for r in reference['trials']),
                'Interaction cases changed or duplicated')
    for row in rows:
        outcome(row, name)
        require(row['success'], f'{name}: failed regression: {row["reason"]}')
        require(number(row['anchor_error'], name + '.anchor') <= .12, f'{name}: structural failure')
        if name == 'interaction':
            require(0 < number(row['seconds'], 'interaction seconds') <= 120, 'Interaction deadline')
        else:
            require(number(row['limit_violation'], 'limit violation', 0) <= .15
                    and 0 <= number(row['error'], 'arrival error') < 1
                    and .5 <= number(row['minimum_up'], 'minimum up') <= 1 + 1e-6,
                    f'{name}: invalid physical arrival')
            require(0 < row['arrival_seconds'] <= (100 if 'course' in row else 124), f'{name}: arrival deadline')
    return {'passed': sum(r['success'] for r in rows), 'count': len(rows)}


def unit_results(directory):
    paths = sorted(directory.glob('TEST-*.xml'))
    seen = set()
    require(paths, 'Missing common unit XML')
    for path in paths:
        root = ET.parse(path).getroot()
        cases = root.findall('testcase')
        require(int(root.attrib['tests']) == len(cases) and all(int(root.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped')),
                f'{path}: incomplete/failed/skipped tests')
        for case in cases:
            key = case.get('classname'), case.get('name')
            require(all(key) and key not in seen and not any(case.find(k) is not None for k in ('failure', 'error', 'skipped')),
                    f'{path}: duplicate/failed/skipped testcase')
            seen.add(key)
    require(len(seen) >= 152, 'At least 152 passing common tests required')
    return len(seen), paths


def validate(args):
    prefix, qual = args.prefix, args.qual_prefix
    freeze_path = suffixed(prefix, 'freeze.json')
    freeze = read(freeze_path)
    implementation = freeze['implementation_sha256']
    require(re.fullmatch(r'[0-9a-f]{64}', implementation or ''), 'Invalid frozen source hash')
    refs = {key: resolve_reference(freeze_path, freeze, key) for key in
            ('genome', 'settings', 'fixture_manifest', 'baseline_benchmark', 'baseline_interactions')}
    require(refs['genome'] == suffixed(prefix, 'model.json').resolve()
            and refs['settings'] == suffixed(prefix, 'settings.json').resolve(), 'Freeze selection differs from prefix')
    archive = suffixed(prefix, 'source.zip')
    source_hashes = archive_sources(archive, implementation)
    require(sha(V10) == V10_SHA, 'Qualified v10 rollback model was modified')
    baseline_model, selected = read(V10), read(refs['genome'])
    require(baseline_model['validated'] is True and baseline_model['policy'] == 'leaper_predator_clearance_v10', 'Invalid v10 baseline')
    chosen = settings(read(refs['settings']))
    if 'highstep_settings' in selected:
        match_settings(selected['highstep_settings'], chosen, 'Selected model')
    policy_source = (JAVA / 'MinecraftMachinesLeaperPredatorPolicy.java').read_text()
    require(re.search(r'\bVERSION\s*=\s*"' + POLICY + r'"', policy_source), 'Current runtime policy must be v11')
    decoded = parameters(selected, policy_source)
    rig_source = (JAVA / 'MinecraftMachinesLeaper.java').read_text()
    contract = re.search(r'\bPREDATOR_CONTRACT\s*=\s*"([^"]+)"', rig_source)[1]
    require(contract == baseline_model['contract'], 'Qualified rig contract changed')
    compact = re.sub(r'\s+', '', rig_source)
    for expected in ('activeMin=newdouble[]{-.9,-1.05,-1.2}', 'activeMin[1]=shoulderMinimum;',
                     'predatorReach(-1.05,shoulder,knee);', 'activeMax[1]=shoulder;',
                     'activeMax[2]=knee;', '100000*power,8000*Math.sqrt(power)*motorDampingScale,true,150000*power*motorForceScale',
                     'doublemotorForceScale=1,motorDampingScale=1;', 'preciseCollision(true);'):
        require(expected in compact, f'Physical source contract changed: {expected}')
    resources = {name: sha(RES / name) for name in baseline_model['resource_sha256']}
    require(resources == baseline_model['resource_sha256'], 'Qualified geometry/navigation resources changed')
    pads = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/physics_block_properties/leaper_pads.json'
    require(sha(pads) == PADS_SHA, 'Qualified grip/friction resource changed')
    manifest = read(refs['fixture_manifest'])
    require(manifest['rubric_sha256'] == RUBRIC_SHA and manifest['version'] >= 2, 'Wrong critic fixture/rubric version')
    require(manifest['goal_height'] == 'actual native voxel collision surface'
            and manifest['F6_final_apron'] == FINAL_APRON,
            'Qualified v2 goal/apron semantics changed')
    benchmark_path = suffixed(prefix, 'benchmark.json')
    interaction_path = suffixed(prefix, 'interactions.json', 'interaction.json')
    score_path = suffixed(prefix, 'score.json')
    baseline, benchmark = read(refs['baseline_benchmark']), read(benchmark_path)
    baseline_inter, interactions = read(refs['baseline_interactions']), read(interaction_path)
    collision = resources['collision_geometry.json']
    metadata(baseline, baseline_model['genome'], implementation, collision, BASE_SETTINGS, True)
    metadata(benchmark, selected['genome'], implementation, collision, chosen, True)
    metadata(interactions, selected['genome'], implementation, collision, chosen)
    require(baseline['mode'] == 'predator-highstep-baseline' and benchmark['mode'] == 'predator-highstep-candidate', 'Incorrect benchmark mode')
    for report in (baseline, benchmark):
        require(report['fixture_manifest_sha256'] == sha(refs['fixture_manifest'])
                and report['fixture_manifest'] == manifest and report['baseline_model_sha256'] == V10_SHA,
                'Benchmark fixture/baseline provenance mismatch')
        require(report['metric_version'] == 'critic-v2-stall-from-settled-20hz', 'Scoring metric version changed')
    require(baseline['rayon_threads'] == benchmark['rayon_threads'], 'Baseline/candidate native thread count differs')
    benchmark_rows(baseline, manifest, False)
    benchmark_rows(benchmark, manifest, True)
    metadata(baseline_inter, baseline_model['genome'], implementation, collision, BASE_SETTINGS)
    fixture_spec_path = OUT / 'pending-fixture-spec.md'
    require(sha(fixture_spec_path) == INTERACTION_SPEC_SHA, 'Pending fixture specification changed; review required')
    pending_fixture(baseline_inter)
    pending_fixture(interactions)
    scorer_path, rubric_path = OUT / 'score_benchmark.py', OUT / 'critic-rubric.md'
    require(sha(scorer_path) == SCORER_SHA and sha(rubric_path) == RUBRIC_SHA, 'Independent scorer/rubric changed; review required')
    spec = importlib.util.spec_from_file_location('leaper_independent_scorer', scorer_path)
    scorer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(scorer)
    before = scorer.score(baseline, baseline_inter, 'baseline')
    after = scorer.score(benchmark, interactions, 'candidate')
    comparison = scorer.compare(before, after)
    recorded_score = read(score_path)
    require(recorded_score['schema'] == 'leaper-independent-score-v2'
            and recorded_score['scorer_sha256'] == SCORER_SHA and recorded_score['rubric_sha256'] == RUBRIC_SHA,
            'Score provenance mismatch')
    inputs = {'baseline': refs['baseline_benchmark'], 'candidate': benchmark_path,
              'baseline_interactions': refs['baseline_interactions'], 'candidate_interactions': interaction_path}
    for key, path in inputs.items():
        require(recorded_score['inputs'][key]['sha256'] == sha(path), f'Score input {key} changed')
    require(recorded_score['baseline'] == before and recorded_score['candidate'] == after
            and recorded_score['comparison'] == comparison, 'Stored score differs from independent recomputation')
    require(number(after['score_raw'], 'actual critic score') >= 8.5 and set(comparison['gates']) == GATES
            and all(v is True for v in comparison['gates'].values()) and comparison['all_numeric_gates_pass'] is True,
            f'Critic gates failed: {[k for k, v in comparison["gates"].items() if v is not True]} (score {after["score_raw"]})')
    evidence = [freeze_path, archive, V10, *refs.values(), benchmark_path, interaction_path,
                score_path, scorer_path, rubric_path, fixture_spec_path, pads, *(RES / name for name in resources)]
    reports, summaries = {}, {}
    for name in COUNTS:
        path = suffixed(qual, name + '.json')
        report = read(path)
        metadata(report, selected['genome'], implementation, collision, chosen)
        reference_path = ROOT / 'artifacts/leaper-clearance' / ('final-' + name + '.json')
        require(sha(reference_path) == baseline_model['evidence_sha256'][label(reference_path)],
                f'Qualified v10 {name} reference changed')
        summaries[name] = qualification(name, report, read(reference_path))
        reports[name] = report
        evidence += [path, reference_path]
    detail = scorer.interaction_details(reports['interaction'], 'final interactions')
    pending_fixture(reports['interaction'])
    require(detail['passed'] == 6 and detail['stop_drift'] < 1.5, 'Final interaction gates failed')
    for path in [*inputs.values(), *(suffixed(qual, name + '.json') for name in COUNTS)]:
        log = path.with_suffix('.log')
        native_log(log)
        evidence.append(log)
    units, xml = unit_results(args.test_results)
    evidence += xml
    evidence += list((ROOT / 'minecraft_machines/common/src/test/java').rglob('*.java'))
    hashes = {label(p): sha(p) for p in evidence}
    approval_path = args.critic_approval or suffixed(prefix, 'critic-approval.json')
    approval = read(approval_path)
    require(approval['release_approved'] is True and approval['implementation_sha256'] == implementation,
            'Independent critic has not approved this frozen implementation')
    require(set(approval['manual_gates']) == set(comparison['mandatory_unautomated_gates'])
            and all(value is True for value in approval['manual_gates'].values()), 'Independent manual gates incomplete')
    for path in [freeze_path, archive, *refs.values(), *inputs.values(), score_path,
                 *(suffixed(qual, name + '.json') for name in COUNTS), scorer_path, rubric_path, fixture_spec_path]:
        require(approval['evidence_sha256'].get(label(path)) == sha(path), 'Critic approval is not bound to all final evidence')
    hashes[label(approval_path)] = sha(approval_path)
    physics = copy.deepcopy(baseline_model['physics'])
    physics.pop('shoulder_joint_limit_rad', None)
    physics.pop('knee_joint_limit_rad', None)
    physics.update(shoulder_joint_limits_rad=[chosen['shoulder_minimum'], chosen['shoulder_maximum']],
                   knee_joint_limits_rad=[-1.2, chosen['knee_maximum']],
                   yaw_joint_limits_rad=[-.9, .9], total_mass=87, body_masses=[27] + [1, 8, 6] * 4,
                   max_joint_motor_force=150000 * decoded['motor_power'], joint_slew_rad_s=decoded['motor_slew'],
                   joint_motor_stiffness=100000 * decoded['motor_power'],
                   joint_motor_damping=8000 * math.sqrt(decoded['motor_power']),
                   motor_force_scale=1, motor_damping_scale=1, native_config=PHYSICS_CONFIG)
    physics['benchmark_history'] = {
        'core_orientation': 'Actual core quaternion in world space, ordered x/y/z/w.',
        'per_leg_angle_order': ['yaw', 'shoulder', 'knee'],
        'angle_units': 'radians',
        'raw_requested_angles': 'Unclipped IK output for the control tick; may lie outside selected joint bounds.',
        'bounded_target_angles': 'Copy of actual motor targets after joint bounds and slew limiting.',
        'joint_angles': 'Measured native joint angles, distinct from both requested and bounded targets.'}
    physics['clearance_clock'] = {
        'pause_seconds_setting': chosen['clearance_pause'],
        'budget_ticks_per_leg_per_stroke': math.ceil(chosen['clearance_pause'] / .05),
        'waiting_phase_increment_multiplier': .05,
        'trigger': 'While activity > .01, a leg is in swing fraction [.20,.65), obstacle exceeds departure+.5, measured toe is below obstacle+.25, and its stroke budget remains.',
        'budget_lifecycle': 'Each eligible leg spends one tick of its own budget; any waiting leg reduces the shared phase increment to 5%. Each leg resets its budget when floor(phase+leg_phase_offset) changes. Zero budget preserves normal advancement.',
        'scope': 'Bounded phase creep coordinates all legs; clearance_pause is a per-leg per-stroke budget, not a global wall-clock timeout. No body teleportation or extra actuator force.'}
    physics['exposed_surface_sensing'] = {
        'enabled': chosen['exposed_surfaces'],
        'exposure_epsilon': 1e-5,
        'horizontal_tolerance': 1e-6,
        'policy': 'When enabled, accept a candidate AABB top only if the point 1e-5 above it is outside collision at the same local x/z. Check the containing cell and the cell below for shapes extending above their origin cell.',
        'fluids_and_leaves': 'Preserve fluid rejection and ignore leaves for surface sensing, including the exposure check.',
        'ceiling_policy': 'Preserve height <= ceiling+1e-6 and the existing 24-block downward scan. May return an exposed lower floor under a roof; never substitute an above-ceiling top.',
        'sweep_policy': 'sweepHeight retains finite-sample aggregation and its existing no-finite-sample sentinel. This is not a new above-ceiling obstacle detector.'}
    model = dict(validated=True, policy=POLICY, contract=contract, genome=selected['genome'],
                 parameters=decoded, highstep_settings=chosen, balance_gain=1, physics_substeps=4,
                 highstep_behavior={
                     'adaptive_footholds': {
                         'fractions_when_enabled': [1, .7, .4, .15],
                         'offset_multipliers_per_axis': [0, -1, 1, -2, 2],
                         'policy': 'Search fractions from measured toe toward the predicted nominal landing, then add each offset times foothold_radius. Score displacement from the nominal landing plus .08 times absolute height change; retain the existing finite-pad, height and joint-feasibility checks. Disabled uses the original fraction-one arithmetic.'},
                     'world_swing_height': {
                         'torso_height_error': 'center.y-ground-referenceHeight',
                         'compensation': 'max(0,-torso_height_error) * smooth(clamp(min(t/.15,(1-t)/.30),0,1)), where smooth(u)=u*u*(3-2*u)',
                         'scope': 'When enabled during swing, add only positive sag compensation to the body-relative vertical target. It is zero at both endpoints and when the torso is at or above its reference height. The separate world_swing path overrides this target when enabled.'},
                     'stance_compression': {
                         'gain': 'stance_compression * clamp((hypot(gradientX,gradientZ)-.1)/.4,0,1)',
                         'along_incline_extent_multiplier_bounds': [1-chosen['stance_compression'], 1],
                         'policy': 'Subtract gain times the projection of the nominal foot offset onto the filtered terrain incline, transformed into local yaw coordinates. Preserve its perpendicular component. The compressed nominal offset feeds both stance and swing geometry; zero gain retains the original extent.'},
                     'fresh_support': 'When enabled, bypass lower-outlier rejection only if support error relative to the fitted ground plane is below -4.5 and the old contact exceeds every pre-filter sensed foot floor by more than 4.5. Unavailable samples retain the existing fallback; upper-outlier rejection remains 4.5.',
                     'slow_damping': 'Add slow_damping * clamp((1-pace)/.55,0,1) to base vertical_damping before the clear-ground interpolation.',
                     'clear_ground_gain': {
                         'sampling': 'Relative to travel heading: ahead offsets [-8,0,8,16] and lateral offsets [-6,0,6], measured against centerFloor. Discrepancy is the maximum of foot roughness and absolute sampled height differences; unavailable terrain disables safeFlat.',
                         'safe_flat': 'clamp(1-discrepancy/.75,0,1); zero when clear_ground_gain is zero or centerFloor is unavailable',
                         'smoothing': 'Initialize clearGroundBlend to safeFlat, then move .25 of the remaining difference each 20Hz control step.',
                         'recovery': 'clear_ground_gain * clearGroundBlend * clamp((pace-.45)/.55,0,1)',
                         'recovery_bounds': [0, chosen['clear_ground_gain']],
                         'extra_lift': 'Multiply the post_clearance_lift extra-lift contribution by (1-recovery); the earlier extra-lift branch is unchanged when post_clearance_lift is false.',
                         'vertical_damping': 'Interpolate from base vertical_damping plus slow_damping adjustment to the qualified v10 value by recovery.',
                         'vertical_damping_target': baseline_model['parameters']['vertical_damping'],
                         'scope': 'Careful pace and lower remain unchanged. The sampled 16-block lookahead anticipates terrain variation but does not prove the entire corridor is flat. Native motor and traction caps remain unchanged.'},
                     'flat_cadence': {
                         'pace': 'clamp(speed,.25,1.3)',
                         'clear_stride': 'clamp(1-roughness/.5,0,1) * product_over_four_feet(1-clamp((obstacle-contact-.15)/.5,0,1))',
                         'gain': 'flat_cadence * clear_stride * clamp((pace-.45)/.55,0,1)',
                         'period_seconds': 'base_period / (pace * (1+gain))',
                         'gain_bounds': [0, chosen['flat_cadence']],
                         'cadence_multiplier_bounds': [1, 1 + chosen['flat_cadence']],
                         'scope': 'Continuous boost above Careful pace only; fades with roughness and sensed obstruction, reaching zero at roughness >= .5 or any obstacle >= contact+.65. Motor power, force caps and slew caps remain unchanged.'}},
                 contact_grip=True, early_clearance=True, coordinated_swing=True,
                 implementation_sha256=implementation, resource_sha256=resources,
                 source_sha256=source_hashes, evidence_sha256=hashes,
                 packaging_sha256=sha(Path(__file__)), physics=physics,
                 architecture='Layered stance planning, perceptive continuous IK, qualified finite native servos and traction',
                 learning={'method': 'Engineered high-step refinement and native development/held-out evaluation',
                           'new_cem_episodes': 0, 'inherited_v10_learning': baseline_model['learning'],
                           'selection': freeze.get('selected_from'),
                           'settings_scope': 'Named genome parameters are the base values; bounded pace-dependent damping and cadence adjustments, targeted vanished-contact sensing, measured-clearance clock budgets and exposed-surface queries are engineered refinements described in highstep_behavior and physics.',
                           'scope': 'No new end-to-end neural reinforcement-learning claim.'},
                 qualification={'suites': summaries, 'common_unit_tests': units,
                                'critic_score_raw': after['score_raw'], 'critic_components': after['components'],
                                'critic_numeric_gates': comparison['gates'], 'critic_manual_gates': approval['manual_gates'],
                                'baseline_critic_score_raw': before['score_raw'],
                                'baseline_interaction_implementation_sha256': baseline_inter['implementation_sha256'],
                                'interaction_fixture_version': INTERACTION_VERSION,
                                'interaction_fixture_spec_sha256': INTERACTION_SPEC_SHA,
                                'fixture_manifest_sha256': sha(refs['fixture_manifest']),
                                'benchmark_failures': [dict(family=r['family'], configuration=r['configuration'], reason=r['reason'])
                                                       for r in benchmark['trials'] if not r['success']],
                                'limitations': [after['sampling_caution'],
                                    'Backward-travel and reverse-speed events exclude navigator backouts; these are proxies, not measured collision impulses.',
                                    'Fixed recorded courses do not establish universal high-step access, wall climbing, swimming, or reliable narrow-cave navigation.',
                                    'Numeric and independent visual/provenance approval apply only to the bound frozen evidence.']},
                 rollback={'model': label(V10), 'sha256': V10_SHA, 'policy': baseline_model['policy']})
    finite_tree(model, 'output model')
    return model


def atomic_write(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prefix', type=Path, default=OUT / 'candidate-02')
    parser.add_argument('--qual-prefix', type=Path, default=OUT / 'final')
    parser.add_argument('--critic-approval', type=Path)
    parser.add_argument('--test-results', type=Path, default=ROOT / 'minecraft_machines/common/build/test-results/test')
    parser.add_argument('--promote', action='store_true', help='After all checks, write v11 artifact and packaged resource')
    args = parser.parse_args(argv)
    try:
        model = validate(args)
        # Refuse evidence/source changes during validation, including concurrent native work.
        require(source_hash() == model['implementation_sha256'], 'Source changed during validation')
        for name, expected in model['evidence_sha256'].items():
            path = Path(name) if Path(name).is_absolute() else ROOT / name
            require(sha(path) == expected, f'Evidence changed during validation: {name}')
        if args.promote:
            payload = json.dumps(model, indent=2, allow_nan=False) + '\n'
            atomic_write(OUT / 'model.json', payload)
            atomic_write(RES / 'predator.json', payload)
        print(json.dumps({'validated': True, 'promoted': args.promote,
                          'score_raw': model['qualification']['critic_score_raw'],
                          'implementation_sha256': model['implementation_sha256']}, indent=2))
    except (ValueError, KeyError, TypeError, IndexError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(json.dumps({'validated': False, 'promoted': False, 'error': str(error)}), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
