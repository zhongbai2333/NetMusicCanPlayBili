"""Binary codec for Pad map snapshot cache files."""
from __future__ import annotations

import struct
from pathlib import Path

SNAPSHOT_MAGIC = 0x4E504D53
LEGACY_VERSIONS = frozenset(range(1, 10))
CENTER_Y_VERSIONS = frozenset({12, 18})
SUPPORTED_VERSIONS = LEGACY_VERSIONS | CENTER_Y_VERSIONS


def _read_i32(stream) -> int:
    data = stream.read(4)
    if len(data) != 4:
        raise EOFError("unexpected eof while reading int")
    return struct.unpack(">i", data)[0]


def _upgrade_pre_v9_tile(tile: int) -> int:
    """Insert INDOOR_FLOOR at ordinal 2 for v1-v8 snapshots."""
    return tile + 1 if tile >= 2 else tile


def read_snapshot(path: Path) -> dict:
    with path.open("rb") as stream:
        magic = _read_i32(stream)
        version = _read_i32(stream)
        if magic != SNAPSHOT_MAGIC:
            raise ValueError(f"not a Pad snapshot cache: magic=0x{magic:08X}")
        if version not in SUPPORTED_VERSIONS:
            supported = ", ".join(str(value) for value in sorted(SUPPORTED_VERSIONS))
            raise ValueError(f"unsupported snapshot version {version}; supported versions: {supported}")

        center_x = _read_i32(stream)
        center_y = _read_i32(stream) if version in CENTER_Y_VERSIONS else None
        center_z = _read_i32(stream)
        cell_size = _read_i32(stream)
        width = _read_i32(stream)
        height = _read_i32(stream)
        length = _read_i32(stream)
        if width <= 0 or height <= 0 or length != width * height:
            raise ValueError(f"invalid tile dimensions {width}x{height} with length {length}")
        tiles = list(stream.read(length))
        if len(tiles) != length:
            raise EOFError("unexpected eof while reading tiles")
        if version in LEGACY_VERSIONS:
            tiles = [_upgrade_pre_v9_tile(tile) for tile in tiles]

    return {
        "path": path,
        "version": version,
        "center_x": center_x,
        "center_y": center_y,
        "center_z": center_z,
        "cell_size": cell_size,
        "width": width,
        "height": height,
        "tiles": tiles,
    }