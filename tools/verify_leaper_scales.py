#!/usr/bin/env python3
"""Independent volume, bounds and cell-local mesh checks for enlarged physical assets."""
import json
import math
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'minecraft_machines/common/src/main/resources'
DATA = RES / 'data/minecraft_machines/leaper'
source = json.loads((DATA / 'collision_geometry.json').read_text())
scaled = json.loads((DATA / 'scaled_geometry.json').read_text())
models = RES / 'assets/minecraft_machines/models/block'


def solids(data, ids):
    return [[b[a % 3 + (3 if a >= 3 else 0)] + data['cells'][i]['offset'][a % 3] - .5
             for a in range(6)] for i in ids for b in data['cells'][i]['boxes']]


def volume(boxes):
    return sum(math.prod(b[a+3] - b[a] for a in range(3)) for b in boxes)


def bounds(boxes):
    return [min(b[a] for b in boxes) if a < 3 else max(b[a] for b in boxes) for a in range(6)]


rows = []
for scale in scaled['scales']:
    for part, ids in source['parts'].items():
        original = solids(source, ids)
        enlarged_ids = scaled['parts'][f'{part}_{int(scale * 100)}']
        enlarged = solids(scaled, enlarged_ids)
        ratio = volume(enlarged) / volume(original)
        assert math.isclose(ratio, scale**3, abs_tol=1e-8), (part, scale, ratio)
        assert all(math.isclose(a*scale, b, abs_tol=1e-8) for a, b in zip(bounds(original), bounds(enlarged)))
        eye_faces = 0
        for index in enlarged_ids:
            cell = scaled['cells'][index]
            for box in cell['boxes']:
                assert all(-1e-9 <= v <= 1+1e-9 for v in box)
            lines = (models / f'leaper_scaled/cell_{index}.obj').read_text().splitlines()
            assert cell['eye'] == ('usemtl light' in lines)
            eye_faces += cell['eye']
            for line in lines:
                if line.startswith('v '):
                    assert all(-1e-8 <= float(v) <= 1+1e-8 for v in line.split()[1:])
        assert (eye_faces > 0) == (part == 'core')
        rows.append({'scale': scale, 'part': part, 'volume_ratio': ratio, 'cells': len(enlarged_ids), 'eyes': eye_faces})
print(json.dumps({'success': True, 'checks': rows}, indent=2))
