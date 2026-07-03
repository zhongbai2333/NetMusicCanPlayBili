#!/usr/bin/env python3
"""Export NetMusic Pad map cache snapshots for debugging.

Reads snapshot.bin written by PadMapClientCache and exports:
- raw tile classification image
- styled image approximating PadMapLayerTexture building masks

Usage:
  python tools/pad_cache_preview.py run/netmusic-pad-map-cache --latest
  python tools/pad_cache_preview.py path/to/snapshot.bin
    python tools/pad_cache_preview.py run/netmusic-pad-map-cache --latest --crop 140,80,96,64 --masks
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
from collections import deque
from pathlib import Path

SNAPSHOT_MAGIC = 0x4E504D53
CURRENT_SNAPSHOT_VERSION = 9
MIN_SUPPORTED_SNAPSHOT_VERSION = 1

KINDS = [
    ("UNKNOWN", 0xFFE6E0D5),
    ("GRASS", 0xFFE6E0D5),
    ("INDOOR_FLOOR", 0xFFE9E1D3),
    ("BUILDING", 0xFFDAD5CF),
    ("WATER", 0xFF9DBDCE),
    ("TREE", 0xFFBCD3B2),
    ("FARMLAND", 0xFFE0D5B7),
    ("ROCK", 0xFFD0CDC6),
    ("SNOW", 0xFFE8E5DE),
]

STYLE_COLORS = {
    "base": 0xFFE6E0D5,
    "farmland": 0xFFE0D5B7,
    "green": 0xFFBCD3B2,
    "water": 0xFF9DBDCE,
    "indoor_floor": 0xFFE8DED0,
    "building_zone": 0xFFD1CBC3,
    "building_core": 0xFFBEB7AE,
    "water_line": 0xFF86AFC4,
}


def read_i32(f):
    data = f.read(4)
    if len(data) != 4:
        raise EOFError("unexpected eof while reading int")
    return struct.unpack(">i", data)[0]


def read_snapshot(path: Path):
    with path.open("rb") as f:
        magic = read_i32(f)
        version = read_i32(f)
        if magic != SNAPSHOT_MAGIC:
            raise ValueError(f"not a Pad snapshot cache: magic=0x{magic:08X}")
        if version < MIN_SUPPORTED_SNAPSHOT_VERSION or version > CURRENT_SNAPSHOT_VERSION:
            raise ValueError(
                f"unsupported snapshot version {version}, expected <= {CURRENT_SNAPSHOT_VERSION}"
            )
        center_x = read_i32(f)
        center_z = read_i32(f)
        cell_size = read_i32(f)
        width = read_i32(f)
        height = read_i32(f)
        length = read_i32(f)
        if length != width * height:
            raise ValueError(f"invalid tile length {length}, expected {width * height}")
        tiles = list(f.read(length))
        if len(tiles) != length:
            raise EOFError("unexpected eof while reading tiles")
        if version < 9:
            tiles = [upgrade_pre_v9_tile(tile) for tile in tiles]
    return {
        "path": path,
        "version": version,
        "center_x": center_x,
        "center_z": center_z,
        "cell_size": cell_size,
        "width": width,
        "height": height,
        "tiles": tiles,
    }


    def upgrade_pre_v9_tile(tile: int) -> int:
        # v9 inserted INDOOR_FLOOR before BUILDING. Older snapshots did not contain indoor-floor tiles.
        return tile + 1 if tile >= 2 else tile


def argb_to_rgb(color: int):
    return ((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF)


def idx(width: int, x: int, z: int) -> int:
    return z * width + x


def mask_for(tiles, width, kind_name):
    ordinal = next(i for i, (name, _) in enumerate(KINDS) if name == kind_name)
    return [tile == ordinal for tile in tiles]


def count_radius(mask, width, height, x, z, radius):
    count = 0
    for dz in range(-radius, radius + 1):
        nz = z + dz
        if nz < 0 or nz >= height:
            continue
        for dx in range(-radius, radius + 1):
            nx = x + dx
            if 0 <= nx < width and mask[idx(width, nx, nz)]:
                count += 1
    return count


def large_components(source, width, height, min_area):
    output = [False] * len(source)
    visited = [False] * len(source)
    for start, value in enumerate(source):
        if not value or visited[start]:
            continue
        q = deque([start])
        visited[start] = True
        component = []
        while q:
            cur = q.popleft()
            component.append(cur)
            x = cur % width
            z = cur // width
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < width and 0 <= nz < height:
                    ni = idx(width, nx, nz)
                    if source[ni] and not visited[ni]:
                        visited[ni] = True
                        q.append(ni)
        if len(component) >= min_area:
            for i in component:
                output[i] = True
    return output


def soften_area(source, width, height, radius, threshold):
    output = source[:]
    for z in range(height):
        for x in range(width):
            i = idx(width, x, z)
            if source[i]:
                continue
            if count_radius(source, width, height, x, z, radius) >= threshold:
                output[i] = True
    return output


def outline_with_source(source, width, height):
    output = source[:]
    for z in range(height):
        for x in range(width):
            i = idx(width, x, z)
            if source[i]:
                continue
            if count_radius(source, width, height, x, z, 1) > 0:
                output[i] = True
    return output


def fill_small_interior_gaps(mask, width, height):
    output = mask[:]
    for z in range(1, height - 1):
        for x in range(1, width - 1):
            i = idx(width, x, z)
            if output[i]:
                continue
            cardinal = 0
            cardinal += 1 if output[idx(width, x - 1, z)] else 0
            cardinal += 1 if output[idx(width, x + 1, z)] else 0
            cardinal += 1 if output[idx(width, x, z - 1)] else 0
            cardinal += 1 if output[idx(width, x, z + 1)] else 0
            if cardinal >= 3 or (cardinal >= 2 and count_radius(output, width, height, x, z, 1) >= 5):
                output[i] = True
    return output


def fill_single_cell_holes(mask, width, height):
    output = mask[:]
    for z in range(1, height - 1):
        for x in range(1, width - 1):
            i = idx(width, x, z)
            if output[i]:
                continue
            if (output[idx(width, x - 1, z)] and output[idx(width, x + 1, z)]
                    and output[idx(width, x, z - 1)] and output[idx(width, x, z + 1)]):
                output[i] = True
    return output


def fill_enclosed_holes(mask, width, height, max_area):
    output = mask[:]
    visited = [False] * len(mask)

    def flood_open(start_x, start_z):
        start = idx(width, start_x, start_z)
        if output[start] or visited[start]:
            return
        q = deque([start])
        visited[start] = True
        while q:
            cur = q.popleft()
            x = cur % width
            z = cur // width
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < width and 0 <= nz < height:
                    ni = idx(width, nx, nz)
                    if not output[ni] and not visited[ni]:
                        visited[ni] = True
                        q.append(ni)

    for z in range(height):
        flood_open(0, z)
        flood_open(width - 1, z)
    for x in range(width):
        flood_open(x, 0)
        flood_open(x, height - 1)

    for start in range(len(output)):
        if output[start] or visited[start]:
            continue
        q = deque([start])
        component = []
        touches_edge = False
        visited[start] = True
        while q:
            cur = q.popleft()
            component.append(cur)
            x = cur % width
            z = cur // width
            if x == 0 or x == width - 1 or z == 0 or z == height - 1:
                touches_edge = True
            for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < width and 0 <= nz < height:
                    ni = idx(width, nx, nz)
                    if not output[ni] and not visited[ni]:
                        visited[ni] = True
                        q.append(ni)
        if not touches_edge and len(component) <= max_area and has_building_ring(output, width, height, component):
            for i in component:
                output[i] = True
    return output


def has_building_ring(mask, width, height, component):
    required = max(4, min(48, len(component) // 2))
    adjacent = 0
    for i in component:
        x = i % width
        z = i // width
        if ((x > 0 and mask[idx(width, x - 1, z)])
                or (x + 1 < width and mask[idx(width, x + 1, z)])
                or (z > 0 and mask[idx(width, x, z - 1)])
                or (z + 1 < height and mask[idx(width, x, z + 1)])):
            adjacent += 1
            if adjacent >= required:
                return True
    return adjacent >= required


def building_core(source, width, height, threshold):
    output = [False] * len(source)
    for z in range(height):
        for x in range(width):
            i = idx(width, x, z)
            if source[i] and count_radius(source, width, height, x, z, 1) >= threshold:
                output[i] = True
    return output


def subtract(source, remove):
    return [a and not b for a, b in zip(source, remove)]


def style_snapshot(snapshot):
    width = snapshot["width"]
    height = snapshot["height"]
    tiles = snapshot["tiles"]
    tree = mask_for(tiles, width, "TREE")
    farmland = mask_for(tiles, width, "FARMLAND")
    indoor_floor = mask_for(tiles, width, "INDOOR_FLOOR")
    building = mask_for(tiles, width, "BUILDING")
    water = mask_for(tiles, width, "WATER")
    indoor_map = sum(1 for value in indoor_floor if value) >= 8

    tree_component = large_components(tree, width, height, 10)
    green_area = soften_area(tree_component, width, height, 2, 9)
    farmland_component = large_components(farmland, width, height, 8)
    farmland_area = soften_area(farmland_component, width, height, 1, 4)
    water_component = large_components(water, width, height, 18)
    water_area = soften_area(water_component, width, height, 1, 5)
    water_line = subtract(water, water_area)
    building_footprint = large_components(building, width, height, 2 if indoor_map else 4)
    if indoor_map:
        building_footprint = fill_small_interior_gaps(building_footprint, width, height)
        building_zone = outline_with_source(building_footprint, width, height)
        core = building_core(building_footprint, width, height, 4)
    else:
        building_footprint = fill_single_cell_holes(building_footprint, width, height)
        building_footprint = fill_enclosed_holes(building_footprint, width, height, max(24, width * height // 18))
        building_zone = building_footprint[:]
        core = building_core(building_footprint, width, height, 6)
    return {
        "farmland_area": farmland_area,
        "green_area": green_area,
        "water_area": water_area,
        "water_line": water_line,
        "indoor_floor": indoor_floor,
        "building_footprint": building_footprint,
        "building_zone": building_zone,
        "building_core": core,
    }


def render_raw(snapshot):
    pixels = []
    for tile in snapshot["tiles"]:
        color = KINDS[tile][1] if 0 <= tile < len(KINDS) else KINDS[0][1]
        pixels.append(argb_to_rgb(color))
    return pixels


def render_styled(snapshot):
    width = snapshot["width"]
    height = snapshot["height"]
    pixels = [argb_to_rgb(STYLE_COLORS["base"])] * (width * height)
    styled = style_snapshot(snapshot)
    layers = [
        (styled["farmland_area"], STYLE_COLORS["farmland"]),
        (styled["green_area"], STYLE_COLORS["green"]),
        (styled["water_area"], STYLE_COLORS["water"]),
        (styled["indoor_floor"], STYLE_COLORS["indoor_floor"]),
        (styled["building_zone"], STYLE_COLORS["building_zone"]),
        (styled["building_core"], STYLE_COLORS["building_core"]),
        (styled["water_line"], STYLE_COLORS["water_line"]),
    ]
    for mask, color in layers:
        rgb = argb_to_rgb(color)
        for i, enabled in enumerate(mask):
            if enabled:
                pixels[i] = rgb
    return pixels


def render_mask(mask):
    return [(190, 183, 174) if enabled else (230, 224, 213) for enabled in mask]


def crop_pixels(pixels, width, height, crop):
    if crop is None:
        return width, height, pixels
    x, z, w, h = crop
    x = max(0, min(width, x))
    z = max(0, min(height, z))
    w = max(1, min(width - x, w))
    h = max(1, min(height - z, h))
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
        out = path.with_suffix(".ppm")
        with out.open("wb") as f:
            f.write(f"P6\n{width} {height}\n255\n".encode("ascii"))
            for r, g, b in pixels:
                f.write(bytes((r, g, b)))
        return out


def find_latest_snapshot(root: Path):
    candidates = list(root.rglob("snapshot.bin")) if root.is_dir() else []
    if not candidates:
        raise FileNotFoundError(f"no snapshot.bin found under {root}")
    return max(candidates, key=lambda p: p.stat().st_mtime)


def summarize(snapshot, styled=None, crop=None):
    counts = {name: 0 for name, _ in KINDS}
    for tile in snapshot["tiles"]:
        name = KINDS[tile][0] if 0 <= tile < len(KINDS) else "UNKNOWN"
        counts[name] += 1
    total = len(snapshot["tiles"])
    print(f"snapshot: {snapshot['path']}")
    print(
        f"version={snapshot['version']}, center=({snapshot['center_x']}, {snapshot['center_z']}), "
        f"cell={snapshot['cell_size']}, size={snapshot['width']}x{snapshot['height']}"
    )
    for name, count in counts.items():
        print(f"{name:8s} {count:7d} {count / total * 100:6.2f}%")
    if crop is not None:
        summarize_region(snapshot, styled or style_snapshot(snapshot), crop)


def summarize_region(snapshot, styled, crop):
    width = snapshot["width"]
    height = snapshot["height"]
    x, z, w, h = crop
    x = max(0, min(width, x))
    z = max(0, min(height, z))
    w = max(1, min(width - x, w))
    h = max(1, min(height - z, h))
    total = w * h
    counts = {name: 0 for name, _ in KINDS}
    zone = 0
    footprint = 0
    core = 0
    for row in range(z, z + h):
        for col in range(x, x + w):
            i = idx(width, col, row)
            tile = snapshot["tiles"][i]
            name = KINDS[tile][0] if 0 <= tile < len(KINDS) else "UNKNOWN"
            counts[name] += 1
            zone += 1 if styled["building_zone"][i] else 0
            footprint += 1 if styled["building_footprint"][i] else 0
            core += 1 if styled["building_core"][i] else 0
    print(f"region x={x}, z={z}, w={w}, h={h}")
    for name, count in counts.items():
        print(f"region.{name:8s} {count:6d} {count / total * 100:6.2f}%")
    print(f"region.building_footprint {footprint:6d} {footprint / total * 100:6.2f}%")
    print(f"region.building_zone      {zone:6d} {zone / total * 100:6.2f}%")
    print(f"region.building_core      {core:6d} {core / total * 100:6.2f}%")


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path, help="snapshot.bin or cache root")
    parser.add_argument("--latest", action="store_true", help="find newest snapshot.bin under path")
    parser.add_argument("--out", type=Path, default=Path("build/pad-cache-preview"), help="output directory")
    parser.add_argument("--crop", type=parse_crop, help="crop output/statistics to x,z,w,h in snapshot pixels")
    parser.add_argument("--masks", action="store_true", help="also export building footprint/zone/core mask images")
    args = parser.parse_args(argv)

    snapshot_path = find_latest_snapshot(args.path) if args.latest or args.path.is_dir() else args.path
    snapshot = read_snapshot(snapshot_path)
    styled = style_snapshot(snapshot)
    summarize(snapshot, styled, args.crop)

    args.out.mkdir(parents=True, exist_ok=True)
    stem = snapshot_path.parent.name + "-" + snapshot_path.stem
    suffix = "" if args.crop is None else f"-crop-{args.crop[0]}-{args.crop[1]}-{args.crop[2]}-{args.crop[3]}"
    raw_w, raw_h, raw_pixels = crop_pixels(render_raw(snapshot), snapshot["width"], snapshot["height"], args.crop)
    styled_w, styled_h, styled_pixels = crop_pixels(render_styled(snapshot), snapshot["width"], snapshot["height"], args.crop)
    raw_path = save_image(args.out / f"{stem}-raw{suffix}", raw_w, raw_h, raw_pixels)
    styled_path = save_image(args.out / f"{stem}-styled{suffix}", styled_w, styled_h, styled_pixels)
    print(f"raw:    {raw_path}")
    print(f"styled: {styled_path}")
    if args.masks:
        for name in ("building_footprint", "building_zone", "building_core"):
            mask_w, mask_h, mask_pixels = crop_pixels(
                render_mask(styled[name]), snapshot["width"], snapshot["height"], args.crop
            )
            mask_path = save_image(args.out / f"{stem}-{name}{suffix}", mask_w, mask_h, mask_pixels)
            print(f"{name}: {mask_path}")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
