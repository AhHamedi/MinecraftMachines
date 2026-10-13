#!/usr/bin/env python3
"""Check all synchronized eye states resolve to unchanged geometry and emissive materials."""
import json

from generate_leaper_eyes import ASSET, DATA, EYE_COLORS, MODEL, eye_material


def verify():
    geometry = json.loads((DATA / 'geometry.json').read_text())
    metadata = json.loads((DATA / 'eye_cells.json').read_text())
    eye_cells = set(metadata['cells'])
    authored = {
        cell['index'] for cell in geometry['cells']
        if 'usemtl light' in (MODEL / f"cell_{cell['index']}.obj").read_text().splitlines()
    }
    assert eye_cells == authored, 'Synchronized eye cells must match the actual mesh'
    assert eye_cells and eye_cells <= set(geometry['parts']['core'])
    assert metadata['colors'] == {mode: list(color) for mode, color in EYE_COLORS.items()}

    base_material = (MODEL / 'leaper.mtl').read_text()
    assert base_material == eye_material(base_material, EYE_COLORS['patrolling'])
    for mode, color in EYE_COLORS.items():
        variant_material = (MODEL / f'leaper_{mode}.mtl').read_text()
        assert variant_material == eye_material(base_material, color), mode
        light = variant_material.split('newmtl light\n', 1)[1].split('newmtl ', 1)[0]
        assert 'Ka 1 1 1\n' in light, 'Every mode must retain emissive eyes'

    variants = json.loads((ASSET / 'blockstates/leaper_part.json').read_text())['variants']
    selectors = [(dict(item.split('=') for item in key.split(',')), model)
                 for key, model in variants.items()]
    valid_indices = {cell['index'] for cell in geometry['cells']}
    states_checked = 0
    for index in range(256):
        source = index if index in valid_indices else 0
        original = json.loads((MODEL / f'cell_{source}.json').read_text())
        for mode in EYE_COLORS:
            for precise in ('false', 'true'):
                state = {'part': str(index), 'eye_mode': mode, 'precise': precise}
                matches = [model for selector, model in selectors
                           if all(state[key] == value for key, value in selector.items())]
                assert len(matches) == 1, (state, matches)
                resource = matches[0]['model'].removeprefix('minecraft_machines:')
                model = json.loads((ASSET / 'models' / f'{resource}.json').read_text())
                if source in eye_cells:
                    assert model.pop('mtl_override') == (
                        f'minecraft_machines:models/block/leaper/leaper_{mode}.mtl')
                assert model == original, 'Modes must preserve geometry, UVs and shading'
                states_checked += 1
    print(json.dumps({'passed': True, 'states': states_checked,
                      'eye_cells': sorted(eye_cells), 'colors': EYE_COLORS}))


if __name__ == '__main__':
    verify()
