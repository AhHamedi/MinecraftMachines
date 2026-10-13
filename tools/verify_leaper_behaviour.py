#!/usr/bin/env python3
"""Verify Apex awareness, continuous encounters, physical recovery, home arrival, and synchronized eyes."""
import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

from verify_leaper_hunting import ROOT, COMMON, NEO, source_fingerprint
from prepare_leaper_preview import prepare_preview


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apex', action='store_true', default=True, help='Compatibility flag; Apex is always used')
    parser.add_argument('--offline', action='store_true', help='Use cached Gradle dependencies without network lookups')
    args = parser.parse_args()
    env = os.environ.copy()
    env.setdefault('RAYON_NUM_THREADS', '4')
    prepare_preview(NEO / 'build/leaper-scratch-gametest/leaper_learning')
    if not env.get('JAVA_HOME'):
        homes = sorted((Path.home() / '.local/jdks').glob('*21*/Contents/Home'))
        if homes:
            env['JAVA_HOME'] = str(homes[-1])
    output = ROOT / 'artifacts/leaper-behaviour' / datetime.datetime.now().strftime('verification-%Y%m%d-%H%M%S')
    output.mkdir(parents=True, exist_ok=False)
    fingerprint = source_fingerprint()
    summary = {'implementation_sha256': fingerprint, 'version': 'apex', 'complete': False}

    def save():
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')

    def run(name, command):
        print(f'Running {name}; log: {output / (name + ".log")}', flush=True)
        with (output / (name + '.log')).open('w') as log:
            subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)

    save()
    gradle = [str(ROOT / 'gradlew')] + (['--offline'] if args.offline else [])
    run('build', [*gradle, ':minecraft_machines:common:test', ':minecraft_machines:neoforge:jar'])
    unit = [ET.parse(p).getroot().attrib for p in (COMMON / 'build/test-results/test').glob('TEST-*.xml')]
    summary['unit_tests'] = {k: sum(int(row.get(k, 0)) for row in unit)
                             for k in ('tests', 'failures', 'errors', 'skipped')}
    run('eyes', ['python3', str(ROOT / 'tools/verify_leaper_eyes.py')])
    summary['eyes'] = json.loads((output / 'eyes.log').read_text())
    reports = [NEO / 'build/leaper-scratch-gametest/leaper_learning' / f'horrific-predator-behaviour-{name}.json'
               for name in ('perception', 'rig', 'return-home', 'recovery', 'home-route', 'encounter', 'footing', 'shockwave', 'launch-pursuit')]
    reports.append(NEO / 'build/leaper-scratch-gametest/leaper_learning/horrific-predator-shore-pursuit.json')
    previous = {p: p.stat().st_mtime_ns if p.exists() else None for p in reports}
    run('native', [*gradle, ':minecraft_machines:neoforge:runGameTest',
                   '-PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning',
                   '-PleaperScratch=true', '-PleaperMode=predator-behaviour', '-PleaperProfile=horrific',
                   '-PleaperHighStepCandidate=true', '-PleaperAgile=true', '-PleaperLeap=true',
                   f'-PleaperApex={str(args.apex).lower()}'])
    for path in reports:
        if not path.exists() or path.stat().st_mtime_ns == previous[path]:
            raise RuntimeError(f'Missing fresh native report: {path}')
        data = json.loads(path.read_text())
        if data.get('implementation_sha256') != fingerprint:
            raise RuntimeError(f'Stale implementation fingerprint: {path}')
        if path.name.endswith('perception.json'):
            passed = data.get('validated') is True and len(data.get('cases', [])) >= 42
            passed = passed and all(row.get('success') is True for row in data['cases'])
            summary['perception_checks'] = len(data.get('cases', []))
        elif path.name.endswith('rig.json'):
            passed = data.get('complete') is True and data.get('success') is True
            summary['physical_rig'] = {k: v for k, v in data.items() if k != 'history'}
        elif path.name.endswith('recovery.json'):
            passed = data.get('complete') is True and data.get('success') is True
            passed = passed and len(data.get('cases', [])) >= 7
            passed = passed and all(row.get('success') is True for row in data.get('cases', []))
            summary['recovery'] = data
        elif path.name.endswith('home-route.json'):
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('local_leg_verified') is True and data.get('final_distance', 100) <= 4)
            summary['home_route'] = data
        elif path.name.endswith('encounter.json'):
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('controller') == 'apex' and len(data.get('cases', [])) >= 7
                      and all(row.get('success') is True for row in data.get('cases', [])))
            summary['continuous_encounter'] = data
        elif path.name.endswith('launch-pursuit.json'):
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('distant_tracking_frames', 0) > 8
                      and data.get('airborne_ground_goal_frames', 0) > 8
                      and data.get('hunt_search_destinations', 0) >= 2
                      and data.get('alert_search_destinations', 0) >= 2
                      and data.get('occlusion_and_acquisition_checked') is True)
            summary['launched_pursuit'] = data
        elif path.name.endswith('shockwave.json'):
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('blasts') == 1 and data.get('cooldown_ticks') == 80
                      and data.get('block_radius') == 10 and data.get('outer_blocks_destroyed', 0) > 0
                      and data.get('commitment_survived_target_loss_stop_and_disable') is True
                      and data.get('outside_radius_preserved') is True
                      and data.get('underneath_blocks_destroyed', 0) > 0)
            summary['committed_shockwave'] = data
        elif path.name.endswith('shore-pursuit.json'):
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('scale') == 1.5 and data.get('travel', 0) > 6
                      and data.get('accepted', 0) > 0 and data.get('rejected', 0) > 0)
            summary['shore_pursuit'] = data
        elif path.name.endswith('footing.json'):
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('partial_footing') is True and data.get('walking_stance_unavailable') is True
                      and data.get('launches') == 1 and data.get('final_distance', 100) < 2.5)
            summary['footing_recovery'] = data
        else:
            passed = (data.get('complete') is True and data.get('success') is True
                      and data.get('reacquired_visible_player') is True
                      and 4 < data.get('original_home_distance', 0) < 8)
            summary['return_home'] = {k: v for k, v in data.items() if k != 'history'}
        if not passed:
            raise RuntimeError(f'Incomplete or failed native report: {path}')
        shutil.copy2(path, output / path.name)
    if source_fingerprint() != fingerprint:
        raise RuntimeError('Implementation changed during verification; rerun against stable source')
    summary['complete'] = True
    save()
    print(f'Verified. Results: {output / "summary.json"}', flush=True)


if __name__ == '__main__':
    main()
