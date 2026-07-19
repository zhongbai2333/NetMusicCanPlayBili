import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from pad_cache_codec import read_snapshot
from pad_cache_style import style_snapshot


MAGIC = 0x4E504D53


def write_snapshot(path: Path, version: int, values, tiles):
    with path.open("wb") as stream:
        stream.write(struct.pack(">ii", MAGIC, version))
        stream.write(struct.pack(">i", values[0]))
        if version in {12, 18}:
            stream.write(struct.pack(">i", values[1]))
            offset = 2
        else:
            offset = 1
        stream.write(struct.pack(">iiii", values[offset], values[offset + 1], values[offset + 2], values[offset + 3]))
        stream.write(struct.pack(">i", len(tiles)))
        stream.write(bytes(tiles))


class PadCacheCodecTests(unittest.TestCase):
    def test_reads_v18_center_y_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.bin"
            write_snapshot(path, 18, (10, 64, -20, 2, 2, 2), [0, 1, 2, 3])
            snapshot = read_snapshot(path)
            self.assertEqual((10, 64, -20), (snapshot["center_x"], snapshot["center_y"], snapshot["center_z"]))
            self.assertEqual([0, 1, 2, 3], snapshot["tiles"])

    def test_reads_v9_without_center_y(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.bin"
            write_snapshot(path, 9, (10, -20, 2, 2, 2), [0, 1, 2, 3])
            snapshot = read_snapshot(path)
            self.assertIsNone(snapshot["center_y"])
            self.assertEqual(-20, snapshot["center_z"])

    def test_upgrades_pre_v9_tile_ordinals(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.bin"
            write_snapshot(path, 8, (10, -20, 2, 2, 2), [0, 1, 2, 7])
            self.assertEqual([0, 1, 3, 8], read_snapshot(path)["tiles"])

    def test_rejects_unknown_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.bin"
            write_snapshot(path, 17, (10, 64, -20, 2, 2, 2), [0, 1, 2, 3])
            with self.assertRaises(ValueError):
                read_snapshot(path)

    def test_style_snapshot_returns_expected_masks(self):
        snapshot = {"width": 3, "height": 3, "tiles": [3] * 9}
        styled = style_snapshot(snapshot)
        self.assertEqual(9, sum(styled["building_footprint"]))
        self.assertEqual(5, sum(styled["building_core"]))


if __name__ == "__main__":
    unittest.main()
