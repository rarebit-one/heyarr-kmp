#!/usr/bin/env python3
"""Generate and post-process the Archive illustration set (see README.md).

    scripts/art/art.py list                 # every asset id, its style and destination
    scripts/art/art.py generate [ID ...]    # Codex image generation → build/art-raw/<id>.png
    scripts/art/art.py snap [ID ...]        # raw PNG → checked-in WebP (grid snap + palette)
    scripts/art/art.py build [ID ...]       # generate, then snap

With no ids, `generate` only fills in raw images that are missing and `snap` processes every
raw image present. Needs `codex` (with image generation) and ImageMagick 7 (`magick`) on PATH.
Run from the repo root. Standard library only.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("assets.toml")
RAW = ROOT / "build" / "art-raw"

INSTRUCTION = (
    "Use your image generation tool to generate exactly one image, then stop. "
    "Do not write files, run commands or explain. Aspect ratio {aspect}.\n\n{preamble}\n\nSubject: {prompt}"
)


def load() -> tuple[dict, list[dict]]:
    data = tomllib.loads(MANIFEST.read_text())
    return data, data["asset"]


def select(assets: list[dict], ids: list[str]) -> list[dict]:
    if not ids:
        return assets
    by_id = {a["id"]: a for a in assets}
    unknown = [i for i in ids if i not in by_id]
    if unknown:
        sys.exit(f"unknown asset id(s): {', '.join(unknown)}")
    return [by_id[i] for i in ids]


def codex_images_dir() -> Path:
    home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
    return home / "generated_images"


def generate(data: dict, asset: dict) -> None:
    out = RAW / f"{asset['id']}.png"
    images = codex_images_dir()
    before = set(images.rglob("*.png")) if images.exists() else set()
    prompt = INSTRUCTION.format(
        aspect=asset.get("aspect", "16:9"),
        preamble=data["style"][asset["style"]]["preamble"].strip(),
        prompt=asset["prompt"],
    )
    started = time.monotonic()
    result = subprocess.run(
        [
            "codex", "exec", "--skip-git-repo-check", "--enable", "image_generation",
            "-s", "read-only", "-c", 'model_reasoning_effort="low"', prompt,
        ],
        cwd=RAW, capture_output=True, text=True, timeout=900,
    )
    made = sorted(set(images.rglob("*.png")) - before, key=lambda p: p.stat().st_mtime) if images.exists() else []
    if result.returncode != 0 or not made:
        sys.exit(f"{asset['id']}: codex produced no image (exit {result.returncode})\n{result.stdout[-2000:]}{result.stderr[-2000:]}")
    shutil.copyfile(made[-1], out)
    print(f"generated {asset['id']} in {time.monotonic() - started:.0f}s → {out.relative_to(ROOT)}")


def palette_png(data: dict) -> Path:
    path = RAW / "palette.png"
    swatches = [f"xc:{c}" for c in data["palette"]]
    subprocess.run(["magick", "-size", "1x1", *swatches, "+append", str(path)], check=True)
    return path


def snap(data: dict, asset: dict, palette: Path) -> None:
    raw = RAW / f"{asset['id']}.png"
    if not raw.exists():
        print(f"skip {asset['id']}: no raw image")
        return
    if asset.get("review"):
        print(f"review {asset['id']}: {raw.relative_to(ROOT)} (not written into the repo)")
        return
    dest = ROOT / asset["dest"]
    dest.parent.mkdir(parents=True, exist_ok=True)
    w, h = asset["out"]
    if asset["style"] == "pixel":
        # Generated "pixel art" is not on a true grid: average each cell down to one pixel,
        # remap to the palette with no dithering, then scale back up nearest-neighbour.
        gw, gh = asset["grid"]
        cmd = [
            "magick", str(raw), "-filter", "Box", "-resize", f"{gw}x{gh}^", "-gravity", "center",
            "-extent", f"{gw}x{gh}", "+dither", "-remap", str(palette), "-filter", "point",
            "-resize", f"{w}x{h}!", "-define", "webp:lossless=true", "-strip", str(dest),
        ]
    else:
        cmd = [
            "magick", str(raw), "-resize", f"{w}x{h}^", "-gravity", "center", "-extent", f"{w}x{h}",
            "-quality", "80", "-strip", str(dest),
        ]
    subprocess.run(cmd, check=True)
    print(f"snapped {asset['id']} → {asset['dest']} ({dest.stat().st_size // 1024} KB)")


def main(argv: list[str]) -> None:
    if not argv or argv[0] not in {"list", "generate", "snap", "build"}:
        sys.exit(__doc__)
    command, ids = argv[0], argv[1:]
    data, assets = load()
    chosen = select(assets, ids)
    RAW.mkdir(parents=True, exist_ok=True)
    if command == "list":
        for a in assets:
            print(f"{a['id']:32} {a['style']:10} {a.get('dest', '(review only)')}")
        return
    if command in {"generate", "build"}:
        for a in chosen:
            if ids or not (RAW / f"{a['id']}.png").exists():
                generate(data, a)
    if command in {"snap", "build"}:
        palette = palette_png(data)
        for a in chosen:
            snap(data, a, palette)


if __name__ == "__main__":
    main(sys.argv[1:])
