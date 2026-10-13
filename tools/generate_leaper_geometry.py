#!/usr/bin/env python3
"""Minecraft-styled Leaper skin: pixel-aligned cuboids, native block textures.
The accepted geometry.json is the physical contract and is read, never rewritten.
Rendering and precise collision share the same solid union. Legacy collision remains available.
"""
import hashlib
import json
import math
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / 'minecraft_machines/common/src/main/resources/assets/minecraft_machines'
MODEL = ASSET / 'models/block/leaper'
PHYSICS = ROOT / 'minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper/geometry.json'
physical_bytes = PHYSICS.read_bytes()
geometry = json.loads(physical_bytes)
MATERIALS = {
    'armor': ('polished_deepslate', (1, 1, 1)),
    'plate': ('deepslate_tiles', (1, 1, 1)),
    'edge': ('deepslate', (1, 1, 1)),
    'dark': ('blackstone', (.70, .73, .78)),
    'steel': ('iron_block', (.58, .61, .64)),
    'red': ('red_terracotta', (.90, .65, .57)),
    'light': ('white_concrete', (1, .16, .035)),
    'ember': ('redstone_block', (1, .60, .40)),
    'lens': ('black_concrete', (.70, .50, .50)),
}
mtl=[]
for name, (texture, color) in MATERIALS.items():
    mtl += [f'newmtl {name}', f'map_Kd minecraft:block/{texture}',
            'Kd ' + ' '.join(map(str, color)),
            'Ka ' + ('1 1 1' if name == 'light' else '0 0 0')]
(MODEL / 'leaper.mtl').write_text('\n'.join(mtl) + '\n')
faces=[]
solids=[]
box_count=0
stats={}
precise_cells={}
source_solids={}

def face(vertices, material):
    faces.append((np.array(vertices, dtype=float), material))

def box(lo, hi, material, omit=()):
    global box_count
    box_count += 1
    if omit:
        raise ValueError('Solid union owns face visibility')
    solids.append((tuple(lo),tuple(hi),material))


def exterior_surface():
    """Exact cuboid union using a compressed coordinate grid.

    Later cuboids provide the surface material where solids overlap. Only solid/air
    boundaries are emitted. Shared internal faces and coplanar overlays cannot
    survive; coplanar rectangles of the same material are merged greedily.
    """
    coords=[np.unique([v[a] for lo,hi,_ in solids for v in (lo,hi)]) for a in range(3)]
    labels=list(MATERIALS)
    occupancy=np.zeros(tuple(len(c)-1 for c in coords),dtype=np.int16)
    for lo,hi,mat in solids:
        bounds=[(int(np.searchsorted(coords[a],lo[a])),int(np.searchsorted(coords[a],hi[a]))) for a in range(3)]
        occupancy[tuple(slice(lo,hi) for lo,hi in bounds)]=labels.index(mat)+1
    for axis in range(3):
        others=[a for a in range(3) if a!=axis]
        layers=np.moveaxis(occupancy,axis,0)
        empty=np.zeros(layers.shape[1:],dtype=np.int16)
        for boundary in range(len(coords[axis])):
            before=empty if boundary==0 else layers[boundary-1]
            after=empty if boundary==len(layers) else layers[boundary]
            for sign,mask in ((1,np.where((before>0)&(after==0),before,0)),
                              (-1,np.where((after>0)&(before==0),after,0))):
                for material in np.unique(mask):
                    if material==0:continue
                    visible=mask==material
                    for u in range(visible.shape[0]):
                        for v in range(visible.shape[1]):
                            if not visible[u,v]:continue
                            V=v+1
                            while V<visible.shape[1] and visible[u,V]:V+=1
                            U=u+1
                            while U<visible.shape[0] and visible[U,v:V].all():U+=1
                            visible[u:U,v:V]=False
                            points=[]
                            for i,j in ((u,v),(U,v),(U,V),(u,V)):
                                point=np.zeros(3);point[axis]=coords[axis][boundary]
                                point[others[0]]=coords[others[0]][i]
                                point[others[1]]=coords[others[1]][j]
                                points.append(point)
                            normal=np.cross(points[1]-points[0],points[2]-points[0])
                            if normal[axis]*sign<0:points.reverse()
                            face(points,labels[material-1])
    solids.clear()

def clip(poly, axis, bound, greater):
    if len(poly)==0: return poly
    out=[]
    for a,b in zip(poly,np.roll(poly,-1,axis=0)):
        ia=a[axis]>=bound-1e-9 if greater else a[axis]<=bound+1e-9
        ib=b[axis]>=bound-1e-9 if greater else b[axis]<=bound+1e-9
        if ia:out.append(a)
        if ia!=ib:out.append(a+(b-a)*(bound-a[axis])/(b[axis]-a[axis]))
    return np.array(out)

def save(name):
    global box_count
    source_solids[name] = [[list(lo),list(hi)] for lo,hi,_ in solids]
    # Clip the authored solid union to each Sable cell, then partition it into
    # disjoint cuboids. Internal overlaps never become duplicate contact faces.
    for idx in geometry['parts'][name]:
        cell = geometry['cells'][idx]
        origin = np.array(cell['offset'], dtype=float)-.5
        clipped=[]
        for lo,hi,_ in solids:
            a=np.maximum(np.array(lo)-origin,0); b=np.minimum(np.array(hi)-origin,1)
            if np.all(b-a>1e-9): clipped.append((a,b))
        merged=[]
        if clipped:
            coords=[np.unique([v[a] for lo,hi in clipped for v in (lo,hi)]) for a in range(3)]
            mask=np.zeros(tuple(len(c)-1 for c in coords),dtype=bool)
            for lo,hi in clipped:
                ij=[(np.searchsorted(coords[a],lo[a]),np.searchsorted(coords[a],hi[a])) for a in range(3)]
                mask[tuple(slice(a,b) for a,b in ij)]=True
            while mask.any():
                x,y,z=np.argwhere(mask)[0]; X=x+1;Y=y+1;Z=z+1
                while Z<mask.shape[2] and mask[x,y,Z]:Z+=1
                while Y<mask.shape[1] and mask[x,Y,z:Z].all():Y+=1
                while X<mask.shape[0] and mask[X,y:Y,z:Z].all():X+=1
                merged.append([float(coords[0][x]),float(coords[1][y]),float(coords[2][z]),float(coords[0][X]),float(coords[1][Y]),float(coords[2][Z])])
                mask[x:X,y:Y,z:Z]=False
        precise_cells[idx]={'index':idx,'offset':cell['offset'],'boxes':merged}
    exterior_surface()
    ids=geometry['parts'][name]
    cells={tuple(geometry['cells'][i]['offset']): [] for i in ids}
    for p, mat in faces:
        p=p+.5
        lo=np.floor(p.min(axis=0)+1e-7).astype(int)
        hi=np.maximum(lo,np.floor(p.max(axis=0)-1e-7).astype(int))
        for x in range(lo[0],hi[0]+1):
            for y in range(lo[1],hi[1]+1):
                for z in range(lo[2],hi[2]+1):
                    cell=(x,y,z); poly=p.copy()
                    for axis in range(3):
                        poly=clip(poly,axis,cell[axis],True)
                        poly=clip(poly,axis,cell[axis]+1,False)
                    if len(poly)<3:continue
                    n=np.cross(poly[1]-poly[0],poly[2]-poly[0]);mag=np.linalg.norm(n)
                    if mag<1e-10:continue
                    if cell not in cells:
                        raise ValueError(f'{name} visual exceeds existing assembly cell: {cell}')
                    cells[cell].append((poly-np.array(cell),mat,n/mag))
    quads=0
    for idx in ids:
        c=geometry['cells'][idx]; rendered=cells[tuple(c['offset'])]
        lines=['mtllib leaper.mtl']; vi=0; ni=0; ti=0
        for p,mat,n in rendered:
            assert len(p)==4, 'Every visible surface must remain a cuboid face'
            axis=int(np.argmax(np.abs(n)))
            assert np.isclose(abs(n[axis]),1), 'No smooth or diagonal polygon surfaces'
            lines += ['usemtl '+mat, 'vn '+' '.join(f'{v:.7f}' for v in n)]; ni+=1
            lines += ['v '+' '.join(f'{v:.7f}' for v in point) for point in p]
            # Cell-local planar UVs: native 16 px/block, continuous through cell cuts.
            u,v=((2,1) if axis==0 else (0,2) if axis==1 else (0,1))
            lines += [f'vt {point[u]:.7f} {point[v]:.7f}' for point in p]
            lines.append('f '+' '.join(f'{vi+j+1}/{ti+j+1}/{ni}' for j in range(4)))
            vi+=4; ti+=4; quads+=1
        (MODEL/f'cell_{idx}.obj').write_text('\n'.join(lines)+'\n')
        (MODEL/f'cell_{idx}.json').write_text(json.dumps({
            'loader':'neoforge:obj','model':f'minecraft_machines:models/block/leaper/cell_{idx}.obj',
            'automatic_culling':False,'shade_quads':True,'emissive_ambient':True,
            'ambientocclusion':False,'textures':{'particle':'minecraft:block/polished_deepslate'}}))
    stats[name]={'cuboids':box_count,'quads':quads,'physical_cells_unchanged':len(ids)}
    faces.clear();box_count=0

# Layered voxel core: chamfered squares, 1/8 block steps, chunky separate crown plates.
def octagonal_layer(y0,y1,width,depth,mat):
    cut=.25 if width>=.75 else .125
    box((-width+cut,y0,-depth),(width-cut,y1,depth),mat)
    box((-width,y0,-depth+cut),(-width+cut,y1,depth-cut),mat)
    box((width-cut,y0,-depth+cut),(width,y1,depth-cut),mat)

for y0,y1,width,depth,mat in [
    (-1.0,-.875,.50,.50,'dark'),(-.875,-.75,.75,.625,'edge'),
    (-.75,-.50,.875,.75,'armor'),(-.50,.50,1.0,.875,'plate'),
    (.50,.75,.875,.75,'armor'),(.75,.875,.75,.625,'edge'),
    (.875,1.0,.50,.50,'dark')]:
    octagonal_layer(y0,y1,width,depth,mat)
# Pixel disk and ring in front of the faceted core. The centre is recessed.
step=.125
for ix in range(-7,7):
    for iy in range(-7,7):
        x=(ix+.5)*step;y=(iy+.5)*step;r=math.hypot(x,y)
        if r>.86:continue
        if r>.75: mat,z='edge',-1.0
        elif r>.60: mat,z='light',-1.0625
        elif r>.48: mat,z='dark',-1.0
        else: mat,z='lens',-.9375
        box((ix*step,iy*step,z),((ix+1)*step,(iy+1)*step,-.875),mat)
# Chunky armour lugs, crown ribs and rear vents.
for x in [-.75,.50]:
    box((x,-.5,-1.0),(x+.25,-.25,-.8125),'dark')
    box((x,.25,-1.0),(x+.25,.5,-.8125),'dark')
for x in [-.5,.25]:
    box((x,.75,-.625),(x+.25,.9375,.5),'armor')
for x in [-.5,-.25,0,.25]:
    box((x,-.375,.875),(x+.125,.375,.9375),'dark')
box((-.375,-.9375,-.625),(.375,-.8125,-.4375),'ember')
save('core')

# Square universal bearing housing; same hip pivot and simulated body.
box((-.25,-.1875,-.1875),(.25,.1875,.1875),'steel')
for x0,x1 in [(-.3125,-.25),(.25,.3125)]:
    box((x0,-.25,-.25),(x1,.25,.25),'dark')
    box((x0-.015625,-.125,-.125),(x1+.015625,.125,.125),'armor')
box((-.125,.1875,-.125),(.125,.3125,.125),'dark')
save('hip')

# Straight rectangular armour shells over a square backbone; steps replace all tapers.
box((-.1875,.0625,-.1875),(.1875,3.875,.1875),'dark')
box((-.25,.3125,-.25),(.25,.625,.25),'steel')
for a,b,w in [(.625,1.5,.3125),(1.5625,2.5,.4375),(2.5625,3.5,.5)]:
    box((-w,a,-.3125),(w,b,.25),'plate')
    box((-w,a,-.375),(w,a+.125,.25),'edge')
    box((-w,b-.125,-.375),(w,b,.25),'armor')
    box((-.125,a+.125,-.390625),(.125,b-.125,-.375),'armor')
    box((-.1875,a+.125,.25),(.1875,b-.125,.3125),'red')
box((-.125,.625,.3125),(.125,3.375,.34375),'dark')
for a in [.6875,1.875,2.9375]:
    box((-.0625,a,.34375),(.0625,a+.3125,.375),'ember')
# Large squared knee cheeks and an open backing bracket.
box((-.5625,3.75,-.25),(.5625,4.25,.25),'steel')
for x0,x1 in [(-.625,-.50),(.50,.625)]:
    box((x0,3.625,-.3125),(x1,4.375,.3125),'dark')
    box((x0-.015625,3.8125,-.1875),(x1+.015625,4.1875,.1875),'armor')
box((-.375,3.5,.25),(.375,4.5,.375),'plate')
box((-.4375,4.5,.1875),(.4375,4.625,.4375),'edge')
for x in [-.3125,.25]:box((x,3.125,.375),(x+.0625,4.375,.4375),'steel')
save('upper')

# Three stepped shin shells, exposed piston gaps, and a square toe pad.
box((-.125,.0,-.125),(.125,4.0,.125),'dark')
for a,b,w,d in [(.25,1.4375,.375,.3125),(1.625,2.875,.3125,.25),(3.0625,3.9375,.1875,.1875)]:
    box((-w,a,-d),(w,b,d),'armor')
    box((-w,a,-d-.0625),(w,a+.125,d),'edge')
    box((-w,b-.125,-d-.0625),(w,b,d),'plate')
    box((-.0625,a+.1875,d),(.0625,b-.1875,d+.03125),'red')
for a,b,w in [(1.4375,1.625,.15625),(2.875,3.0625,.125)]:
    box((-w,a,-w),(w,b,w),'steel')
    box((-.0625,a,w),(.0625,b,w+.03125),'ember')
box((-.25,-.375,.25),(.25,.25,.375),'plate')
box((-.3125,-.4375,.1875),(.3125,-.3125,.4375),'edge')
box((-.0625,.4375,.34375),(.0625,2.6875,.4375),'steel')
box((-.125,.5,.3125),(.125,1.25,.46875),'dark')
box((-.125,4.0,-.125),(.125,4.375,.125),'steel')
box((-.25,4.3125,-.25),(.25,4.625,.25),'dark')
box((-.1875,4.375,-.3125),(.1875,4.5625,-.25),'armor')
save('lower')
precise={'version':'minecraft_cuboid_collision_v1','parts':geometry['parts'],
         'cells':[precise_cells[i] for i in range(len(geometry['cells']))]}
(PHYSICS.parent/'collision_geometry.json').write_text(json.dumps(precise,indent=2)+'\n')
(ROOT/'artifacts/leaper-terrain-v2/authored-solids.json').write_text(json.dumps(source_solids,indent=2)+'\n')
assert PHYSICS.read_bytes()==physical_bytes
summary={'style':'minecraft_cuboid_v2','parts':stats,'physics_sha256':hashlib.sha256(physical_bytes).hexdigest(),
         'legacy_physics_changed':False,'precise_collision_generated':True,'textures':'native Minecraft block textures; planar 16 px/block UVs',
         'surface_topology':'exact solid union; no coincident exterior surfaces or internal faces',
         'source':'original cuboid model based on supplied Minecraft-style inspiration'}
(ROOT/'artifacts/leaper/mesh-statistics.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))

# Behaviour variants share these exact meshes and preserve their collision contract.
from generate_leaper_eyes import generate as generate_eyes
generate_eyes()
