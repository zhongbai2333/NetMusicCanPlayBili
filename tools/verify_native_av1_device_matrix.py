#!/usr/bin/env python3
"""Validate and summarize six physical-device AV1 hardware evidence reports."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import prepare_native_bundle
import verify_native_av1_smoke
import verify_native_runtime


INITIAL_FIXTURE_SHA256 = "39a7b50ce1ca0aae0906f69968d2c24cd9da6a88cd6c456615534969a754c349"
SEEK_FIXTURE_SHA256 = "c2abab80f42df19e261375e9b7f67d924da87c07bcad18c5fb6e2b8ccb5bd215"
EXPECTED_DECODE = {
    "packets": 125,
    "framesAfterEofDrain": 125,
    "firstPts": 0,
    "lastPts": 4_960_000_000,
    "seekPackets": 125,
    "seekFramesAfterEofDrain": 125,
    "seekFirstPts": 35_000_000_000,
    "seekLastPts": 39_960_000_000,
}


def require_mapping(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise RuntimeError(f"{label} must be an object")
    return value


def require_equal(actual: Any, expected: Any, label: str) -> None:
    if actual != expected:
        raise RuntimeError(f"{label} mismatch: expected={expected!r}, actual={actual!r}")


def validate_report(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"cannot read device report {path}: {error}") from error
    root = require_mapping(document, str(path))
    require_equal(root.get("schemaVersion"), 1, f"{path.name} schemaVersion")
    require_equal(root.get("status"), "passed", f"{path.name} status")
    platform_name = root.get("platform")
    if platform_name not in verify_native_runtime.SUPPORTED_PLATFORMS:
        raise RuntimeError(f"{path.name} has unsupported platform: {platform_name!r}")

    expected_os, expected_arch = verify_native_runtime.SUPPORTED_PLATFORMS[platform_name]
    runner = require_mapping(root.get("runner"), f"{path.name} runner")
    require_equal(runner.get("os"), expected_os, f"{platform_name} runner os")
    require_equal(runner.get("arch"), expected_arch, f"{platform_name} runner arch")

    device = require_mapping(root.get("device"), f"{path.name} device")
    require_equal(device.get("os"), expected_os, f"{platform_name} device os")
    require_equal(device.get("arch"), expected_arch, f"{platform_name} device arch")
    gpus = device.get("gpus")
    if not isinstance(gpus, list) or not gpus or not all(isinstance(gpu, dict) for gpu in gpus):
        raise RuntimeError(f"{platform_name} has no structured physical GPU inventory")
    if expected_os == "darwin":
        if not any(gpu.get("sppci_model") or gpu.get("_name") for gpu in gpus):
            raise RuntimeError(f"{platform_name} has no system_profiler GPU model")
    elif expected_os == "windows":
        if not any(gpu.get("Name") and gpu.get("DriverVersion") for gpu in gpus):
            raise RuntimeError(f"{platform_name} has no Windows GPU name/driver version")
    elif not any(gpu.get("vendor") and gpu.get("device") and gpu.get("driver") for gpu in gpus):
        raise RuntimeError(f"{platform_name} has no Linux DRM vendor/device/driver identity")

    bundle = require_mapping(root.get("bundle"), f"{path.name} bundle")
    require_equal(
        bundle.get("release"),
        prepare_native_bundle.CURRENT_RELEASE,
        f"{platform_name} release",
    )
    require_equal(
        bundle.get("sourceCommit"),
        prepare_native_bundle.FFMPEG_SOURCE_COMMIT,
        f"{platform_name} source commit",
    )
    require_equal(
        bundle.get("ffmpegRuntimeVersion"),
        prepare_native_bundle.FFMPEG_RUNTIME_VERSIONS[platform_name],
        f"{platform_name} FFmpeg runtime",
    )

    expected_backend = verify_native_av1_smoke.HARDWARE_BACKENDS[expected_os]
    decoder = require_mapping(root.get("decoder"), f"{path.name} decoder")
    require_equal(decoder.get("requested"), expected_backend, f"{platform_name} requested backend")
    require_equal(decoder.get("actual"), expected_backend, f"{platform_name} actual backend")

    video = require_mapping(root.get("video"), f"{path.name} video")
    require_equal(video.get("width"), 682, f"{platform_name} output width")
    require_equal(video.get("height"), 360, f"{platform_name} output height")
    require_equal(video.get("fps"), 25, f"{platform_name} output fps")
    require_equal(video.get("outputFormat"), "NV12", f"{platform_name} output format")

    fixtures = require_mapping(root.get("fixtures"), f"{path.name} fixtures")
    initial = require_mapping(fixtures.get("initial"), f"{path.name} initial fixture")
    seek = require_mapping(fixtures.get("seek"), f"{path.name} seek fixture")
    require_equal(initial.get("decodedSha256"), INITIAL_FIXTURE_SHA256, f"{platform_name} initial fixture")
    require_equal(seek.get("decodedSha256"), SEEK_FIXTURE_SHA256, f"{platform_name} seek fixture")

    decode = require_mapping(root.get("decode"), f"{path.name} decode")
    for key, expected in EXPECTED_DECODE.items():
        require_equal(decode.get(key), expected, f"{platform_name} {key}")
    for key in ("initialDecodeNanos", "seekDecodeNanos"):
        if not isinstance(decode.get(key), int) or decode[key] <= 0:
            raise RuntimeError(f"{platform_name} {key} must be a positive integer")

    resources = require_mapping(root.get("resources"), f"{path.name} resources")
    baseline = require_mapping(resources.get("baseline"), f"{path.name} baseline resources")
    active = require_mapping(resources.get("active"), f"{path.name} active resources")
    after_close = require_mapping(resources.get("afterClose"), f"{path.name} after-close resources")
    require_equal(after_close, baseline, f"{platform_name} resource convergence")
    for field in verify_native_av1_smoke.CURRENT_RESOURCE_FIELDS:
        if not isinstance(baseline.get(field), int) or baseline[field] < 0:
            raise RuntimeError(f"{platform_name} baseline {field} must be a non-negative integer")
        if not isinstance(active.get(field), int) or active[field] < 0:
            raise RuntimeError(f"{platform_name} active {field} must be a non-negative integer")
    if active["ffmpegBytes"] <= baseline["ffmpegBytes"]:
        raise RuntimeError(
            f"{platform_name} did not observe a live FFmpeg allocation: "
            f"baseline={baseline['ffmpegBytes']} active={active['ffmpegBytes']}"
        )
    if not isinstance(root.get("capturedAt"), str) or not root["capturedAt"].strip():
        raise RuntimeError(f"{platform_name} capturedAt is missing")
    return root


def verify_matrix(reports_directory: Path, output: Path) -> dict[str, Any]:
    paths = sorted(reports_directory.glob("*.json"))
    if not paths:
        raise RuntimeError(f"no device reports found in {reports_directory}")
    reports: dict[str, tuple[Path, dict[str, Any]]] = {}
    for path in paths:
        document = validate_report(path)
        platform_name = document["platform"]
        if platform_name in reports:
            raise RuntimeError(f"duplicate device reports for {platform_name}")
        reports[platform_name] = (path, document)
    expected = set(verify_native_runtime.SUPPORTED_PLATFORMS)
    actual = set(reports)
    if actual != expected:
        raise RuntimeError(
            f"device matrix platform set mismatch: missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )

    entries = []
    for platform_name in sorted(reports):
        path, document = reports[platform_name]
        entries.append(
            {
                "platform": platform_name,
                "file": path.name,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "capturedAt": document["capturedAt"],
                "actualBackend": document["decoder"]["actual"],
                "device": document["device"],
                "video": document["video"],
                "initialDecodeNanos": document["decode"]["initialDecodeNanos"],
                "seekDecodeNanos": document["decode"]["seekDecodeNanos"],
                "activeResources": document["resources"]["active"],
            }
        )
    summary = {
        "schemaVersion": 1,
        "status": "passed",
        "release": prepare_native_bundle.CURRENT_RELEASE,
        "sourceCommit": prepare_native_bundle.FFMPEG_SOURCE_COMMIT,
        "ffmpegRuntimeVersions": prepare_native_bundle.FFMPEG_RUNTIME_VERSIONS,
        "platformCount": len(entries),
        "reports": entries,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    summary = verify_matrix(args.reports_dir, args.output)
    print(
        "native AV1 hardware device matrix passed: "
        f"release={summary['release']} platforms={summary['platformCount']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
