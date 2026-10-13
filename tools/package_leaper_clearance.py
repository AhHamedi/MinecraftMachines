#!/usr/bin/env python3
"""Package engineered swing clearance only from complete, matching final native evidence.

Importing this module never promotes a model. Run main explicitly after qualification.
"""
from pathlib import Path
import hashlib
import json
import math
import re
import statistics
import xml.etree.ElementTree as ET
import zipfile
from package_leaper_predator import ROOT, source_files, source_hash

OUT = ROOT / 'artifacts/leaper-clearance'
RES = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper'
JAVA = ROOT / 'minecraft_machines/neoforge/src/main/java/dev/ahmedhamedi/minecraft_machines/neoforge'
COMMON = ROOT / 'minecraft_machines/common'
COUNTS = {'certification': 72, 'challenge': 18, 'interaction': 6, 'wilderness': 6,
          'natural-tour': 13, 'baseline-certification': 72, 'baseline-challenge': 18}
FLAGS = {'early_clearance': True, 'coordinated_swing': True}
MOTION_FIELDS = ('backward_travel', 'worst_reverse_speed', 'kickbacks_over_4bps')
MUTATED = [0, 1, 2]
TRAINING_IMPLEMENTATION = '4be084249f34f6933ed38f182ec4eeacfb69d28f648797121ef8c33fd30bab4e'
PRE_RETARGET_IMPLEMENTATION = 'bf9ca43ba717b09f26087cd5f00368479677542739dbb123ee851bf521aa3cb0'
PRE_LIFECYCLE_IMPLEMENTATION = '1774267ac7825c4e3de6b486e5f0a98e0ab3c0039253778d335e1ca522b3f56d'
QUALIFIED_IMPLEMENTATION = '25f7b11c6a5c96462bbfe4d75ae189b036d758ae1f4e6f4e3a098756dffa50da'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def archive_sources(path, expected):
    """Verify the frozen manifest with exactly source_hash()'s path/content ordering."""
    names = [p.relative_to(ROOT).as_posix() for p in source_files()]
    digest, hashes = hashlib.sha256(), {}
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(names) and set(archive.namelist()) == set(names),
                f'{path.name}: source manifest differs from production')
        for name in names:
            data = archive.read(name)
            digest.update((name + '\n').encode())
            digest.update(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
    require(digest.hexdigest() == expected, f'{path.name}: frozen source hash mismatch')
    return hashes


def number(row, key):
    value = row[key]
    require(type(value) in (int, float) and math.isfinite(value), f'Nonfinite/invalid {key}')
    return value


def match(pattern, text):
    found = re.search(pattern, text, re.S)
    require(found is not None, f'Source contract not found: {pattern}')
    return found[1]


def validate_genome(genome, baseline):
    require(len(genome) == 20 and all(type(v) in (int, float) and math.isfinite(v) for v in genome),
            'Invalid 20-parameter genome')
    require(genome[3:] == baseline['genome'][3:], 'A parameter outside stride/period/duty changed')


def validate_parameters(model, policy_source):
    low, high = [[float(v) for v in match(rf'\b{name}\s*=\s*\{{([^}}]+)\}}', policy_source).split(',')]
                 for name in ('LOW', 'HIGH')]
    names = re.findall(r'"([^"]+)"', match(r'\bNAMES\s*=\s*\{([^}]+)\}', policy_source))
    require(len(low) == len(high) == len(names) == 20, 'Policy parameter dimensions changed')
    decoded = {n: a + (b-a) * (.5 + .5 * math.tanh(g))
               for n, a, b, g in zip(names, low, high, model['genome'])}
    require(decoded.keys() == model['parameters'].keys(), 'Parameter names changed')
    require(all(math.isclose(v, number(model['parameters'], n), rel_tol=1e-12, abs_tol=1e-12)
                for n, v in decoded.items()), 'Named parameters do not match decoded genome')
    return decoded


def validate_physics(baseline, candidate, policy_source, rig_source, control_source):
    """Timing may change; all other decoded parameters and actuator limits remain qualified."""
    validate_parameters(baseline, policy_source)
    decoded = validate_parameters(candidate, policy_source)
    require(all(candidate['parameters'][n] == value for n, value in baseline['parameters'].items()
                if n not in ('stride', 'period', 'duty')), 'An unrelated physical parameter changed')
    physics = baseline['physics']
    limits = [physics[f'{joint}_joint_limit_rad'] for joint in ('yaw', 'shoulder', 'knee')]
    for name, expected in (('activeMin', [-v for v in limits]), ('activeMax', limits)):
        actual = [float(v) for v in match(rf'{name}\s*=\s*new double\[\]\s*\{{([^}}]+)\}}', rig_source).split(',')]
        require(actual == expected, f'{name} differs from qualified joint limits')
    rig = re.sub(r'\s+', '', rig_source)
    control = re.sub(r'\s+', '', control_source)
    if re.search(r'\bshoulderMaximum\s*=', control_source):
        require('rig.clearanceShoulderLimit(shoulderMaximum)' in control
                and 'activeMax[1]=maximum;' in rig, 'Missing explicit shoulder-limit application')
    require('setMotor(ANGULAR_X,targets[j],100000*power,8000*Math.sqrt(power),true,150000*power)' in rig,
            'Joint motor stiffness, damping or force-cap formula changed')
    require('rig.command(action,.05,Math.max(1,p[17]*governor),Math.max(1,p[16]*governor),cartesianSlew)' in control,
            'Controller actuator power/slew contract changed')
    require(math.isclose(physics['max_joint_motor_force'], 150000 * decoded['motor_power'], rel_tol=1e-12)
            and math.isclose(physics['joint_slew_rad_s'], decoded['motor_slew'], rel_tol=1e-12),
            'Qualified motor caps mismatch')
    require(physics['control_hz'] == 20 and physics['native_substeps_hz'] == 80
            and physics['body_count'] == 13 and physics['limited_native_joints'] == 12,
            'Qualified physical contract mismatch')
    pads = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/physics_block_properties/leaper_pads.json'
    require(read(pads)['overrides']['part=43']['sable:friction'] == physics['foot_friction'],
            'Qualified pad friction changed')
    return pads


def validate_trial(name, trial):
    require(type(trial['success']) is bool, f'{name}: missing boolean outcome')
    for field in ('error', 'anchor_error', 'limit_violation', 'arrival_seconds', 'progress_speed',
                  'seconds', 'stop_drift', *MOTION_FIELDS):
        if field in trial:
            require(number(trial, field) >= 0, f'{name}: negative {field}')
    if 'course' in trial:
        for field in ('arrival_seconds', 'progress_speed', *MOTION_FIELDS):
            number(trial, field)  # Paired evidence must contain the new metrics, even on failure.
        require(type(trial['kickbacks_over_4bps']) is int, f'{name}: invalid kickback count')
    if not trial['success']:
        require(bool(trial['reason']), f'{name}: failure without a reason')
        return
    require(trial['reason'] == '', f'{name}: success masks failure: {trial["reason"]}')
    interaction = name == 'interaction'
    require(0 <= number(trial, 'anchor_error') <= (.16 if interaction else .12),
            f'{name}: structural error')
    require((.4 if interaction else .5) <= number(trial, 'minimum_up') <= 1 + 1e-6,
            f'{name}: unstable pose')
    if interaction:
        require(0 < number(trial, 'seconds') <= 120, 'Interaction arrival deadline')
    else:
        require(0 <= number(trial, 'limit_violation') <= .15 and 0 <= number(trial, 'error') < 1,
                f'{name}: limit or arrival error')
        require(0 < number(trial, 'arrival_seconds') <= (100 if 'course' in trial else 124),
                f'{name}: arrival deadline')
    if 'course' in trial:
        seconds = trial['arrival_seconds']
        require(math.isclose(seconds, (number(trial, 'ticks') - 119) * .05, abs_tol=1e-8),
                f'{name}: incomplete arrival hold')
        require(math.isclose(trial['progress_speed'], trial['course']['distance'] / seconds, rel_tol=1e-9),
                f'{name}: inconsistent journey speed')


def validate_training(baseline, candidate, training, policy_source, policy, implementation, collision):
    validate_parameters(candidate, policy_source)
    for name, data in (('timing-candidate', candidate), ('timing-training', training)):
        require(data['policy'] == policy and data['implementation_sha256'] == implementation,
                f'{name}: policy/source mismatch')
        require(data['collision_sha256'] == collision and data['contact_grip'] is True
                and data['physics_substeps'] == 4 and data['balance'] == 1, f'{name}: physics mismatch')
        require(data['baseline_genome'] == baseline['genome'] and data['mutated_indices'] == MUTATED,
                f'{name}: incorrect seed/mutation contract')
        require(all(data.get(flag) is enabled for flag, enabled in FLAGS.items())
                and number(data, 'shoulder_maximum') == 1.05, f'{name}: controller configuration mismatch')
        validate_genome(data['genome'], baseline)
    require(training['status'] == 'complete' and training['completed'] == 96
            and training['population'] == 8 and training['planned_generations'] == 3
            and training['planned_episodes'] == 96, 'Incomplete 8 x 3 x 4 timing search')
    require(candidate['completed_episodes'] == 96 and candidate['completed_tasks'] == 4
            and candidate['seed'] == training['seed'], 'Candidate search metadata mismatch')
    selected = candidate['selected_candidate']
    require(type(selected) is int and 0 <= selected < 8 and candidate['selected_generation'] == 2
            and training['selected_generation'] == 2 and training['selected_candidate'] == selected,
            'Selection must identify a generation-2 candidate')
    rows = training['episodes']
    require(len(rows) == 96, 'Training must retain all 96 completed trials')
    indexed = {}
    for row in rows:
        key = tuple(row[k] for k in ('generation', 'candidate', 'task'))
        require(all(type(v) is int for v in key) and key not in indexed, 'Duplicate/invalid training trial')
        indexed[key] = row
        validate_genome(row['genome'], baseline)
        validate_parameters(row, policy_source)
        validate_trial('timing-training', row)
        require(all(row.get(flag) is enabled for flag, enabled in FLAGS.items())
                and number(row, 'shoulder_maximum') == 1.05, 'Training trial controller mismatch')
        expected = (1000 if row['success'] else -1000) - .2 * number(row, 'ticks') - 3 * row['backward_travel']
        require(math.isclose(number(row, 'score'), expected, abs_tol=1e-8), 'Training trial score mismatch')
    require(set(indexed) == {(g, c, t) for g in range(3) for c in range(8) for t in range(4)},
            'Missing generation/candidate/task combinations')
    stats = training['generations']
    require([s['generation'] for s in stats] == [0, 1, 2] and candidate['generations'] == stats,
            'Incomplete or inconsistent generation statistics')
    incumbent = baseline['genome']
    for generation, stat in enumerate(stats):
        scores, genomes = [], []
        for c in range(8):
            tasks = [indexed[generation, c, t] for t in range(4)]
            genome = tasks[0]['genome']
            require(all(r['genome'] == genome for r in tasks), 'Genome changed during four-task evaluation')
            require(all(r['course'] == indexed[0, 0, t]['course'] for t, r in enumerate(tasks)),
                    'Training courses differ between candidates/generations')
            total, worst = sum(r['score'] for r in tasks), min(r['score'] for r in tasks)
            score = total + worst
            require(math.isclose(stat['task_score_totals'][c], total, abs_tol=1e-8)
                    and math.isclose(stat['worst_task_scores'][c], worst, abs_tol=1e-8)
                    and math.isclose(stat['scores'][c], score, abs_tol=1e-8)
                    and stat['tasks_passed'][c] == sum(r['success'] for r in tasks),
                    'Generation scores/pass counts disagree with completed trials')
            scores.append(score)
            genomes.append(genome)
        rank = sorted(range(8), key=lambda c: scores[c], reverse=True)
        require(genomes[0] == incumbent and stat['sampled_genomes'] == genomes,
                'Incumbent/sample provenance mismatch')
        require(stat['elite_candidates'] == rank[:2] and stat['selected_candidate'] == rank[0]
                and stat['selected_genome'] == genomes[rank[0]], 'Incorrect completed-generation selection')
        incumbent = genomes[rank[0]]
    selected_rows = [indexed[2, selected, t] for t in range(4)]
    require(candidate['genome'] == training['genome'] == incumbent
            and all(r['genome'] == candidate['genome'] for r in selected_rows),
            'Exported genome does not match the selected four complete generation-2 rows')
    selected_score = sum(r['score'] for r in selected_rows) + min(r['score'] for r in selected_rows)
    require(math.isclose(number(candidate, 'score'), selected_score, abs_tol=1e-8)
            and math.isclose(number(training, 'best_score'), selected_score, abs_tol=1e-8)
            and candidate['tasks_passed'] == sum(r['success'] for r in selected_rows),
            'Selected score/pass count mismatch')
    # Selection is not qualification: preserve its actual pass count; the final 97/97 and
    # stress >=17/18 gates below decide whether the complete tuned system may be packaged.
    return selected_rows


def validate_release(baseline, release, training, policy_source, policy, implementation, collision):
    require(release['policy'] == policy and release['implementation_sha256'] == implementation,
            'Release checkpoint policy/source mismatch')
    require(release['collision_sha256'] == collision and release['contact_grip'] is True
            and release['physics_substeps'] == 4 and release['balance'] == 1,
            'Release checkpoint physics mismatch')
    require(all(release.get(flag) is enabled for flag, enabled in FLAGS.items())
            and number(release, 'shoulder_maximum') == 1.05, 'Release checkpoint controller mismatch')
    validate_genome(release['genome'], baseline)
    validate_parameters(release, policy_source)
    selection = tuple(release[k] for k in ('selected_generation', 'selected_candidate'))
    require(all(type(v) is int for v in selection)
            and 0 <= selection[0] < training['planned_generations']
            and 0 <= selection[1] < training['population'],
            'Release checkpoint must identify a candidate within the completed search')
    rows = sorted((r for r in training['episodes'] if (r['generation'], r['candidate']) == selection),
                  key=lambda r: r['task'])
    require([r['task'] for r in rows] == [0, 1, 2, 3]
            and all(r['genome'] == release['genome'] and r['parameters'] == release['parameters'] for r in rows),
            'Release checkpoint must match four complete training records, including named parameters')
    return rows  # validate_training has already checked every row's scores, flags and physical outcomes.


def checkpoint_summary(artifact, candidate, rows):
    return {'artifact': (OUT / artifact).relative_to(ROOT).as_posix(), 'sha256': sha(OUT / artifact),
            'implementation_sha256': candidate['implementation_sha256'],
            'generation_zero_based': candidate['selected_generation'],
            'candidate_zero_based': candidate['selected_candidate'], 'genome': candidate['genome'],
            'training_tasks_completed': len(rows), 'training_tasks_passed': sum(r['success'] for r in rows),
            'training_score': sum(r['score'] for r in rows) + min(r['score'] for r in rows),
            'training_failures': [{'task': r['task'], 'course': r['course'], 'reason': r['reason']}
                                  for r in rows if not r['success']]}


def validate_report(name, report, genome, policy, implementation, collision, count, enabled):
    require(report['policy'] == policy and report['genome'] == genome, f'{name}: policy/genome mismatch')
    require(report['implementation_sha256'] == implementation, f'{name}: source mismatch')
    require(report['collision_sha256'] == collision, f'{name}: collision mismatch')
    require(report['contact_grip'] is True and report['physics_substeps'] == 4 and report['balance'] == 1,
            f'{name}: actuator settings mismatch')
    require(len(report['trials']) == count, f'{name}: incomplete suite')
    for trial in report['trials']:
        if 'certification' in name or 'challenge' in name:
            require(all(trial.get(flag) is (required and enabled) for flag, required in FLAGS.items()),
                    f'{name}: incorrect ablation flags')
            require(number(trial, 'shoulder_maximum') == 1.05, f'{name}: shoulder maximum changed')
        validate_trial(name, trial)


def checkpoint_history(baseline, training, policy_source, policy, collision):
    """Optional development history: verify recorded evidence, never require development passes."""
    manifest = OUT / 'checkpoint-validation.json'
    if not manifest.exists():
        return None, []
    generator = OUT / 'summarize_checkpoints.py'
    require(generator.is_file(), 'Checkpoint manifest lacks its summarizer source')
    history, evidence, seen = read(manifest), [manifest, generator], set()
    for label, checkpoint in history['checkpoints'].items():
        candidate_name = checkpoint['candidate_artifact']
        require(Path(candidate_name).name == candidate_name, 'Checkpoint candidate must be a local artifact')
        candidate_path = OUT / candidate_name
        candidate = read(candidate_path)
        validate_release(baseline, candidate, training, policy_source, policy, TRAINING_IMPLEMENTATION, collision)
        require(label == f"g{candidate['selected_generation']}c{candidate['selected_candidate']}"
                and checkpoint['genome'] == candidate['genome']
                and checkpoint['parameters'] == candidate['parameters'], f'{label}: candidate provenance mismatch')
        evidence.append(candidate_path)
        for entry in checkpoint['evaluations']:
            name = entry['artifact']
            require(Path(name).name == name and name not in seen, 'Duplicate/nonlocal checkpoint report')
            seen.add(name)
            require(type(entry['complete']) is bool, f'{name}: invalid completion status')
            if not entry['complete']:
                continue  # Incomplete historical checks are neither required nor credited as passes.
            path = OUT / name
            report = read(path)
            implementation = entry['implementation_sha256']
            require(re.fullmatch(r'[0-9a-f]{64}', implementation) and sha(path) == entry['sha256'],
                    f'{name}: historical source/report hash mismatch')
            suite = next((n for n in COUNTS if name.endswith(f'-{n}.json')), None)
            require(suite is not None, f'{name}: unknown historical suite')
            validate_report(suite, report, candidate['genome'], policy, implementation, collision, COUNTS[suite], True)
            failures = [{'index': i, 'case': r.get('course', r.get('case', r.get('stage'))),
                         'reason': r['reason'], 'error': r.get('error'), 'anchor_error': r['anchor_error']}
                        for i, r in enumerate(report['trials']) if not r['success']]
            require(entry['cases'] == len(report['trials'])
                    and entry['passed'] == sum(r['success'] for r in report['trials'])
                    and entry['failures'] == failures, f'{name}: historical outcomes differ from the raw report')
            evidence.append(path)
            if path.with_suffix('.log').exists():
                evidence.append(path.with_suffix('.log'))
    return history, evidence


def paired_summary(pairs):
    completed = [(a, b) for a, b in pairs if a['success'] and b['success']]
    result = {'pairs': len(pairs), 'baseline_passed': sum(a['success'] for a, b in pairs),
              'final_passed': sum(b['success'] for a, b in pairs), 'both_succeeded': len(completed),
              'gained_successes': sum(not a['success'] and b['success'] for a, b in pairs),
              'lost_successes': sum(a['success'] and not b['success'] for a, b in pairs)}
    for field in ('arrival_seconds', 'progress_speed', *MOTION_FIELDS):
        rows = completed if field in ('arrival_seconds', 'progress_speed') else pairs
        before, after = [a[field] for a, b in rows], [b[field] for a, b in rows]
        result[field] = {'pairs': len(rows),
                         'baseline_mean': statistics.mean(before) if rows else None,
                         'final_mean': statistics.mean(after) if rows else None,
                         'mean_paired_delta': statistics.mean(b-a for a, b in zip(before, after)) if rows else None,
                         'baseline_max': max(before) if rows else None,
                         'final_max': max(after) if rows else None}
    result['median_paired_speed_gain_percent'] = (
        100 * (statistics.median(b['progress_speed'] / a['progress_speed'] for a, b in completed) - 1)
        if completed else None)
    result['total_journey_time_reduction_percent'] = (
        100 * (1 - sum(b['arrival_seconds'] for a, b in completed) / sum(a['arrival_seconds'] for a, b in completed))
        if completed else None)
    return result


def comparison(before, after):
    require([t['course'] for t in before] == [t['course'] for t in after], 'Paired courses/order differ')
    courses = [json.dumps(t['course'], sort_keys=True) for t in after]
    require(len(set(courses)) == len(courses), 'Duplicate paired courses')
    pairs = list(zip(before, after))
    result = {'overall': paired_summary(pairs)}
    for field in ('pace', 'kind'):
        result[f'by_{field}'] = {
            str(value): paired_summary([(a, b) for a, b in pairs if b['course'][field] == value])
            for value in sorted({b['course'][field] for a, b in pairs})}
    return result


def main():
    baseline = read(OUT / 'baseline-model.json')
    require(baseline['validated'] is True and baseline['policy'] == 'leaper_predator_precise_v9',
            'Expected the preserved, qualified v9 baseline model')
    validate_genome(baseline['genome'], baseline)
    candidate = read(OUT / 'timing-candidate.json')
    training = read(OUT / 'timing-training.json')
    release = read(OUT / 'release-candidate.json')
    genome = release['genome']
    validate_genome(genome, baseline)
    policy_source = (JAVA / 'MinecraftMachinesLeaperPredatorPolicy.java').read_text()
    rig_source = (JAVA / 'MinecraftMachinesLeaper.java').read_text()
    control_source = (JAVA / 'MinecraftMachinesLeaperPredatorControl.java').read_text()
    policy = match(r'\bVERSION\s*=\s*"([^"]+)"', policy_source)
    contract = match(r'\bPREDATOR_CONTRACT\s*=\s*"([^"]+)"', rig_source)
    selected_shoulder = re.search(r'\bshoulderMaximum\s*=\s*([\d.]+)', control_source)
    shoulder_maximum = (float(selected_shoulder[1]) if selected_shoulder else
                        float(match(r'activeMax\s*=\s*new double\[\]\s*\{([^}]+)\}', rig_source).split(',')[1]))
    require(shoulder_maximum == baseline['physics']['shoulder_joint_limit_rad'] == 1.05,
            'Timing qualification requires the original 1.05 shoulder limit')
    require(contract == baseline['contract'], 'Qualified morphology contract changed')
    require(re.search(r'\bearlyClearance\s*=\s*true\s*,\s*coordinatedSwing\s*=\s*true', control_source),
            'Production must enable both qualified swing features')
    pads = validate_physics(baseline, release, policy_source, rig_source, control_source)
    bindings = {name: sha(RES / name) for name in baseline['resource_sha256']}
    require(bindings == baseline['resource_sha256'], 'Qualified collision/geometry/navigation resources changed')
    implementation = source_hash()
    require(implementation == QUALIFIED_IMPLEMENTATION, 'Production source changed after the final freeze')
    training_sources = archive_sources(OUT / 'timing-training-source.zip', TRAINING_IMPLEMENTATION)
    pre_retarget_sources = archive_sources(OUT / 'pre-retarget-source.zip', PRE_RETARGET_IMPLEMENTATION)
    pre_lifecycle_sources = archive_sources(OUT / 'pre-lifecycle-source.zip', PRE_LIFECYCLE_IMPLEMENTATION)
    qualified_sources = archive_sources(OUT / 'qualified-source.zip', QUALIFIED_IMPLEMENTATION)
    selected_rows = validate_training(baseline, candidate, training, policy_source, policy,
                                      TRAINING_IMPLEMENTATION, bindings['collision_geometry.json'])
    release_rows = validate_release(baseline, release, training, policy_source, policy,
                                    TRAINING_IMPLEMENTATION, bindings['collision_geometry.json'])
    reports = {name: read(OUT / f'final-{name}.json') for name in COUNTS}
    evidence = [OUT / name for name in ('baseline-model.json', 'final-surface-sensing.log',
                                        'timing-candidate.json', 'timing-training.json', 'release-candidate.json',
                                        'timing-training-source.zip', 'pre-retarget-source.zip',
                                        'pre-lifecycle-source.zip', 'qualified-source.zip')]
    if (OUT / 'timing-training.log').exists():
        evidence.append(OUT / 'timing-training.log')
    for name, report in reports.items():
        expected_genome = baseline['genome'] if name.startswith('baseline-') else genome
        expected_source = TRAINING_IMPLEMENTATION if name.startswith('baseline-') else implementation
        validate_report(name, report, expected_genome, policy, expected_source, bindings['collision_geometry.json'],
                        COUNTS[name], not name.startswith('baseline-'))
        evidence += [OUT / f'final-{name}.json', OUT / f'final-{name}.log']
        if name in ('certification', 'interaction', 'wilderness', 'natural-tour'):
            require(all(t['success'] for t in report['trials']), f'{name}: 97-case regression')
    winner_pre_refinement = {}
    for name in ('certification', 'challenge'):
        path = OUT / f'training-winner-{name}.json'
        report = read(path)
        validate_report(f'training-winner-{name}', report, candidate['genome'], policy, TRAINING_IMPLEMENTATION,
                        bindings['collision_geometry.json'], COUNTS[name], True)
        require([r['course'] for r in report['trials']] == [r['course'] for r in reports[name]['trials']],
                f'{name}: training winner and release checkpoint courses differ')
        log = path.with_suffix('.log').read_text()
        require('GAME TESTS COMPLETE' in log and ('BUILD SUCCESSFUL' in log or 'BUILD FAILED' in log),
                f'{name}: incomplete archived training-winner run')
        if 'BUILD FAILED' in log:
            passed = sum(r['success'] for r in report['trials'])
            require(f'Physical navigation: {passed}/{COUNTS[name]}' in log,
                    f'{name}: archived build failure does not corroborate the reported navigation result')
        winner_pre_refinement[name] = {
            'artifact': path.relative_to(ROOT).as_posix(), 'sha256': sha(path),
            'log_artifact': path.with_suffix('.log').relative_to(ROOT).as_posix(),
            'log_sha256': sha(path.with_suffix('.log')), 'cases': len(report['trials']),
            'build_successful': 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log,
            'passed': sum(r['success'] for r in report['trials']),
            'failures': [{'case_index_zero_based': i, 'course': r['course'], 'reason': r['reason'],
                          'error': r['error'], 'ticks': r['ticks']}
                         for i, r in enumerate(report['trials']) if not r['success']]}
        evidence += [path, path.with_suffix('.log')]
    require(winner_pre_refinement['certification']['passed'] == 71,
            'Expected the archived training winner\'s 71/72 certification result')
    for path in evidence:
        # A completed failed run documents the winner's pre-refinement behavior; it is
        # never substituted for the successful final qualification evidence.
        if path.suffix == '.log' and not path.name.startswith('training-winner-'):
            log = path.read_text()
            require('BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log, f'{path.name}: unsuccessful build')
    challenge = reports['challenge']['trials']
    require(sum(t['success'] for t in challenge) >= 17, 'Challenge requires at least 17/18 passes')
    interaction = {t['case']: t for t in reports['interaction']['trials']}
    require(set(interaction) == {'moving-prey-hills', 'moving-prey-forest', 'retarget-pond',
            'continuous-pace-change', 'stop-resume-rough', 'dynamic-obstacle'}, 'Interaction cases changed')
    stop = interaction['stop-resume-rough']['stop_drift']
    require(stop < 1.5, 'Stop/resume drift regression')
    require(interaction['dynamic-obstacle']['rejected_target_preserved_route'] is True
            and interaction['dynamic-obstacle']['replans'] > 0, 'Dynamic obstacle invariant failed')
    require(all(interaction['retarget-pond'].get(flag) is True for flag in
                ('pending_retarget', 'invalid_pending_request_preserved', 'retarget_goal_reached')),
            'Pond retarget must exercise queuing, preserve the request on invalid input, and reach the new goal')
    for name in ('wilderness', 'natural-tour'):
        require([t['stage'] for t in reports[name]['trials']] == list(range(COUNTS[name])), f'{name}: stages differ')
    paired = {name: comparison(reports[f'baseline-{name}']['trials'], reports[name]['trials'])
              for name in ('certification', 'challenge')}

    xmls = sorted((COMMON / 'build/test-results/test').glob('TEST-*.xml'))
    suites = [ET.parse(path).getroot() for path in xmls]
    units = sum(int(s.attrib['tests']) for s in suites)
    skipped = sum(int(s.attrib.get('skipped', 0)) for s in suites)
    require(units - skipped >= 144 and all(int(s.attrib['failures']) + int(s.attrib['errors']) == 0 for s in suites),
            'Require at least 144 executed common tests and zero XML failures/errors')
    for suffix, count in (('LeaperGaitTest', 10), ('LeaperSwingClearanceTest', 6)):
        require(any(s.attrib['name'].endswith('.' + suffix) and int(s.attrib['tests']) >= count
                    and int(s.attrib.get('skipped', 0)) == 0 for s in suites), f'Missing {suffix} regressions')
    evidence += xmls
    development, development_evidence = checkpoint_history(baseline, training, policy_source, policy,
                                                          bindings['collision_geometry.json'])
    evidence += development_evidence
    pre_lifecycle_validation = []
    for path in sorted(OUT.glob('pre-lifecycle-*.json')):
        name = path.stem.removeprefix('pre-lifecycle-')
        require(name in COUNTS and not name.startswith('baseline-'), f'{path.name}: unknown historical suite')
        report = read(path)
        require(any(r['genome'] == report['genome'] for r in training['episodes']),
                f'{path.name}: historical genome absent from completed CEM')
        validate_report(name, report, report['genome'], policy, PRE_LIFECYCLE_IMPLEMENTATION,
                        bindings['collision_geometry.json'], COUNTS[name], True)
        pre_lifecycle_validation.append({'artifact': path.relative_to(ROOT).as_posix(), 'sha256': sha(path),
            'implementation_sha256': PRE_LIFECYCLE_IMPLEMENTATION, 'genome': report['genome'],
            'cases': len(report['trials']), 'passed': sum(r['success'] for r in report['trials'])})
        evidence.append(path)
        if path.with_suffix('.log').exists():
            evidence.append(path.with_suffix('.log'))
    sources = source_files() + sorted((COMMON / 'src/test/java').rglob('*.java'))
    routes = reports['certification']['trials']
    release_label = f"generation-{release['selected_generation']} candidate-{release['selected_candidate']}"
    releases_training_winner = genome == candidate['genome']
    release_subject = ('the same training-score winner' if releases_training_winner
                       else f'the retained {release_label} checkpoint')
    model = {**baseline, 'policy': policy, 'contract': contract, **FLAGS,
        'genome': genome, 'parameters': release['parameters'], 'physics': baseline['physics'],
        'shoulder_maximum': shoulder_maximum,
        'implementation_sha256': implementation, 'resource_sha256': bindings,
        'source_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in sources},
        'source_provenance': {
            'training_and_fresh_baseline': {'implementation_sha256': TRAINING_IMPLEMENTATION,
                'archive': (OUT / 'timing-training-source.zip').relative_to(ROOT).as_posix(),
                'source_sha256': training_sources},
            'descent_refinement_before_retarget': {'implementation_sha256': PRE_RETARGET_IMPLEMENTATION,
                'archive': (OUT / 'pre-retarget-source.zip').relative_to(ROOT).as_posix(),
                'source_sha256': pre_retarget_sources},
            'retarget_before_lifecycle_fixes': {'implementation_sha256': PRE_LIFECYCLE_IMPLEMENTATION,
                'archive': (OUT / 'pre-lifecycle-source.zip').relative_to(ROOT).as_posix(),
                'source_sha256': pre_lifecycle_sources},
            'final_qualification': {'implementation_sha256': implementation,
                'archive': (OUT / 'qualified-source.zip').relative_to(ROOT).as_posix(),
                'source_sha256': qualified_sources}},
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in evidence},
        'development_history': {
            'scope': 'Optional checkpoint-validation.json history, checked against its raw candidate and report artifacts. Failures and incomplete evaluations are preserved; these are validation trials, not additional CEM episodes or independent release qualification. Final report aliases must not be counted again.',
            'manifest': development,
            'pre_lifecycle_validation': pre_lifecycle_validation},
        'packaging_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in
                             (Path(__file__).resolve(), ROOT / 'tools/package_leaper_predator.py', pads)},
        'architecture': 'Engineered early swing clearance and synchronized Cartesian servo targets with CEM-tuned stride, period and duty, followed by descent-clearance and pending-retarget refinements; original motor caps and joint limits retained',
        'learning': {'method': 'Native Sable CEM over three timing parameters of the 20-parameter gait, combined with engineered swing clearance and synchronized Cartesian servo targets',
            'completed_new_episodes': 96, 'completed_new_cem_episodes': 96,
            'population': 8, 'generations': 3, 'tasks_per_candidate': 4,
            'mutated_indices': MUTATED, 'mutated_parameters': ['stride', 'period', 'duty'],
            'fixed_parameters': 17, 'training_seed': training['seed'],
            'training_selection': {**checkpoint_summary('timing-candidate.json', candidate, selected_rows),
                'released': releases_training_winner,
                'broader_validation': {'stage': 'before_descent_refinement',
                    'implementation_sha256': TRAINING_IMPLEMENTATION, 'reports': winner_pre_refinement}},
            'release_checkpoint': {**checkpoint_summary('release-candidate.json', release, release_rows),
                'selection_method': ('Training winner qualified after post-training engineering refinements'
                                     if releases_training_winner else 'Retained checkpoint selected after broader validation of post-training engineering refinements'),
                'selection_reason': f"Before the engineering refinements, the training-score winner recorded {winner_pre_refinement['certification']['passed']}/72 certification passes on source {TRAINING_IMPLEMENTATION}. After the descent-clearance and pending-retarget refinements, {release_subject} passed the full 97-case qualification and at least 17/18 stress cases on final source {implementation}."},
            'training_implementation_sha256': TRAINING_IMPLEMENTATION,
            'post_training_refinement': {
                'description': 'When early clearance is enabled, extra baseline lift measures obstacle height above the higher of departure and landing, instead of their mean; a pure descent no longer adds lift as if it were an obstacle above the path.',
                'pending_retarget': 'A valid destination request can wait for a supported starting stance while the previous route continues. Retrying installs the exact requested goal; stop cancels the request, invalid input preserves it, and pending requests cannot report arrival at the old goal. Native qualification requires the pending-request and actual-new-goal checks.',
                'pending_lifecycle': 'The pending deadline triggers a final planning attempt independently of the retry timer, and old-goal holding is disabled while a request is pending. These lifecycle corrections followed the archived pre-lifecycle validation and require fresh final-source qualification.',
                'pending_retry_interval_ticks': 10, 'pending_request_deadline_ticks': 200,
                'included_in_cem': False, 'new_cem_episodes': 0, 'implementation_sha256': implementation},
            'inherited_policy': baseline['policy'], 'inherited_cem_history': baseline['learning'],
            'qualification_note': 'Only stride, period and duty were fine-tuned in 96 native development episodes; the other 17 genome entries remain exact. Descent clearance and pending retargeting were engineered after CEM and qualified on the final source. Training-score selection and the checkpoint released after broader validation are recorded separately. Qualification courses informed checkpoint selection and refinement, so they are not an untouched holdout. This is not end-to-end neural RL.'},
        'qualification': {'current_cases': 97, 'current_passed': 97, 'routes': 72, 'interaction_cases': 6,
            'recorded_wilderness_journeys': 6, 'natural_dune_circuit_legs': 13, 'common_unit_tests': units,
            'skipped_unit_tests': skipped, 'stress_cases': 18, 'stress_passed': sum(t['success'] for t in challenge),
            'baseline_stress_passed': sum(t['success'] for t in reports['baseline-challenge']['trials']),
            'stress_failures': [{'course': t['course'], 'reason': t['reason'], 'error': t['error']} for t in challenge if not t['success']],
            'arrival_hold_seconds': 2, 'mean_error': statistics.mean(t['error'] for t in routes),
            'maximum_error': max(t['error'] for t in routes), 'maximum_anchor_error': max(t['anchor_error'] for t in routes),
            'maximum_joint_violation': max(t['limit_violation'] for t in routes), 'stop_drift': stop,
            'paired_comparison': paired,
            'baseline_implementation_sha256': TRAINING_IMPLEMENTATION,
            'final_implementation_sha256': implementation,
            'comparison_scope': f'Identical-course full-system comparison across the recorded training/baseline and final sources: original v9 genome with both swing features disabled versus the released {release_label} genome with both enabled and the post-training descent-clearance and pending-retarget refinements. The descent correction applies only when early clearance is enabled and would be inactive under the baseline flags; pending retargeting is separate navigator behavior and the tested source versions remain distinct. This does not isolate individual feature, timing or refinement effects. Time/speed use only pairs where both succeeded; reverse-motion summaries include every pair and observation durations can differ.',
            'reverse_motion_definition': 'Opposite-goal motion proxy, sampled after 80 settle ticks, outside navigator back-outs and more than 3 blocks from goal. Kickbacks count entries above 4 blocks/s reverse speed. These are not measured collision impulses.',
            'scope': '97 native qualification cases plus 18 development stress cases and 90 fresh original-v9 baseline route trials, following 96 three-parameter CEM development episodes. Another 72 certification and 18 stress trials document the training winner before the descent refinement on the training source and informed checkpoint selection and engineering refinement.',
            'limits': baseline['qualification']['limits']}}
    # No output is written until every gate passes. Timing changes cannot alter motor caps,
    # joint limits, collision resources, or any of the other 17 genome entries.
    require(model['physics'] == baseline['physics'] and model['genome'][3:] == baseline['genome'][3:],
            'An unrelated baseline physics/genome setting changed')
    payload = json.dumps(model, indent=2, allow_nan=False) + '\n'
    (OUT / 'model.json').write_text(payload)
    (RES / 'predator.json').write_text(payload)
    print(json.dumps(model['qualification'], indent=2))


if __name__ == '__main__':
    main()
