from __future__ import annotations

import hashlib
import io
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest

import prepare_native_bundle as subject
import verify_native_runtime


class PrepareNativeBundleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.tag = subject.CURRENT_RELEASE
        self.original_ffmpeg_source_files = subject.FFMPEG_SOURCE_FILES
        subject.FFMPEG_SOURCE_FILES = {
            name: hashlib.sha256(f"ffmpeg-media-source-{self.tag}:{name}".encode()).hexdigest()
            for name in self.original_ffmpeg_source_files
        }

    def tearDown(self) -> None:
        subject.FFMPEG_SOURCE_FILES = self.original_ffmpeg_source_files
        self.temp.cleanup()

    @staticmethod
    def binary_bytes(platform: str, *, wrong_arch: bool = False) -> bytes:
        os_name, arch = platform.split("-", 1)
        actual_arch = ({"arm64": "x86_64", "x86_64": "arm64"}[arch] if wrong_arch else arch)
        if os_name == "linux":
            data = bytearray(64)
            data[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", data, 16, 3)
            struct.pack_into("<H", data, 18, subject.ELF_MACHINES[actual_arch])
            return bytes(data)
        if os_name == "macos":
            data = bytearray(32)
            data[:4] = b"\xcf\xfa\xed\xfe"
            struct.pack_into("<I", data, 4, subject.MACHO_CPUS[actual_arch])
            struct.pack_into("<I", data, 12, 6)
            return bytes(data)
        data = bytearray(0x100)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x80)
        data[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<H", data, 0x84, subject.PE_MACHINES[actual_arch])
        struct.pack_into("<H", data, 0x94, 0x20)
        struct.pack_into("<H", data, 0x96, 0x2000)
        struct.pack_into("<H", data, 0x98, 0x20B)
        return bytes(data)

    def write_archive(
        self,
        platform: str,
        *,
        extra: str | None = None,
        symlink: bool = False,
        wrong_arch_name: str | None = None,
        invalid_binary_name: str | None = None,
    ) -> None:
        archive = self.assets / f"ffmpeg-media-{platform}.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            for name in subject.PLATFORM_FILES[platform]:
                if name.endswith(".txt"):
                    data = f"{platform}:{name}".encode()
                elif name == invalid_binary_name:
                    data = b"not-a-binary"
                else:
                    data = self.binary_bytes(platform, wrong_arch=name == wrong_arch_name)
                info = tarfile.TarInfo(f"{platform}/{name}")
                info.size = len(data)
                info.mode = 0o644 if name.endswith(".txt") else 0o755
                bundle.addfile(info, io.BytesIO(data))
            if extra is not None:
                data = b"extra"
                info = tarfile.TarInfo(f"{platform}/{extra}")
                info.size = len(data)
                bundle.addfile(info, io.BytesIO(data))
            if symlink:
                info = tarfile.TarInfo(f"{platform}/bad-link")
                info.type = tarfile.SYMTYPE
                info.linkname = "FFmpeg-LGPL-2.1.txt"
                bundle.addfile(info)

    def write_release(self, *, extra_archive_member: bool = False) -> None:
        for index, platform in enumerate(subject.PLATFORM_FILES):
            self.write_archive(platform, extra="unexpected" if extra_archive_member and index == 0 else None)
        source_archives = ((
            f"ffmpeg-media-source-{self.tag}.tar.gz",
            f"ffmpeg-media-source-{self.tag}",
            subject.FFMPEG_SOURCE_FILES,
        ),)
        for name, root, members in source_archives:
            with tarfile.open(self.assets / name, "w:gz") as bundle:
                for member in members:
                    data = f"{root}:{member}".encode()
                    info = tarfile.TarInfo(f"{root}/{member}")
                    info.size = len(data)
                    bundle.addfile(info, io.BytesIO(data))
        (self.assets / "changes.diff").write_bytes(b"diff --git a/file b/file\n")
        runtime_provenance = "".join(
            f"ffmpeg_runtime_version_{platform.replace('-', '_')}={version}\n"
            for platform, version in subject.FFMPEG_RUNTIME_VERSIONS.items()
        )
        provenance = (
            f"release_tag={self.tag}\n"
            f"source_commit={subject.FFMPEG_SOURCE_COMMIT}\n"
            f"upstream_base={subject.FFMPEG_UPSTREAM_BASE}\n"
            f"{runtime_provenance}"
            "linux_arm64_glibc_floor=GLIBC_2.38\n"
            "linux_x86_64_glibc_floor=GLIBC_2.35\n"
        )
        (self.assets / "BUILD-PROVENANCE.txt").write_text(provenance, encoding="utf-8")
        names = sorted(subject.required_release_assets(self.tag))
        manifest = "".join(
            f"{hashlib.sha256((self.assets / name).read_bytes()).hexdigest()}  {name}\n" for name in names
        )
        (self.assets / "SHA256SUMS.txt").write_text(manifest, encoding="utf-8")

    def test_prepares_exact_bundle_and_extracted_manifest(self) -> None:
        self.write_release()
        output = self.root / "native"
        subject.prepare_bundle(self.assets, output, self.tag)
        expected = {
            f"{platform}/{name}" for platform, names in subject.PLATFORM_FILES.items() for name in names
        }
        actual = {
            str(path.relative_to(output)) for path in output.rglob("*")
            if path.is_file() and path.name not in {"README.md", "SHA256SUMS"}
        }
        self.assertEqual(expected, actual)
        self.assertIn(
            f"Release: `{subject.CURRENT_RELEASE}`",
            (output / "README.md").read_text(encoding="utf-8"),
        )
        readme = (output / "README.md").read_text(encoding="utf-8")
        for platform, version in subject.FFMPEG_RUNTIME_VERSIONS.items():
            self.assertIn(f"FFmpeg runtime version ({platform}): `{version}`", readme)
        self.assertIn("`GLIBC_2.38` on Linux ARM64", (output / "README.md").read_text(encoding="utf-8"))
        runtime_metadata = verify_native_runtime.read_bundle_metadata(output)
        self.assertEqual(subject.CURRENT_RELEASE, runtime_metadata.release)
        self.assertEqual(subject.FFMPEG_RUNTIME_VERSIONS, runtime_metadata.ffmpeg_runtime_versions)
        self.assertIn("sendEndOfStream", runtime_metadata.video_methods)
        self.assertEqual(len(expected), len((output / "SHA256SUMS").read_text().splitlines()))

    def test_rejects_release_hash_drift_without_creating_output(self) -> None:
        self.write_release()
        target = self.assets / "changes.diff"
        target.write_bytes(target.read_bytes() + b"tampered")
        output = self.root / "native"
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            subject.prepare_bundle(self.assets, output, self.tag)
        self.assertFalse(output.exists())

    def test_rejects_empty_release_asset(self) -> None:
        self.write_release()
        target = self.assets / "changes.diff"
        target.write_bytes(b"")
        lines = (self.assets / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
        rewritten = []
        for line in lines:
            name = line.split("  ", 1)[1]
            digest = subject.sha256(target) if name == target.name else line.split("  ", 1)[0]
            rewritten.append(f"{digest}  {name}")
        (self.assets / "SHA256SUMS.txt").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "release asset is empty"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def test_rejects_archive_extra_file(self) -> None:
        self.write_release(extra_archive_member=True)
        with self.assertRaisesRegex(ValueError, "archive file set mismatch"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def rewrite_archive_hash(self, platform: str) -> None:
        archive = self.assets / f"ffmpeg-media-{platform}.tar.gz"
        self.rewrite_asset_hash(archive)

    def rewrite_asset_hash(self, asset: Path) -> None:
        lines = (self.assets / "SHA256SUMS.txt").read_text().splitlines()
        rewritten = []
        for line in lines:
            name = line.split("  ", 1)[1]
            digest = subject.sha256(asset) if name == asset.name else line.split("  ", 1)[0]
            rewritten.append(f"{digest}  {name}")
        (self.assets / "SHA256SUMS.txt").write_text("\n".join(rewritten) + "\n")

    def test_rejects_wrong_binary_format(self) -> None:
        self.write_release()
        platform = "linux-arm64"
        self.write_archive(platform, invalid_binary_name="libvideo_jni.so")
        self.rewrite_archive_hash(platform)
        with self.assertRaisesRegex(ValueError, "binary format mismatch"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def test_rejects_wrong_architecture_for_every_binary_format(self) -> None:
        cases = (
            ("linux-arm64", "libvideo_jni.so"),
            ("macos-x86_64", "libvideo_jni.dylib"),
            ("windows-arm64", "video_jni.dll"),
        )
        for platform, name in cases:
            with self.subTest(platform=platform):
                case_root = self.root / platform
                case_root.mkdir()
                original_assets = self.assets
                self.assets = case_root
                try:
                    self.write_release()
                    self.write_archive(platform, wrong_arch_name=name)
                    self.rewrite_archive_hash(platform)
                    with self.assertRaisesRegex(ValueError, "binary architecture mismatch"):
                        subject.prepare_bundle(self.assets, case_root / "native", self.tag)
                finally:
                    self.assets = original_assets

    def test_rejects_symlink_member(self) -> None:
        self.write_release()
        platform = next(iter(subject.PLATFORM_FILES))
        self.write_archive(platform, symlink=True)
        self.rewrite_archive_hash(platform)
        with self.assertRaisesRegex(ValueError, "regular file"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def test_rejects_existing_output(self) -> None:
        self.write_release()
        output = self.root / "native"
        output.mkdir()
        with self.assertRaisesRegex(ValueError, "already exists"):
            subject.prepare_bundle(self.assets, output, self.tag)

    def test_rejects_missing_runtime_provenance(self) -> None:
        self.write_release()
        provenance = self.assets / "BUILD-PROVENANCE.txt"
        platform, version = next(iter(subject.FFMPEG_RUNTIME_VERSIONS.items()))
        key = f"ffmpeg_runtime_version_{platform.replace('-', '_')}"
        provenance.write_text(
            provenance.read_text(encoding="utf-8").replace(f"{key}={version}\n", ""),
            encoding="utf-8",
        )
        lines = (self.assets / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
        rewritten = []
        for line in lines:
            name = line.split("  ", 1)[1]
            digest = subject.sha256(provenance) if name == provenance.name else line.split("  ", 1)[0]
            rewritten.append(f"{digest}  {name}")
        (self.assets / "SHA256SUMS.txt").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, key):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def test_rejects_unreviewed_ffmpeg_identity(self) -> None:
        replacements = {
            "source_commit": "0" * 40,
            "upstream_base": "1" * 40,
            "ffmpeg_runtime_version_linux_arm64": "git-2026-08-13-deadbee",
        }
        for key, replacement in replacements.items():
            with self.subTest(key=key), tempfile.TemporaryDirectory(dir=self.root) as case_directory:
                original_assets = self.assets
                self.assets = Path(case_directory) / "assets"
                self.assets.mkdir()
                try:
                    self.write_release()
                    provenance = self.assets / "BUILD-PROVENANCE.txt"
                    lines = provenance.read_text(encoding="utf-8").splitlines()
                    lines = [
                        f"{key}={replacement}" if line.startswith(f"{key}=") else line
                        for line in lines
                    ]
                    provenance.write_text("\n".join(lines) + "\n", encoding="utf-8")
                    manifest = self.assets / "SHA256SUMS.txt"
                    manifest_lines = manifest.read_text(encoding="utf-8").splitlines()
                    manifest.write_text(
                        "\n".join(
                            f"{subject.sha256(provenance)}  {provenance.name}"
                            if line.endswith(f"  {provenance.name}") else line
                            for line in manifest_lines
                        ) + "\n",
                        encoding="utf-8",
                    )
                    with self.assertRaisesRegex(ValueError, f"release provenance mismatch for {key}"):
                        subject.prepare_bundle(self.assets, Path(case_directory) / "native", self.tag)
                finally:
                    self.assets = original_assets

    def test_rejects_duplicate_provenance_key(self) -> None:
        self.write_release()
        provenance = self.assets / "BUILD-PROVENANCE.txt"
        provenance.write_text(
            provenance.read_text(encoding="utf-8") + f"source_commit={'3' * 40}\n",
            encoding="utf-8",
        )
        lines = (self.assets / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
        rewritten = []
        for line in lines:
            name = line.split("  ", 1)[1]
            digest = subject.sha256(provenance) if name == provenance.name else line.split("  ", 1)[0]
            rewritten.append(f"{digest}  {name}")
        (self.assets / "SHA256SUMS.txt").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate release provenance key"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def test_rejects_incomplete_corresponding_source_archive(self) -> None:
        self.write_release()
        archive = self.assets / f"ffmpeg-media-source-{self.tag}.tar.gz"
        root = f"ffmpeg-media-source-{self.tag}"
        with tarfile.open(archive, "w:gz") as bundle:
            data = b"license"
            info = tarfile.TarInfo(f"{root}/COPYING.LGPLv2.1")
            info.size = len(data)
            bundle.addfile(info, io.BytesIO(data))
        lines = (self.assets / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
        rewritten = []
        for line in lines:
            name = line.split("  ", 1)[1]
            digest = subject.sha256(archive) if name == archive.name else line.split("  ", 1)[0]
            rewritten.append(f"{digest}  {name}")
        (self.assets / "SHA256SUMS.txt").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "corresponding-source archive is incomplete"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)

    def test_rejects_corresponding_source_content_from_another_commit(self) -> None:
        self.write_release()
        archive = self.assets / f"ffmpeg-media-source-{self.tag}.tar.gz"
        root = f"ffmpeg-media-source-{self.tag}"
        with tarfile.open(archive, "w:gz") as bundle:
            for member in subject.FFMPEG_SOURCE_FILES:
                data = (b"tampered-source" if member == "video_jni.c"
                        else f"{root}:{member}".encode())
                info = tarfile.TarInfo(f"{root}/{member}")
                info.size = len(data)
                bundle.addfile(info, io.BytesIO(data))
        self.rewrite_asset_hash(archive)
        with self.assertRaisesRegex(ValueError, "corresponding-source SHA-256 mismatch"):
            subject.prepare_bundle(self.assets, self.root / "native", self.tag)


if __name__ == "__main__":
    unittest.main()
