#!/usr/bin/env python3
"""Reproduce the original procedural Leaper charge and blast sounds.

Install tools/requirements-assets.txt first. No sampled or reference-game audio
is used. --output-root selects an alternate asset directory for verification.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import wave

import imageio_ffmpeg
import numpy as np


def generate(output: Path) -> None:
    sounds = output / "sounds/leaper"
    sounds.mkdir(parents=True, exist_ok=True)
    sample_rate = 44100
    rng = np.random.default_rng(91426)
    with tempfile.TemporaryDirectory(prefix="leaper-audio-") as temporary:
        for name, duration in [("charge", 3.0), ("blast", 2.4)]:
            t = np.arange(int(sample_rate * duration)) / sample_rate
            noise = rng.normal(0, 1, len(t))
            smooth = np.convolve(noise, np.ones(18) / 18, "same")
            if name == "charge":
                q = t / 3
                phase = 2 * np.pi * (68 * t + 34 * t * t + 18 * t * t * t)
                envelope = np.minimum(1, t / .16) * np.minimum(1, (3 - t) / .045) * (.14 + .6 * q * q)
                tone = .32 * np.sin(phase) + .17 * np.sin(phase * 2.007) + .11 * np.sin(phase * 4.013)
                wobble = .6 + .4 * np.sin(2 * np.pi * (2 * t + 1.3 * t * t)) ** 2
                signal = envelope * (tone * wobble + smooth * (.08 + .38 * q))
                signal += .12 * np.sin(2 * np.pi * (900 * t + 350 * t * t)) * q ** 4 * np.minimum(1, (3 - t) / .035)
            else:
                sub = np.sin(2 * np.pi * (39 * t + 26 * (1 - np.exp(-12 * t)))) * np.exp(-3.6 * t)
                crack = noise * np.exp(-40 * t)
                air = smooth * np.exp(-2.5 * t)
                metallic = np.sin(2 * np.pi * 213 * t + 8 * np.sin(2 * np.pi * 36 * t)) * np.exp(-8 * t)
                signal = .65 * sub + .28 * crack + .5 * air + .17 * metallic
                signal *= np.minimum(1, t / .002) * np.minimum(1, (duration - t) / .08)
            signal = np.tanh(signal * 1.3) * .88
            wav_path = Path(temporary) / f"{name}.wav"
            with wave.open(str(wav_path), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(sample_rate)
                wav.writeframes((signal * 32767).astype("<i2").tobytes())
            subprocess.run([
                imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error", "-i", str(wav_path),
                "-c:a", "libvorbis", "-q:a", "5", str(sounds / f"{name}.ogg"),
            ], check=True)
    catalog = output / "sounds.json"
    data = json.loads(catalog.read_text()) if catalog.exists() else {}
    for name in ("charge", "blast"):
        data[f"leaper_{name}"] = {
            "subtitle": f"subtitles.minecraft_machines.leaper_{name}",
            "sounds": [{"name": f"minecraft_machines:leaper/{name}", "attenuation_distance": 48}],
        }
    catalog.write_text(json.dumps(data, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=Path(__file__).resolve().parents[1] /
                        "minecraft_machines/common/src/main/resources/assets/minecraft_machines")
    generate(parser.parse_args().output_root)
