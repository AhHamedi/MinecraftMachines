# Published verification and gameplay evidence

This directory contains a small selection of real development reports and
charts. The original local `artifacts/` directory also contains raw logs,
captures, failed experiments, disposable test worlds, and archived builds; it
is not needed to build or use the mod and is excluded from Git.

## Current verification

The [13 October 2026 native behavior report](leaper-behaviour-20261013.json)
was generated from the current source, with implementation fingerprint
`11f37441c3014b0fb7203a86556a4ce9a3ef591850a5e50d4e1f742790542be6`.
It records 229 passing common tests, 42 perception checks, and the native
behavior, recovery, pursuit, and committed-shockwave checks. This report's
scope is the recorded fixtures and assertions; it does not establish reliable
navigation across every possible Minecraft world.

The same checkout also has fresh [physical scaling checks](leaper-scale-20261013.json)
and [five sustained hunting regressions](leaper-hunting-20261013.json), generated
on 13 October 2026 with the same implementation fingerprint.

The dated September directories preserve the previous implementation
fingerprints and measured results. Their dates are part of their identity;
they are historical evidence, rather than new results for today's checkout.
Files marked `qualified: false` remain development observations and should
not be presented as qualified releases.

## Gameplay previews

- [Apex destination run and gap leap](../media/leaper-apex-gameplay.mp4):
  September 2026, actual Minecraft framebuffer captures. Two clips are
  concatenated with idle tails trimmed, at normal playback speed. This shows
  destination navigation rather than a player chase.
- [Vanilla charge and shockwave](../media/leaper-shockwave-vanilla.mp4):
  September 2026, actual Minecraft window capture, without a shader pack.
- [Player approach and knockback](../media/leaper-shockwave-gameplay.mp4):
  September 2026, actual Minecraft window capture. This recording predates the
  later attack-range and terrain-destruction changes.
- [README animation](../media/leaper-demo.gif): the eight-second vanilla
  shockwave recording, reduced to 800 × 500 pixels, 8 fps and 64 colors for
  GitHub. Timing is preserved; the full MP4 offers smoother motion.
- [Charge still](../media/leaper-poster.jpg): an extracted native frame at
  approximately 3.3 seconds from the vanilla recording.

Window chrome is cropped from the shockwave videos. The captures have no
added visual effects or synthesized frames. The recordings are silent even
though the mod implements in-game audio. These older previews demonstrate
the appearance of the project at their capture dates, not every feature in
the current source.

## Provenance

[publication-manifest.json](publication-manifest.json) maps each selected
public file to its original repository-relative artifact path, with original
and published SHA-256 checksums. Personal absolute filesystem paths are
replaced with repository-relative paths where needed; numeric results,
source fingerprints, dates, and qualification flags are retained. Original
manifests may name local release JARs and source archives that are deliberately
excluded from this selection. Build the current mod from source using the
repository README.

[asset-provenance.json](asset-provenance.json) records the procedural Leaper
particle/audio assets and their reproduction checks. See
[the asset-generation instructions](../../tools/README-assets.md) and
[third-party notices](../../THIRD_PARTY_NOTICES.md) for their origin and license
boundary.
