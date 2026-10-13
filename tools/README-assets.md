# Generate Leaper particles and sounds

Requires Python 3.11 or newer.

```sh
python3 -m venv .venv-assets
.venv-assets/bin/python -m pip install -r tools/requirements-assets.txt
.venv-assets/bin/python tools/generate_leaper_particles.py
.venv-assets/bin/python tools/generate_leaper_sounds.py
```

The scripts write the packaged assets under
`minecraft_machines/common/src/main/resources/assets/minecraft_machines/`.
Use `--output-root /tmp/leaper-assets` to write to a different directory.
