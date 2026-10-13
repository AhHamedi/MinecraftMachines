# Contributing

Use Java 21 and Python 3. Clone with `--recursive` to include the physics
submodule. Build and run instructions are in [README.md](README.md).

Run the core checks before opening a pull request:

```sh
./gradlew :minecraft_machines:common:test :minecraft_machines:neoforge:jar
PYTHONPATH=training/python/src python3 -m unittest discover -s training/python/tests
python3 tools/verify_leaper_eyes.py
python3 tools/verify_leaper_scales.py
```

For movement, perception or combat changes, run the native checks sequentially:

```sh
python3 tools/verify_leaper_behaviour.py
python3 tools/verify_leaper_hunting.py --full
python3 tools/verify_leaper_scale.py
```

Use a separate test world. These checks rebuild disposable GameTest worlds and
write reports under `artifacts/`. Inspect gameplay in Minecraft as well as the
automated results.

Development runs use a 4 GiB maximum heap. Pass `-PminecraftRunHeapMax=8G` to
the launcher or Gradle if your test needs more memory.

Keep changes focused and describe how you tested them. Preserve training schema
compatibility and third-party attribution. Update the pinned submodule instead
of editing its source directly.

Original contributions use the repository's MIT license. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency licenses and
[SECURITY.md](SECURITY.md) for private vulnerability reports.
