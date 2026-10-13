#!/usr/bin/env python3
"""Compare measured careful/fast Sable trajectories, without inferred speeds."""
from pathlib import Path
import json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'artifacts/leaper-brisk'
CARE=ROOT/'artifacts/leaper-careful-v1'
read=lambda p:json.loads(p.read_text())
care=read(CARE/'gait-qualification.json')['trials']
fast=read(OUT/'gait-qualification.json')['trials']
indices=[0,1,2,3,6,7]
a=np.array([care[i]['speed'] for i in indices]);b=np.array([fast[i]['speed'] for i in indices])
plt.rcParams.update({'font.family':'DejaVu Sans','font.size':11,'axes.spines.top':False,'axes.spines.right':False,'figure.facecolor':'#faf9f5','axes.facecolor':'white','axes.edgecolor':'#bcc4c6'})
fig,ax=plt.subplots(figsize=(12,7));fig.subplots_adjust(top=.75,bottom=.16,left=.08,right=.96)
x=np.arange(6);w=.33
ax.bar(x-w/2,a,w,color='#758d9a',label='Careful · saved original')
ax.bar(x+w/2,b,w,color='#c95737',label='Fast · separately trained')
for i,v in enumerate(b):ax.text(i+w/2,v+.045,f'{v:.2f}',ha='center',fontsize=11,weight='bold')
ax.set_xticks(x,['Forward','Reverse','Right','Left','Diagonal right','Diagonal left']);ax.set_ylabel('Measured speed (blocks / second)');ax.set_ylim(0,max(b)*1.2);ax.legend(frameon=False,loc='upper left');ax.grid(axis='y',alpha=.15);ax.set_axisbelow(True)
fig.text(.08,.93,'A faster Leaper, with the careful gait preserved',fontsize=22,weight='bold')
fig.text(.08,.85,f'{min(b/a):.1f}–{max(b/a):.1f}× the original sustained translation speed',fontsize=14)
fig.text(.08,.05,'Actual Minecraft / Sable rigid-body motion · normal physical timestep · four flat floor materials',fontsize=10,color='#617077')
fig.savefig(OUT/'pace-comparison.png',dpi=180);fig.savefig(OUT/'pace-comparison.pdf');plt.close(fig)

if (OUT/'packaged-qualification.json').exists() and (OUT/'fresh-qualification.json').exists():
    careful=read(CARE/'packaged-qualification.json')['trials']+read(CARE/'fresh-qualification.json')['trials']
    faster=read(OUT/'packaged-qualification.json')['trials']+read(OUT/'fresh-qualification.json')['trials']
    fig,axes=plt.subplots(1,2,figsize=(12,6));fig.subplots_adjust(top=.77,bottom=.16,left=.08,right=.96,wspace=.25)
    for label,rows,col in [('Careful',careful,'#758d9a'),('Fast',faster,'#c95737')]:
        axes[0].bar(label,np.mean([r['arrival_seconds'] for r in rows]),color=col,width=.5)
        axes[1].scatter(np.arange(96)+1,[r['final_error'] for r in rows],label=label,color=col,s=17,alpha=.75)
    axes[0].set_ylabel('Mean time to settled arrival (seconds)');axes[0].set_title('Same 96 destinations and initial headings',loc='left',fontsize=11)
    axes[1].axhline(.48,ls='--',color='#b56750',lw=1);axes[1].set_ylim(0,.55);axes[1].set_ylabel('Final stopping error (blocks)');axes[1].set_xlabel('Trial');axes[1].legend(frameon=False)
    fig.text(.08,.92,'Faster arrivals, checked independently',fontsize=22,weight='bold');fig.text(.08,.84,'Each accepted arrival includes settling and an additional five-second hold.',fontsize=12)
    fig.savefig(OUT/'destination-comparison.png',dpi=180);fig.savefig(OUT/'destination-comparison.pdf');plt.close(fig)
print('Saved measured pace comparisons')
