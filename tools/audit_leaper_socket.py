import json,numpy as np,math
parts=json.load(open('artifacts/leaper-terrain-v2/authored-solids.json'))
core=parts['core']; upper=parts['upper']
# Uniform solid sample points in the proximal upper-link material.
points=[]
for x in np.arange(-.25,.251,.025):
 for y in np.arange(.025,.651,.025):
  for z in np.arange(-.25,.251,.025):
   p=np.array([x,y,z])
   if any(np.all(p>=lo)&np.all(p<=hi) for lo,hi in upper):points.append(p)
points=np.array(points)
for shoulder in [0,.2,.4,.6,.8,1.0]:
 theta=math.radians(55);alpha=math.radians(27)+shoulder
 X=[math.cos(theta),0,math.sin(theta)]
 Y=[math.sin(theta)*math.cos(alpha),math.sin(alpha),-math.cos(theta)*math.cos(alpha)]
 Z=np.cross(X,Y)
 rot=np.column_stack([X,Y,Z]);hip=np.array([1.03*math.sin(theta),.55,-1.03*math.cos(theta)])
 world=points@rot.T+hip
 inside=np.zeros(len(world),bool)
 for lo,hi in core:inside|=np.all((world>np.array(lo)+1e-4)&(world<np.array(hi)-1e-4),axis=1)
 print('shoulder',shoulder,'intersecting proximal samples',sum(inside),'of',len(world))
