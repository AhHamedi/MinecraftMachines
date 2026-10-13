#!/usr/bin/env python3
"""Reproduce the Leaper's original procedural glow and sixteen-cloud mist atlas.

Install tools/requirements-assets.txt first. No reference-game artwork is used.
--output-root selects an alternate asset directory for verification.
"""
import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image


def noise(x, y, z, seed):
    ix = np.floor(x).astype("int32")
    iy = np.floor(y).astype("int32")
    iz = np.floor(z).astype("int32")
    fx, fy, fz = x - ix, y - iy, z - iz
    fx, fy, fz = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy), fz * fz * (3 - 2 * fz)
    out = np.zeros_like(x)
    for a in range(2):
        for b in range(2):
            for c in range(2):
                v = np.sin((ix + a) * 127.1 + (iy + b) * 311.7 + (iz + c) * 74.7 + seed * 19.37) * 43758.5453
                v = v - np.floor(v)
                out += v * (fx if a else 1 - fx) * (fy if b else 1 - fy) * (fz if c else 1 - fz)
    return out


def generate(output: Path) -> None:
    particles = output / "textures/particle"
    particles.mkdir(parents=True, exist_ok=True)
    glow = Image.new("RGBA", (128, 128))
    pixels = glow.load()
    for y in range(128):
        for x in range(128):
            radius = ((x - 63.5) / 63.5) ** 2 + ((y - 63.5) / 63.5) ** 2
            alpha = max(0, (math.exp(-radius * 5) - math.exp(-5)) / (1 - math.exp(-5)))
            pixels[x, y] = (255, 255, 255, round(255 * alpha))
    glow.save(particles / "leaper_glow.png")

    n, depth = 192, 40
    atlas = Image.new("RGBA", (n * 4, n * 4))
    y, x, z = np.meshgrid(
        np.linspace(-1, 1, n, dtype="float32"), np.linspace(-1, 1, n, dtype="float32"),
        np.linspace(-1, 1, depth, dtype="float32"), indexing="ij",
    )
    for i in range(16):
        broad = noise(x * 2.3 + 7, y * 2.3 + 5, z * 2.3 + 3, i + 14)
        medium = noise(x * 5.7 + 13, y * 5.7 + 9, z * 5.7 + 1, i + 31)
        fine = noise(x * 13.4, y * 13.4, z * 13.4, i + 67)
        radius = np.sqrt((x * (1 + .12 * np.sin(y * 4 + i))) ** 2 +
                         (y * (1 + .13 * np.sin(z * 5 + i))) ** 2 + z * z)
        density = np.clip((.88 - radius) * 1.5 + (broad - .5) * 1.0 +
                          (medium - .5) * .42 + (fine - .5) * .14, 0, None)
        density *= np.clip((1 - radius) / .18, 0, 1)
        density = density ** 1.2 * 1.9
        extinction = 1 - np.exp(-density * 3.5 / depth)
        transmission = np.exp(-np.cumsum(density * 3.5 / depth, axis=2))
        weights = extinction * transmission
        shade = np.exp(-np.cumsum(density, axis=0) * 2 / n)
        illumination = .48 + .50 * shade
        rgb = (weights * illumination).sum(axis=2) / (weights.sum(axis=2) + 1e-8)
        alpha = 1 - np.exp(-density.sum(axis=2) * 3.5 / depth)
        edge = np.clip((1 - np.sqrt(x[:, :, 0] ** 2 + y[:, :, 0] ** 2)) / .08, 0, 1)
        alpha *= edge
        sprite = np.zeros((n, n, 4), dtype="uint8")
        sprite[:, :, :3] = np.clip(rgb[:, :, None] * 255, 0, 255)
        sprite[:, :, 3] = np.clip(alpha * 255, 0, 255)
        atlas.paste(Image.fromarray(sprite), (i % 4 * n, i // 4 * n))
    atlas.save(particles / "leaper_mist.png")
    for name in ("leaper_glow", "leaper_mist"):
        (particles / f"{name}.png.mcmeta").write_text('{"texture":{"blur":true,"clamp":true}}\n')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=Path(__file__).resolve().parents[1] /
                        "minecraft_machines/common/src/main/resources/assets/minecraft_machines")
    generate(parser.parse_args().output_root)
