#!/usr/bin/env python3

from pathlib import Path
import json
import subprocess
import tempfile
import unittest
from unittest import mock

import verify_native_av1_smoke as subject


def bundle_metadata(release: str, source_commit: str, runtime_version: str):
    return subject.verify_native_runtime.BundleMetadata(
        release,
        source_commit,
        {platform: runtime_version for platform in subject.verify_native_runtime.SUPPORTED_PLATFORMS},
    )


class VerifyNativeAv1SmokeTest(unittest.TestCase):
    def test_auto_hardware_backend_is_platform_exact(self) -> None:
        self.assertEqual("videotoolbox", subject.resolve_hwaccel("macos-arm64", "auto"))
        self.assertEqual("d3d11va", subject.resolve_hwaccel("windows-x86_64", "auto"))
        self.assertEqual("vaapi", subject.resolve_hwaccel("linux-arm64", "auto"))
        with self.assertRaisesRegex(RuntimeError, "hardware backend mismatch"):
            subject.resolve_hwaccel("windows-arm64", "vaapi")

    def test_matching_runner_accepts_exact_platform(self) -> None:
        with mock.patch.object(subject.verify_native_runtime, "normalized_os", return_value="linux"), \
                mock.patch.object(subject.verify_native_runtime, "normalized_arch", return_value="arm64"):
            subject.require_matching_runner("linux-arm64")

    def test_matching_runner_rejects_wrong_architecture(self) -> None:
        with mock.patch.object(subject.verify_native_runtime, "normalized_os", return_value="linux"), \
                mock.patch.object(subject.verify_native_runtime, "normalized_arch", return_value="x86_64"):
            with self.assertRaisesRegex(RuntimeError, "runner mismatch"):
                subject.require_matching_runner("linux-arm64")

    def test_windows_smoke_prepends_native_directory_to_dll_search_path(self) -> None:
        native = Path("C:/ncpb/native/windows-arm64")
        with mock.patch.dict(subject.os.environ, {"PATH": "C:/Windows/System32"}, clear=True):
            environment = subject.smoke_environment("windows-arm64", native)
        self.assertEqual(
            f"{native}{subject.os.pathsep}C:/Windows/System32", environment["PATH"]
        )

    def test_non_windows_smoke_does_not_mutate_library_search_path(self) -> None:
        with mock.patch.dict(subject.os.environ, {"PATH": "/usr/bin"}, clear=True):
            environment = subject.smoke_environment("linux-arm64", Path("/tmp/native"))
        self.assertEqual("/usr/bin", environment["PATH"])

    def test_v38_is_rejected_without_invoking_java(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "VideoJni.java"
            fixture = root / "fixture.b64"
            source.write_text("class VideoJni {}", encoding="utf-8")
            fixture.write_text("fixture", encoding="utf-8")
            metadata = bundle_metadata(
                "media-min-v38", "a" * 40, subject.verify_native_runtime.LEGACY_V38_FFMPEG_VERSION
            )
            with mock.patch.object(subject.verify_native_runtime, "read_bundle_metadata", return_value=metadata), \
                    mock.patch.object(subject, "require_matching_runner"), \
                    mock.patch.object(subject.subprocess, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "requires media-min-v48"):
                    subject.run_smoke("macos-arm64", root, source, fixture)
            run.assert_not_called()

    def test_v38_cannot_pass_the_current_hardware_device_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = bundle_metadata(
                "media-min-v38", "a" * 40, subject.verify_native_runtime.LEGACY_V38_FFMPEG_VERSION
            )
            with mock.patch.object(subject.verify_native_runtime, "read_bundle_metadata", return_value=metadata), \
                    mock.patch.object(subject, "require_matching_runner"):
                with self.assertRaisesRegex(
                    RuntimeError, f"requires {subject.verify_native_runtime.CURRENT_RELEASE}"
                ):
                    subject.run_smoke(
                        "macos-arm64", root, root / "missing.java", root / "missing.b64", "auto"
                    )

    def test_current_release_requires_exact_hardware_decode_markers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "macos-arm64").mkdir()
            source = root / "VideoJni.java"
            fixture = root / "fixture.b64"
            source.write_text("class VideoJni {}", encoding="utf-8")
            fixture.write_text("fixture", encoding="utf-8")
            metadata = bundle_metadata(
                subject.verify_native_runtime.CURRENT_RELEASE, "b" * 40, "git-test"
            )
            good_output = (
                "real AV1 JNI smoke: requested=videotoolbox actual=videotoolbox width=682 height=360 "
                "fps=25 outputFormat=NV12 packets=125 "
                "framesBeforeEofDrain=125 framesDrainedAtEof=0 framesAfterEofDrain=125 "
                "firstPts=0 lastPts=4960000000 initialDecodeNanos=1000 seekDecodeNanos=1 "
                "baselineResources=0/0/0/0 "
                "activeResources=3987904/0/0/0 afterCloseResources=0/0/0/0\n"
            )
            completed = mock.Mock(stdout=good_output)
            with mock.patch.object(subject.verify_native_runtime, "read_bundle_metadata", return_value=metadata), \
                    mock.patch.object(subject, "require_matching_runner"), \
                    mock.patch.object(subject, "executable", side_effect=lambda name: name), \
                    mock.patch.object(subject.subprocess, "run", side_effect=[mock.Mock(), completed]) as run:
                result = subject.run_smoke("macos-arm64", root, source, fixture)
            self.assertEqual(good_output.strip(), result)
            self.assertEqual(2, run.call_count)

    def test_current_release_rejects_wrong_backend_or_incomplete_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "macos-arm64").mkdir()
            source = root / "VideoJni.java"
            fixture = root / "fixture.b64"
            source.write_text("class VideoJni {}", encoding="utf-8")
            fixture.write_text("fixture", encoding="utf-8")
            metadata = bundle_metadata(
                subject.verify_native_runtime.CURRENT_RELEASE, "b" * 40, "git-test"
            )
            completed = mock.Mock(
                stdout="real AV1 JNI smoke: requested=videotoolbox actual=cpu packets=125\n"
            )
            with mock.patch.object(subject.verify_native_runtime, "read_bundle_metadata", return_value=metadata), \
                    mock.patch.object(subject, "require_matching_runner"), \
                    mock.patch.object(subject, "executable", side_effect=lambda name: name), \
                    mock.patch.object(subject.subprocess, "run", side_effect=[mock.Mock(), completed]):
                with self.assertRaisesRegex(RuntimeError, "missing required markers"):
                    subject.run_smoke("macos-arm64", root, source, fixture)

    def test_structured_report_records_exact_seek_and_resource_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "initial.b64"
            seek = root / "seek.b64"
            fixture.write_bytes(b"YWJj")
            seek.write_bytes(b"ZGVm")
            report = root / "report.json"
            output = (
                "real AV1 JNI smoke: requested=videotoolbox actual=videotoolbox width=682 height=360 "
                "fps=25 outputFormat=NV12 packets=125 "
                "framesBeforeEofDrain=124 framesDrainedAtEof=1 framesAfterEofDrain=125 "
                "firstPts=0 lastPts=4960000000 initialDecodeNanos=2000000 "
                "seekPackets=125 seekFramesBeforeEofDrain=123 "
                "seekFramesDrainedAtEof=2 seekFramesAfterEofDrain=125 seekFirstPts=35000000000 "
                "seekLastPts=39960000000 seekDecodeNanos=3000000 baselineResources=0/0/0/0 "
                "activeResources=1024/0/0/0 afterCloseResources=0/0/0/0"
            )
            metadata = bundle_metadata(
                subject.verify_native_runtime.CURRENT_RELEASE, "b" * 40, "git-test"
            )
            with mock.patch.object(subject.verify_native_runtime, "normalized_os", return_value="darwin"), \
                    mock.patch.object(subject.verify_native_runtime, "normalized_arch", return_value="arm64"):
                subject.write_report(
                    report,
                    platform_name="macos-arm64",
                    requested="videotoolbox",
                    metadata=metadata,
                    fixture=fixture,
                    seek_fixture=seek,
                    output_text=output,
                    markers=subject.parse_smoke_markers(output),
                    device_inventory={
                        "os": "darwin",
                        "arch": "arm64",
                        "gpus": [{"_name": "Apple M4"}],
                    },
                )

            document = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("passed", document["status"])
            self.assertEqual("videotoolbox", document["decoder"]["actual"])
            self.assertEqual("NV12", document["video"]["outputFormat"])
            self.assertEqual(682, document["video"]["width"])
            self.assertEqual("Apple M4", document["device"]["gpus"][0]["_name"])
            self.assertEqual(35_000_000_000, document["decode"]["seekFirstPts"])
            self.assertEqual(1024, document["resources"]["active"]["ffmpegBytes"])
            self.assertEqual(document["resources"]["baseline"], document["resources"]["afterClose"])

    def test_failure_report_preserves_negative_device_cleanup_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "failure.json"
            metadata = bundle_metadata(
                subject.verify_native_runtime.CURRENT_RELEASE, "b" * 40, "git-test"
            )
            error = subprocess.CalledProcessError(
                1,
                ["java"],
                output=(
                    "hardware AV1 is unsupported\n"
                    "real AV1 JNI cleanup: baselineResources=0/0/0/0 "
                    "afterCloseResources=0/0/0/0\n"
                ),
            )
            with mock.patch.object(subject.verify_native_runtime, "read_bundle_metadata", return_value=metadata), \
                    mock.patch.object(subject.verify_native_runtime, "normalized_os", return_value="darwin"), \
                    mock.patch.object(subject.verify_native_runtime, "normalized_arch", return_value="arm64"):
                subject.write_failure_report(
                    report,
                    platform_name="macos-arm64",
                    requested="videotoolbox",
                    native_root=root,
                    error=error,
                )

            document = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("failed", document["status"])
            self.assertEqual("videotoolbox", document["decoder"]["requested"])
            self.assertTrue(document["resources"]["converged"])
            self.assertIn("unsupported", document["rawOutput"])


if __name__ == "__main__":
    unittest.main()
