#!/usr/bin/env python3

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

import prepare_native_bundle
import verify_native_av1_device_matrix as subject
import verify_native_av1_smoke
import verify_native_runtime


def report(platform_name: str) -> dict:
    os_name, arch = verify_native_runtime.SUPPORTED_PLATFORMS[platform_name]
    backend = verify_native_av1_smoke.HARDWARE_BACKENDS[os_name]
    if os_name == "darwin":
        gpus = [{"_name": "Apple M4", "sppci_model": "Apple M4"}]
    elif os_name == "windows":
        gpus = [{"Name": "Test AV1 GPU", "DriverVersion": "1.2.3"}]
    else:
        gpus = [{"card": "card0", "vendor": "0x1234", "device": "0x5678", "driver": "test"}]
    return {
        "schemaVersion": 1,
        "capturedAt": "2026-08-13T00:00:00+00:00",
        "status": "passed",
        "platform": platform_name,
        "runner": {"os": os_name, "arch": arch},
        "device": {
            "os": os_name,
            "arch": arch,
            "osDescription": "test",
            "runnerName": "test-runner",
            "runnerLabels": [f"ncpb-av1-{platform_name}"],
            "gpus": gpus,
        },
        "bundle": {
            "release": prepare_native_bundle.CURRENT_RELEASE,
            "sourceCommit": prepare_native_bundle.FFMPEG_SOURCE_COMMIT,
            "ffmpegRuntimeVersion": prepare_native_bundle.FFMPEG_RUNTIME_VERSIONS[platform_name],
        },
        "decoder": {"requested": backend, "actual": backend},
        "video": {"width": 682, "height": 360, "fps": 25, "outputFormat": "NV12"},
        "fixtures": {
            "initial": {"decodedSha256": subject.INITIAL_FIXTURE_SHA256},
            "seek": {"decodedSha256": subject.SEEK_FIXTURE_SHA256},
        },
        "decode": {
            **subject.EXPECTED_DECODE,
            "initialDecodeNanos": 2_000_000,
            "seekDecodeNanos": 3_000_000,
        },
        "resources": {
            "baseline": {
                "ffmpegBytes": 0,
                "d3d11Textures": 0,
                "d3d11Surfaces": 0,
                "d3d11LogicalBytes": 0,
            },
            "active": {
                "ffmpegBytes": 4096,
                "d3d11Textures": 1 if os_name == "windows" else 0,
                "d3d11Surfaces": 8 if os_name == "windows" else 0,
                "d3d11LogicalBytes": 8192 if os_name == "windows" else 0,
            },
            "afterClose": {
                "ffmpegBytes": 0,
                "d3d11Textures": 0,
                "d3d11Surfaces": 0,
                "d3d11LogicalBytes": 0,
            },
        },
    }


class VerifyNativeAv1DeviceMatrixTest(unittest.TestCase):
    def write_matrix(self, root: Path) -> None:
        for platform_name in verify_native_runtime.SUPPORTED_PLATFORMS:
            (root / f"{platform_name}.json").write_text(
                json.dumps(report(platform_name)), encoding="utf-8"
            )

    def test_accepts_exact_six_platform_hardware_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_matrix(root)
            summary = subject.verify_matrix(root, root.parent / "summary.json")
            self.assertEqual("passed", summary["status"])
            self.assertEqual(6, summary["platformCount"])
            self.assertEqual(
                sorted(verify_native_runtime.SUPPORTED_PLATFORMS),
                [entry["platform"] for entry in summary["reports"]],
            )

    def test_rejects_missing_platform(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_matrix(root)
            (root / "windows-arm64.json").unlink()
            with self.assertRaisesRegex(RuntimeError, "platform set mismatch"):
                subject.verify_matrix(root, root.parent / "summary.json")

    def test_rejects_cpu_or_wrong_hardware_backend(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_matrix(root)
            path = root / "linux-arm64.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["decoder"]["actual"] = "cpu(libdav1d)"
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "actual backend mismatch"):
                subject.verify_matrix(root, root.parent / "summary.json")

    def test_rejects_nonconverged_resources(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_matrix(root)
            path = root / "windows-x86_64.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["resources"]["afterClose"]["d3d11Surfaces"] = 1
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "resource convergence mismatch"):
                subject.verify_matrix(root, root.parent / "summary.json")


if __name__ == "__main__":
    unittest.main()
