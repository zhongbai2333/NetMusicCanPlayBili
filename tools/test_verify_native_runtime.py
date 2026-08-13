from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import verify_native_runtime as subject


class VerifyNativeRuntimeMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_readme(
        self,
        release: str,
        *,
        source_commit: str = "1" * 40,
        runtime_version: str | None = None,
        duplicate_release: bool = False,
    ) -> None:
        lines = [
            "# Embedded FFmpeg native libraries",
            "",
            f"- Release: `{release}`",
            f"- Source commit: `{source_commit}`",
        ]
        if runtime_version is not None:
            lines.extend(
                f"- FFmpeg runtime version ({platform}): `{runtime_version}`"
                for platform in subject.SUPPORTED_PLATFORMS
            )
        if duplicate_release:
            lines.append(f"- Release: `{release}`")
        (self.root / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def test_v38_uses_frozen_legacy_version_and_export_set(self) -> None:
        self.write_readme("media-min-v38")
        metadata = subject.read_bundle_metadata(self.root)
        self.assertEqual(
            subject.LEGACY_V38_FFMPEG_VERSION,
            metadata.runtime_version_for("macos-arm64"),
        )
        self.assertEqual(subject.VIDEO_METHODS, metadata.video_methods)
        self.assertNotIn("sendEndOfStream", metadata.video_methods)

    def test_current_release_requires_declared_runtime_version_and_eof_export(self) -> None:
        self.write_readme(subject.CURRENT_RELEASE, runtime_version="N-126208-g3b3d6f4")
        metadata = subject.read_bundle_metadata(self.root)
        self.assertEqual("N-126208-g3b3d6f4", metadata.runtime_version_for("linux-arm64"))
        self.assertEqual(subject.CURRENT_VIDEO_METHODS, metadata.video_methods)
        self.assertIn("sendEndOfStream", metadata.video_methods)

    def test_current_release_rejects_missing_runtime_version(self) -> None:
        self.write_readme(subject.CURRENT_RELEASE)
        with self.assertRaisesRegex(RuntimeError, "must contain exactly one FFmpeg runtime version"):
            subject.read_bundle_metadata(self.root)

    def test_rejects_unknown_or_duplicate_release(self) -> None:
        self.write_readme("media-min-v40")
        with self.assertRaisesRegex(RuntimeError, "unsupported embedded native release"):
            subject.read_bundle_metadata(self.root)

        self.write_readme(subject.CURRENT_RELEASE, runtime_version="8.0.git", duplicate_release=True)
        with self.assertRaisesRegex(RuntimeError, "exactly one release"):
            subject.read_bundle_metadata(self.root)

    def test_rejects_short_source_commit(self) -> None:
        self.write_readme(subject.CURRENT_RELEASE, source_commit="abc1234", runtime_version="8.0.git")
        with self.assertRaisesRegex(RuntimeError, "exactly one full source commit"):
            subject.read_bundle_metadata(self.root)


if __name__ == "__main__":
    unittest.main()
