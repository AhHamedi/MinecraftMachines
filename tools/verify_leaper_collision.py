#!/usr/bin/env python3
"""Independent OBJ ray parity vs Sable cuboid occupancy on their exact coordinate partition."""
from pathlib import Path
import json
import hashlib
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'minecraft_machines/common/src/main/resources'
data=json.loads((RES/'data/minecraft_machines/leaper/collision_geometry.json').read_text())
checks=[]
part_faces={}
for name,indices in data['parts'].items():
    quads=[]
    for idx in indices:
        cell=data['cells'][idx];v=[]
        for line in (RES/f"assets/minecraft_machines/models/block/leaper/cell_{idx}.obj").read_text().splitlines():
            if line.startswith('v '):v.append(np.array([float(x) for x in line.split()[1:]])+cell['offset'])
            if line.startswith('f '):quads.append(np.array([v[int(x.split('/')[0])-1] for x in line.split()[1:]]))
    for idx in indices:part_faces[idx]=quads
for cell in data['cells']:
    path=RES/f"assets/minecraft_machines/models/block/leaper/cell_{cell['index']}.obj"
    vertices=[];faces=[]
    for line in path.read_text().splitlines():
        if line.startswith('v '):vertices.append([float(v) for v in line.split()[1:]])
        if line.startswith('f '):faces.append([int(v.split('/')[0])-1 for v in line.split()[1:]])
    verts=np.array(vertices);boxes=np.array(cell['boxes']).reshape(-1,6)
    global_verts=np.concatenate(part_faces[cell['index']])-np.array(cell['offset'])
    coords=[np.unique(np.clip([0,1,*global_verts[:,axis],*boxes[:,axis],*boxes[:,axis+3]],0,1)) for axis in range(3)]
    # OBJ seven-decimal formatting has sub-microvoxel rounding. Merge that noise.
    coords=[np.unique(np.round(c,6)) for c in coords]
    mids=[(c[:-1]+c[1:])/2 for c in coords]
    points=np.stack(np.meshgrid(*mids,indexing='ij'),axis=-1).reshape(-1,3)
    parity=np.zeros(len(points),dtype=bool)
    for global_quad in part_faces[cell['index']]:
        quad=global_quad-np.array(cell['offset']);lo=quad.min(axis=0);hi=quad.max(axis=0)
        if hi[0]-lo[0]>1e-7:continue
        hit=(points[:,0]<lo[0])&(points[:,1]>lo[1])&(points[:,1]<hi[1])&(points[:,2]>lo[2])&(points[:,2]<hi[2])
        parity^=hit
    occupied=np.zeros(len(points),dtype=bool)
    for b in boxes:occupied|=np.all((points>b[:3])&(points<b[3:]),axis=1)
    mismatch=int(np.count_nonzero(parity!=occupied))
    checks.append({'cell':cell['index'],'partition_samples':len(points),'boxes':len(boxes),'mismatches':mismatch})
    assert mismatch==0,(cell['index'],mismatch)
report={'method':'Independent ray parity against exported OBJ surfaces, exact merged coordinate partition','tolerance_blocks':1e-6,'cells':checks,'total_samples':sum(c['partition_samples'] for c in checks),'collision_sha256':hashlib.sha256((RES/'data/minecraft_machines/leaper/collision_geometry.json').read_bytes()).hexdigest()}
out=ROOT/'artifacts/leaper-terrain-v2/collision-verification.json';out.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='cells'},indent=2))
