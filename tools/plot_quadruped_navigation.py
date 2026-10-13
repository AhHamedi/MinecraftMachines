#!/usr/bin/env python3
"""Plot measured Minecraft/Sable trajectories and arrival errors from qualification JSON."""
from pathlib import Path
import json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'artifacts/quadruped-navigation'
report = json.loads((OUT / 'packaged-qualification.json').read_text())
trials = report['trials']
routes = json.loads((OUT / 'packaged-routes.json').read_text())['routes']
plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'axes.spines.top': False,
                     'axes.spines.right': False, 'axes.titleweight': 'bold', 'axes.labelcolor': '#46546b',
                     'text.color': '#17283f', 'axes.edgecolor': '#bbc5d1', 'xtick.color': '#46546b',
                     'ytick.color': '#46546b', 'grid.color': '#e4e9f0', 'figure.facecolor': '#f8fafc',
                     'axes.facecolor': '#ffffff'})
colors = ['#1d6f9c', '#36a47c', '#b87925', '#8764ae', '#ca6577', '#467f63', '#5574c4', '#a8814c']
errors = np.array([t['final_error_blocks'] for t in trials])
fig, axes = plt.subplots(1, 2, figsize=(14, 7), gridspec_kw={'width_ratios': [1, 1.06]})
fig.subplots_adjust(top=.75, bottom=.13, left=.075, right=.96, wspace=.28)
fig.text(.075, .925, 'A destination in every direction', fontsize=24, weight='bold')
fig.text(.075, .858, f"{sum(t['success'] for t in trials)}/{len(trials)} arrivals passed    ·    {errors.mean():.3f} blocks average error    ·    {errors.max():.3f} blocks worst error", fontsize=13)
ax = axes[0]
for i, t in enumerate(trials[:8]):
    trace = np.array(t['trace']); goal = np.array(t['goal_offset'])
    ax.plot(trace[:, 1], -trace[:, 2], color=colors[i], lw=1.8)
    ax.scatter(goal[0], -goal[1], marker='x', color=colors[i], s=70, linewidths=2, zorder=4)
ax.scatter(0, 0, color='#17283f', s=35, zorder=5)
ax.annotate('start', (0, 0), (5, 6), textcoords='offset points', fontsize=9)
ax.set_aspect('equal'); ax.grid(alpha=.8); ax.set_xlabel('East / west displacement (blocks)'); ax.set_ylabel('North / south displacement (blocks)')
ax.set_title('Eight compass targets · actual paths', loc='left', pad=14)
ax.set_xlim(-29, 29); ax.set_ylim(-29, 29)
ax = axes[1]
for surface, color in zip(['smooth_stone', 'grass_block', 'oak_planks', 'cobblestone'], colors[:4]):
    ids = [i for i, t in enumerate(trials) if t['surface'].endswith(surface)]
    ax.scatter(np.array(ids) + 1, errors[ids], s=28, color=color, label=surface.replace('_', ' '), zorder=3)
ax.axhline(.75, color='#b87925', ls='--', lw=1.4)
ax.text(2, .77, 'Qualification limit: 0.75 blocks', color='#986523', fontsize=9)
ax.set_ylim(0, .85); ax.set_xlim(-1, 82); ax.grid(axis='y'); ax.set_xlabel('Qualification trial'); ax.set_ylabel('Final position error (blocks)')
ax.set_title('All 80 arrivals · five-second hold', loc='left', pad=14)
ax.legend(loc='upper right', bbox_to_anchor=(1, .88), frameon=False, fontsize=9)
fig.text(.075, .035, 'Measured in Minecraft 1.21.1 / Sable 2.0.0. Flat ground only. Lines show physical chassis movement; × marks the requested point.', fontsize=9, color='#66748a')
fig.savefig(OUT / 'navigation-results.png', dpi=180)
fig.savefig(OUT / 'navigation-results.pdf')
plt.close(fig)

fig, axes = plt.subplots(2, 2, figsize=(12, 11))
fig.subplots_adjust(top=.89, bottom=.08, left=.08, right=.95, wspace=.27, hspace=.31)
fig.text(.08, .955, 'Continuous routes, with no resets between points', fontsize=20, weight='bold')
for ax, route, color in zip(axes.flat, routes, colors):
    trace = np.array(route['trace']); ax.plot(trace[:,1], -trace[:,2], color=color, lw=1.7)
    ax.scatter(0,0,color='#17283f',s=30,zorder=4)
    for event in route['arrivals']:
        gx,gz=event['goal_offset']; ax.scatter(gx,-gz,color=color,marker='x',s=65,linewidths=2)
        ax.annotate(str(event['waypoint']+1),(gx,-gz),(5,5),textcoords='offset points',fontsize=9)
    ax.set_title(f"{route['route'].replace('_',' ').capitalize()} · {route['arrivals'][-1]['arrival_seconds']:.1f} s",loc='left',pad=12)
    ax.set_aspect('equal',adjustable='datalim');ax.grid();ax.set_xlabel('East / west (blocks)');ax.set_ylabel('North / south (blocks)')
fig.text(.08,.025,'Labels mark reached waypoints. The retarget test changes destination twice while moving, stops for five seconds, then resumes.',fontsize=9,color='#66748a')
fig.savefig(OUT / 'routes.png',dpi=180)
fig.savefig(OUT / 'routes.pdf')
plt.close(fig)
print('Saved measured trajectory and arrival-error figures.')
