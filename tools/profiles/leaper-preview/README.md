# Current Leaper development profile

These two JSON files preserve the gait candidate and high-step settings used by
the current Hunt, Apex, and Shaders development launchers. They were previously
stored only in ignored client and GameTest directories. They are development
inputs, not a new qualified binary release; the packaged v10 policy remains in
`minecraft_machines/common/src/main/resources/data/minecraft_machines/leaper/predator.json`.

Run `python3 tools/prepare_leaper_preview.py --target client` from the repository
root to install missing client inputs, or use `bash tools/run-client.sh --preview`.
The launcher and native verification scripts prepare their inputs automatically.
Existing local files are preserved so that launching does not overwrite a
training experiment. For an exact replay of this versioned profile, first move
any existing candidate and high-step files out of the selected run directory,
then prepare the profile again.

See [the controls guide](../../../docs/leaper-controls.md) for gameplay and
[the verification guide](../../../docs/leaper-behaviour.md) for native tests.
