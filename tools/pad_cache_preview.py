#!/usr/bin/env python3
"""Export NetMusic Pad map cache snapshots for debugging."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from pad_cache_codec import read_snapshot
from pad_cache_style import KINDS, idx, render_mask, render_raw, render_styled, style_snapshot


def crop_pixels(pixels, width, height, crop):
    if crop is None:
        return width, height, pixels
    x, z, w, h = crop
    x, z = max(0, min(width - 1, x)), max(0, min(height - 1, z))
    w, h = max(1, min(width - x, w)), max(1, min(height - z, h))
    cropped = []
    for row in range(z, z + h):
        cropped.extend(pixels[idx(width, x, row):idx(width, x + w, row)])
    return w, h, cropped


def parse_crop(value):
    try:
        parts = [int(part.strip()) for part in value.split(",")]
    except ValueError as exc:
        raise argparse.ArgumentTypeError("crop must be x,z,w,h") from exc
    if len(parts) != 4 or parts[2] <= 0 or parts[3] <= 0:
        raise argparse.ArgumentTypeError("crop must be x,z,w,h with positive width/height")
    return tuple(parts)


def save_image(path: Path, width: int, height: int, pixels):
    try:
        from PIL import Image  # type: ignore
        image = Image.new("RGB", (width, height))
        image.putdata(pixels)
        image.save(path.with_suffix(".png"))
        return path.with_suffix(".png")
    except Exception:
        output = path.with_suffix(".ppm")
        with output.open("wb") as stream:
            stream.write(f"P6\n{width} {height}\n255\n".encode("ascii"))
            for red, green, blue in pixels:
                stream.write(bytes((red, green, blue)))
        return output


def find_latest_snapshot(root: Path):
    candidates = list(root.rglob("snapshot.bin")) if root.is_dir() else []
    if not candidates:
        raise FileNotFoundError(f"no snapshot.bin found under {root}")
    return max(candidates, key=lambda path: path.stat().st_mtime)


def summarize(snapshot, styled=None, crop=None):
    counts = {name: 0 for name, _ in KINDS}
    for tile in snapshot["tiles"]:
        name = KINDS[tile][0] if 0 <= tile < len(KINDS) else "UNKNOWN"
        counts[name] += 1
    total = len(snapshot["tiles"])
    center_y = "?" if snapshot["center_y"] is None else snapshot["center_y"]
    print(f"snapshot: {snapshot['path']}")
    print(f"version={snapshot['version']}, center=({snapshot['center_x']}, {center_y}, {snapshot['center_z']}), "
          f"cell={snapshot['cell_size']}, size={snapshot['width']}x{snapshot['height']}")
    for name, count in counts.items():
        print(f"{name:13s} {count:7d} {count / total * 100:6.2f}%")
    if crop is not None:
        summarize_region(snapshot, styled or style_snapshot(snapshot), crop)


def summarize_region(snapshot, styled, crop):
    width, height = snapshot["width"], snapshot["height"]
    x, z, w, h = crop
    x, z = max(0, min(width - 1, x)), max(0, min(height - 1, z))
    w, h = max(1, min(width - x, w)), max(1, min(height - z, h))
    total = w * h
    counts = {name: 0 for name, _ in KINDS}
    selected = {name: 0 for name in ("building_footprint", "building_zone", "building_core")}
    for row in range(z, z + h):
        for col in range(x, x + w):
            i = idx(width, col, row)
            tile = snapshot["tiles"][i]
            counts[KINDS[tile][0] if 0 <= tile < len(KINDS) else "UNKNOWN"] += 1
            for name in selected:
                selected[name] += int(styled[name][i])
    print(f"region x={x}, z={z}, w={w}, h={h}")
    for name, count in counts.items():
        print(f"region.{name:13s} {count:6d} {count / total * 100:6.2f}%")
    for name, count in selected.items():
        print(f"region.{name:20s} {count:6d} {count / total * 100:6.2f}%")


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path, help="snapshot.bin or cache root")
    parser.add_argument("--latest", action="store_true", help="find newest snapshot.bin under path")
    parser.add_argument("--out", type=Path, default=Path("build/pad-cache-preview"), help="output directory")
    parser.add_argument("--crop", type=parse_crop, help="crop output/statistics to x,z,w,h")
    parser.add_argument("--masks", action="store_true", help="also export building masks")
    args = parser.parse_args(argv)
    snapshot_path = find_latest_snapshot(args.path) if args.latest or args.path.is_dir() else args.path
    snapshot = read_snapshot(snapshot_path)
    styled = style_snapshot(snapshot)
    summarize(snapshot, styled, args.crop)

    args.out.mkdir(parents=True, exist_ok=True)
    stem = snapshot_path.parent.name + "-" + snapshot_path.stem
    suffix = "" if args.crop is None else f"-crop-{args.crop[0]}-{args.crop[1]}-{args.crop[2]}-{args.crop[3]}"
    for name, pixels in (("raw", render_raw(snapshot)), ("styled", render_styled(snapshot))):
        out_width, out_height, out_pixels = crop_pixels(pixels, snapshot["width"], snapshot["height"], args.crop)
        output = save_image(args.out / f"{stem}-{name}{suffix}", out_width, out_height, out_pixels)
        print(f"{name}: {output}")
    if args.masks:
        for name in ("building_footprint", "building_zone", "building_core"):
            out_width, out_height, out_pixels = crop_pixels(render_mask(styled[name]), snapshot["width"], snapshot["height"], args.crop)
            output = save_image(args.out / f"{stem}-{name}{suffix}", out_width, out_height, out_pixels)
            print(f"{name}: {output}")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
