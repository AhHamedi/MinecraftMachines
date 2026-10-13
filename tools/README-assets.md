# Reproduce the Leaper particle and sound assets

The glow sprite, sixteen-cloud mist atlas, and two sounds are original
procedural assets. They use no sampled audio or reference-game artwork.

With Python 3.11 or newer, create a Python environment from the repository root
and install the pinned generation dependencies:

```bash
python3 -m venv .venv-assets
.venv-assets/bin/python -m pip install -r tools/requirements-assets.txt
.venv-assets/bin/python tools/generate_leaper_particles.py
.venv-assets/bin/python tools/generate_leaper_sounds.py
```

The scripts locate the repository through their own file paths and write the
packaged assets under
`minecraft_machines/common/src/main/resources/assets/minecraft_machines/`.
To inspect generated output without replacing packaged files, add
`--output-root /tmp/leaper-assets` to either command.

The algorithms and random seeds are preserved from the original September
2026 generators. Reproduction on 13 October 2026 produced byte-identical PNGs,
texture metadata, and sound definitions, and identical decoded 44.1 kHz mono
audio. OGG container serial numbers can change between encodings, so an OGG
file checksum alone is not a test of audio equivalence. Other library or
encoder versions may also change encoded output.

The mist atlas generation uses NumPy to integrate sixteen 192 × 192 × 40
density volumes. The audio generator uses seeded noise and oscillators and
encodes with the FFmpeg binary supplied by `imageio-ffmpeg`. Temporary WAV
files are removed automatically. These generators do not edit Java source or
player saves.
