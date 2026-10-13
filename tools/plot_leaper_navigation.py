#!/usr/bin/env python3
"""Figures derived solely from recorded Minecraft/Sable qualification trajectories."""
from pathlib import Path
import json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/leaper-navigation'
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,
                     'axes.spines.right':False,'axes.titleweight':'bold','figure.facecolor':'#f7f7f5',
                     'axes.facecolor':'#ffffff','axes.edgecolor':'#bac1c4','grid.color':'#e5e8e9'})
colors=['#ba4839','#c48432','#698947','#3a9384','#477bad','#7e69a9','#aa5985','#596677']
report=json.loads((OUT/'packaged-qualification.json').read_text())
trials=report['trials']
errors=np.array([r['final_error'] for r in trials])
fig,axes=plt.subplots(1,2,figsize=(14,7))
fig.subplots_adjust(top=.75,bottom=.13,left=.075,right=.97,wspace=.3)
fig.text(.075,.925,'The Leaper reaches selected points',fontsize=24,weight='bold')
fig.text(.075,.855,f"{sum(r['success'] for r in trials)}/{len(trials)} arrivals passed · {errors.mean():.3f} blocks mean error · {errors.max():.3f} blocks maximum",fontsize=13)
ax=axes[0]
for i,r in enumerate(trials[:8]):
    trace=np.array([h['position'] for h in r['history']]);start=np.array(r['start']);g=np.array(r['goal_offset'])
    ax.plot(trace[:,0]-start[0],-(trace[:,2]-start[2]),color=colors[i],lw=1.8)
    ax.scatter(g[0],-g[1],marker='x',s=70,color=colors[i],linewidths=2)
ax.scatter(0,0,c='#283237',s=30)
ax.set_aspect('equal');ax.grid();ax.set_title('Eight directions · physical chassis paths',loc='left',pad=14)
ax.set_xlabel('East / west (blocks)');ax.set_ylabel('North / south (blocks)')
ax=axes[1]
for floor,color in zip(['smooth_stone','grass_block','oak_planks','cobblestone'],colors[:4]):
    ids=[i for i,r in enumerate(trials) if r['floor'].endswith(floor)]
    ax.scatter(np.array(ids)+1,errors[ids],s=30,c=color,label=floor.replace('_',' '))
ax.axhline(.48,c='#b14c43',ls='--',lw=1.2);ax.text(1,.495,'Qualification limit: 0.48 blocks',fontsize=9,c='#99423b')
ax.set_ylim(0,.57);ax.set_xlim(0,len(trials)+1);ax.grid(axis='y');ax.set_title('Arrival followed by a five-second hold',loc='left',pad=14)
ax.set_xlabel('Independent trial');ax.set_ylabel('Final distance from destination (blocks)');ax.legend(frameon=False,fontsize=9,loc='upper right',ncol=2)
fig.text(.075,.03,'Minecraft 1.21.1 / Sable 2.0.0 · clear flat ground · observed rigid-body movement',fontsize=9,c='#637077')
fig.savefig(OUT/'navigation-results.png',dpi=180);fig.savefig(OUT/'navigation-results.pdf');plt.close(fig)

fig,axes=plt.subplots(1,2,figsize=(12,5))
for ax,name,title in zip(axes,['gait-candidate','navigation-candidate'],['Foot-space gait','Destination feedback']):
    d=json.loads((OUT/(name+'.json')).read_text());history=d['history'];groups={}
    for h in history: groups.setdefault(h['generation'],[]).append(h['trials'])
    generations=sorted(groups);scores=[]
    for g in generations:
        scores.append(np.array([[t['score'] for t in scene] for scene in groups[g]]).mean(axis=0).max())
    ax.plot(generations,scores,c='#d6aba5',lw=1.3,label='Generation best')
    ax.plot(generations,np.maximum.accumulate(scores),c='#ac4437',lw=2.2,label='Best retained')
    ax.set_title(title,loc='left',pad=12);ax.set_xlabel('Generation');ax.set_ylabel('Training objective');ax.grid();ax.legend(frameon=False,fontsize=9)
fig.suptitle('Training history',x=.08,ha='left',fontsize=20,weight='bold');fig.tight_layout(rect=[0,.06,1,.94])
fig.text(.08,.03,'Search scores describe the training objective. Destination qualification measures reliability separately.',fontsize=9,c='#637077')
fig.savefig(OUT/'training-history.png',dpi=180);fig.savefig(OUT/'training-history.pdf');plt.close(fig)

routes=json.loads((OUT/'packaged-routes.json').read_text())
fig,axes=plt.subplots(2,2,figsize=(11,10))
for i,ax in enumerate(axes.flat):
    trace=np.array([r['position'] for r in routes['history'] if r['route']==i])
    ax.plot(trace[:,0],-trace[:,1],color=colors[i],lw=1.6);ax.scatter(0,0,c='#283237',s=25)
    ax.set_title(['Square','Alternating directions','Close corrections','Stop, resume and retarget'][i],loc='left',pad=12)
    ax.set_aspect('equal',adjustable='datalim');ax.grid();ax.set_xlabel('East / west (blocks)');ax.set_ylabel('North / south (blocks)')
fig.suptitle('Continuous routes with the packaged model',x=.08,ha='left',fontsize=19,weight='bold');fig.tight_layout(rect=[0,.03,1,.94])
fig.savefig(OUT/'routes.png',dpi=180);fig.savefig(OUT/'routes.pdf');plt.close(fig)
print('Saved measured destination, training and route figures.')
