#!/usr/bin/env python3
"""Verify physical resize commands, native movement/bursts, and generated geometry."""
import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
from verify_leaper_hunting import ROOT, NEO, source_fingerprint
from prepare_leaper_preview import prepare_preview


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    env = os.environ.copy()
    env.setdefault('RAYON_NUM_THREADS', '4')
    prepare_preview(NEO / 'build/leaper-scratch-gametest/leaper_learning')
    if not env.get('JAVA_HOME'):
        homes = sorted((Path.home() / '.local/jdks').glob('*21*/Contents/Home'))
        if homes:
            env['JAVA_HOME'] = str(homes[-1])
    output = ROOT / 'artifacts/leaper-scale' / datetime.datetime.now().strftime('verification-%Y%m%d-%H%M%S')
    output.mkdir(parents=True)
    fingerprint = source_fingerprint()
    report = NEO / 'build/leaper-scratch-gametest/leaper_learning/horrific-predator-scale.json'
    before = report.stat().st_mtime_ns if report.exists() else None
    command = [str(ROOT / 'gradlew')] + (['--offline'] if args.offline else []) + [
        ':minecraft_machines:neoforge:runGameTest',
        '-PgameTestNamespaces=minecraft_machines_quadruped_leaper_learning',
        '-PleaperScratch=true', '-PleaperMode=predator-scale', '-PleaperProfile=horrific',
        '-PleaperHighStepCandidate=true', '-PleaperAgile=true', '-PleaperLeap=true']
    print(f'Running physical scale verification: {output}', flush=True)
    with (output / 'native.log').open('w') as log:
        subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    if not report.exists() or report.stat().st_mtime_ns == before:
        raise RuntimeError('Missing fresh scale report')
    data = json.loads(report.read_text())
    if data.get('implementation_sha256') != fingerprint or source_fingerprint() != fingerprint:
        raise RuntimeError('Source changed during verification')
    if not all(data.get(key) is True for key in ('complete', 'success', 'scaled_ground_attack', 'command_rejections', 'state_preserved')):
        raise RuntimeError('Native scale verification incomplete')
    geometry = json.loads(subprocess.check_output(['python3', str(ROOT / 'tools/verify_leaper_scales.py')], cwd=ROOT))
    if not geometry['success']:
        raise RuntimeError('Geometry verification failed')
    shutil.copy2(report, output / report.name)
    (output / 'summary.json').write_text(json.dumps({'complete': True, 'implementation_sha256': fingerprint,
        'native': data, 'geometry': geometry}, indent=2) + '\n')
    print(f'Verified: {output / "summary.json"}', flush=True)


if __name__ == '__main__':
    main()
