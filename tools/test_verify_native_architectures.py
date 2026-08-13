from __future__ import annotations

from pathlib import Path
import struct
import tempfile
import unittest

import prepare_native_bundle
import verify_native_architectures as subject


class VerifyNativeArchitecturesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        runtime_lines = "".join(
            f"- FFmpeg runtime version ({platform}): `git-2026-08-13-1234abc`\n"
            for platform in prepare_native_bundle.PLATFORM_FILES
        )
        (self.root / "README.md").write_text(
            f"- Release: `{prepare_native_bundle.CURRENT_RELEASE}`\n"
            f"- Source commit: `{'1' * 40}`\n"
            f"{runtime_lines}",
            encoding="utf-8",
        )
        for platform, names in prepare_native_bundle.PLATFORM_FILES.items():
            directory = self.root / platform
            directory.mkdir()
            for name in names:
                if not name.endswith(".txt"):
                    (directory / name).write_bytes(self.binary_bytes(platform))

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def binary_bytes(platform: str, *, wrong_arch: bool = False) -> bytes:
        os_name, arch = platform.split("-", 1)
        if wrong_arch:
            arch = {"arm64": "x86_64", "x86_64": "arm64"}[arch]
        if os_name == "linux":
            data = bytearray(64)
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", data, 16, 3)
            struct.pack_into("<H", data, 18, prepare_native_bundle.ELF_MACHINES[arch])
            return bytes(data)
        if os_name == "macos":
            data = bytearray(32)
            data[:4] = b"\xcf\xfa\xed\xfe"
            struct.pack_into("<I", data, 4, prepare_native_bundle.MACHO_CPUS[arch])
            struct.pack_into("<I", data, 12, 6)
            return bytes(data)
        data = bytearray(0x100)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x80)
        data[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<H", data, 0x84, prepare_native_bundle.PE_MACHINES[arch])
        struct.pack_into("<H", data, 0x94, 0x20)
        struct.pack_into("<H", data, 0x96, 0x2000)
        struct.pack_into("<H", data, 0x98, 0x20B)
        return bytes(data)

    def test_accepts_exact_six_platform_identity(self) -> None:
        self.assertEqual(32, subject.verify_architectures(self.root))

    def test_rejects_swapped_architecture(self) -> None:
        target = self.root / "windows-arm64" / "video_jni.dll"
        target.write_bytes(self.binary_bytes("windows-arm64", wrong_arch=True))
        with self.assertRaisesRegex(ValueError, "binary architecture mismatch"):
            subject.verify_architectures(self.root)

    def test_rejects_missing_binary(self) -> None:
        (self.root / "macos-x86_64" / "libvideo_jni.dylib").unlink()
        with self.assertRaisesRegex(RuntimeError, "required native binary is missing"):
            subject.verify_architectures(self.root)


if __name__ == "__main__":
    unittest.main()
