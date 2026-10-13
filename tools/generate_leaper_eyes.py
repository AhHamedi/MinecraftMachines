#!/usr/bin/env python3
"""Generate synchronized Leaper eye materials without changing geometry or collision.

Each mode uses the exact same OBJ, UVs and emissive settings. NeoForge's native
material-library override changes only the eye ring's `light` diffuse color.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / 'minecraft_machines/common/src/main/resources'
ASSET = RESOURCES / 'assets/minecraft_machines'
MODEL = ASSET / 'models/block/leaper'
DATA = RESOURCES / 'data/minecraft_machines/leaper'

EYE_COLORS = {
    'patrolling': (0.075, 0.40, 1.0),
    'alert': (1.0, 0.40, 0.035),
    'hunting': (1.0, 0.055, 0.035),
}


def eye_material(source, color):
    """Keep all non-eye material properties byte for byte equivalent."""
    material = None
    result = []
    replacements = 0
    for line in source.splitlines():
        if line.startswith('newmtl '):
            material = line.split(maxsplit=1)[1]
        if material == 'light' and line.startswith('Kd '):
            line = 'Kd ' + ' '.join(map(str, color))
            replacements += 1
        result.append(line)
    if replacements != 1:
        raise ValueError('Expected exactly one emissive eye material')
    return '\n'.join(result) + '\n'


def generate():
    geometry = json.loads((DATA / 'geometry.json').read_text())
    core = set(geometry['parts']['core'])
    eye_cells = []
    for cell in geometry['cells']:
        index = cell['index']
        lines = (MODEL / f'cell_{index}.obj').read_text().splitlines()
        if 'usemtl light' in lines:
            if index not in core:
                raise ValueError(f'Eye material on non-core cell {index}')
            eye_cells.append(index)
    if not eye_cells:
        raise ValueError('Leaper geometry contains no eye faces')

    base_material = (MODEL / 'leaper.mtl').read_text()
    for mode, color in EYE_COLORS.items():
        (MODEL / f'leaper_{mode}.mtl').write_text(eye_material(base_material, color))
    # Item previews and model fallback also show the calm, unalerted state.
    (MODEL / 'leaper.mtl').write_text(eye_material(base_material, EYE_COLORS['patrolling']))

    for index in eye_cells:
        model = json.loads((MODEL / f'cell_{index}.json').read_text())
        for mode in EYE_COLORS:
            variant = dict(model)
            variant['mtl_override'] = f'minecraft_machines:models/block/leaper/leaper_{mode}.mtl'
            (MODEL / f'cell_{index}_{mode}.json').write_text(
                json.dumps(variant, indent=2) + '\n')

    variants = {}
    valid_indices = {cell['index'] for cell in geometry['cells']}
    for index in range(256):
        source = index if index in valid_indices else 0
        if source in eye_cells:
            for mode in EYE_COLORS:
                variants[f'eye_mode={mode},part={index}'] = {
                    'model': f'minecraft_machines:block/leaper/cell_{source}_{mode}'}
        else:
            variants[f'part={index}'] = {
                'model': f'minecraft_machines:block/leaper/cell_{source}'}
    # PRECISE intentionally has no visual variant: it selects collision only.
    (ASSET / 'blockstates/leaper_part.json').write_text(
        json.dumps({'variants': variants}, indent=2) + '\n')
    (DATA / 'eye_cells.json').write_text(json.dumps({
        'material': 'light', 'cells': sorted(eye_cells),
        'colors': EYE_COLORS,
    }, indent=2) + '\n')
    return eye_cells


if __name__ == '__main__':
    print(json.dumps({'eye_cells': generate(), 'modes': list(EYE_COLORS)}))
