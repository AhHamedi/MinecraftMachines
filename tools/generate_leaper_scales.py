#!/usr/bin/env python3
"""Build real, cell-clipped Leaper meshes and collision solids for supported sizes.
The accepted 1x assets remain byte-for-byte unchanged. UVs are interpolated at
new cell boundaries, so material mapping and emissive eyes stay aligned.
"""
import itertools
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'minecraft_machines/common/src/main/resources'
DATA = RES / 'data/minecraft_machines/leaper'
ASSET = RES / 'assets/minecraft_machines'
SOURCE = ASSET / 'models/block/leaper'
OUT = ASSET / 'models/block/leaper_scaled'
SCALES = (1.5, 2.0)


def clip(poly, axis, bound, greater):
    out = []
    for a, b in zip(poly, poly[1:] + poly[:1]):
        ia = a[axis] >= bound - 1e-9 if greater else a[axis] <= bound + 1e-9
        ib = b[axis] >= bound - 1e-9 if greater else b[axis] <= bound + 1e-9
        if ia:
            out.append(a)
        if ia != ib:
            t = (bound - a[axis]) / (b[axis] - a[axis])
            out.append([v + t * (w - v) for v, w in zip(a, b)])
    return out


def cells_for(lo, hi):
    return itertools.product(*(range(math.floor(a + 1e-8),
                                    max(math.floor(a + 1e-8), math.floor(b - 1e-8)) + 1)
                               for a, b in zip(lo, hi)))


def generate():
    geometry = json.loads((DATA / 'geometry.json').read_text())
    collision = json.loads((DATA / 'collision_geometry.json').read_text())
    result = {'scales': list(SCALES), 'parts': {}, 'cells': []}
    variants = {}
    OUT.mkdir(parents=True, exist_ok=True)
    for mode in ('patrolling', 'alert', 'hunting'):
        (OUT / f'leaper_{mode}.mtl').write_bytes((SOURCE / f'leaper_{mode}.mtl').read_bytes())
    (OUT / 'leaper.mtl').write_bytes((SOURCE / 'leaper.mtl').read_bytes())
    for scale in SCALES:
        for part, indices in geometry['parts'].items():
            boxes, faces = {}, {}
            for index in indices:
                origin = [x - .5 for x in geometry['cells'][index]['offset']]
                for box in collision['cells'][index]['boxes']:
                    lo = [(box[a] + origin[a]) * scale + .5 for a in range(3)]
                    hi = [(box[a + 3] + origin[a]) * scale + .5 for a in range(3)]
                    for cell in cells_for(lo, hi):
                        a = [max(0, lo[k] - cell[k]) for k in range(3)]
                        b = [min(1, hi[k] - cell[k]) for k in range(3)]
                        if all(y - x > 1e-8 for x, y in zip(a, b)):
                            boxes.setdefault(cell, []).append(a + b)
                vertices, uvs, normals = [], [], []
                material = None
                for line in (SOURCE / f'cell_{index}.obj').read_text().splitlines():
                    fields = line.split()
                    if not fields:
                        continue
                    tag = fields[0]
                    if tag == 'v':
                        vertices.append([(float(fields[a + 1]) + origin[a]) * scale + .5 for a in range(3)])
                    elif tag == 'vt':
                        uvs.append(list(map(float, fields[1:])))
                    elif tag == 'vn':
                        normals.append(list(map(float, fields[1:])))
                    elif tag == 'usemtl':
                        material = fields[1]
                    elif tag == 'f':
                        ids = [list(map(int, f.split('/'))) for f in fields[1:]]
                        poly = [vertices[v - 1] + uvs[t - 1] for v, t, n in ids]
                        normal = normals[ids[0][2] - 1]
                        lo = [min(v[a] for v in poly) for a in range(3)]
                        hi = [max(v[a] for v in poly) for a in range(3)]
                        for cell in cells_for(lo, hi):
                            clipped = poly
                            for axis in range(3):
                                clipped = clip(clipped, axis, cell[axis], True)
                                clipped = clip(clipped, axis, cell[axis] + 1, False)
                            # Remove boundary duplicates and reject zero-area faces.
                            clean = []
                            for v in clipped:
                                if not any(sum((v[a] - w[a])**2 for a in range(3)) < 1e-16 for w in clean):
                                    clean.append(v)
                            area_axes = [a for a in range(3) if abs(normal[a]) < .5]
                            if len(clean) >= 3 and all(max(v[a] for v in clean) - min(v[a] for v in clean) > 1e-8 for a in area_axes):
                                faces.setdefault(cell, []).append((clean, material, normal))
            ids = []
            for cell in sorted(boxes.keys() | faces.keys()):
                index = len(result['cells'])
                ids.append(index)
                eye = any(mat == 'light' for _, mat, _ in faces.get(cell, []))
                result['cells'].append({'index': index, 'offset': cell, 'boxes': boxes.get(cell, []), 'eye': eye})
                lines = ['mtllib leaper.mtl']
                count, ni = 0, 0
                for poly, material, normal in faces.get(cell, []):
                    lines += ['usemtl ' + material, 'vn ' + ' '.join(map(str, normal))]
                    ni += 1
                    for v in poly:
                        lines.append('v ' + ' '.join(f'{v[a] - cell[a]:.9f}' for a in range(3)))
                        lines.append('vt ' + ' '.join(f'{x:.9f}' for x in v[3:]))
                    lines.append('f ' + ' '.join(f'{count + i + 1}/{count + i + 1}/{ni}' for i in range(len(poly))))
                    count += len(poly)
                (OUT / f'cell_{index}.obj').write_text('\n'.join(lines) + '\n')
                for mode in ('patrolling', 'alert', 'hunting') if eye else ('patrolling',):
                    model = {'loader': 'neoforge:obj', 'model': f'minecraft_machines:models/block/leaper_scaled/cell_{index}.obj',
                             'mtl_override': f'minecraft_machines:models/block/leaper_scaled/leaper_{mode}.mtl',
                             'automatic_culling': False, 'shade_quads': True, 'emissive_ambient': True,
                             'ambientocclusion': False, 'textures': {'particle': 'minecraft:block/polished_deepslate'}}
                    (OUT / f'cell_{index}_{mode}.json').write_text(json.dumps(model) + '\n')
                    key = f'part={index}' + (f',eye_mode={mode}' if eye else '')
                    variants[key] = {'model': f'minecraft_machines:block/leaper_scaled/cell_{index}_{mode}'}
            result['parts'][f'{part}_{int(scale * 100)}'] = ids
    # Dedicated block avoids changing the trained, accepted 1x blockstate contract.
    capacity = 512
    assert len(result['cells']) <= capacity
    for index in range(len(result['cells']), capacity):
        variants[f'part={index}'] = {'model': 'minecraft_machines:block/leaper_scaled/cell_0_patrolling'}
    (DATA / 'scaled_geometry.json').write_text(json.dumps(result, separators=(',', ':')) + '\n')
    (ASSET / 'blockstates/leaper_scaled_part.json').write_text(json.dumps({'variants': variants}, indent=2) + '\n')
    print(f"Generated {len(result['cells'])} physical cells for {SCALES}")


if __name__ == '__main__':
    generate()
