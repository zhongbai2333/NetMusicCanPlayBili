#!/usr/bin/env python3
"""Decode the frozen real AV1 fixture through the current platform's v39 JNI bundle."""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform as host_platform
import re
import shutil
import subprocess
import tempfile
from typing import Any

import verify_native_runtime


CLASS_NAME = "com.zhongbai233.net_music_can_play_bili.media.codec.VideoJni"
HARDWARE_BACKENDS = {
    "linux": "vaapi",
    "darwin": "videotoolbox",
    "windows": "d3d11va",
}
CURRENT_RESOURCE_FIELDS = (
    "ffmpegBytes",
    "d3d11Textures",
    "d3d11Surfaces",
    "d3d11LogicalBytes",
)


def executable(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise RuntimeError(f"required Java executable is not on PATH: {name}")
    return path


def require_matching_runner(platform_name: str) -> None:
    expected = verify_native_runtime.SUPPORTED_PLATFORMS[platform_name]
    actual = (verify_native_runtime.normalized_os(), verify_native_runtime.normalized_arch())
    if actual != expected:
        raise RuntimeError(
            f"runner mismatch for {platform_name}: expected={expected[0]}/{expected[1]}, "
            f"actual={actual[0]}/{actual[1]}"
        )


def smoke_environment(platform_name: str, native_dir: Path) -> dict[str, str]:
    environment = os.environ.copy()
    os_name, _ = verify_native_runtime.SUPPORTED_PLATFORMS[platform_name]
    if os_name == "windows":
        current = environment.get("PATH", "")
        environment["PATH"] = str(native_dir) + (os.pathsep + current if current else "")
    return environment


def resolve_hwaccel(platform_name: str, requested: str) -> str:
    os_name, _ = verify_native_runtime.SUPPORTED_PLATFORMS[platform_name]
    expected = HARDWARE_BACKENDS[os_name]
    normalized = requested.strip().lower()
    if normalized == "auto":
        return expected
    if normalized in {"none", "off"}:
        return "none"
    if normalized != expected:
        raise RuntimeError(
            f"hardware backend mismatch for {platform_name}: expected={expected}, requested={normalized}"
        )
    return normalized


def decoded_fixture_sha256(path: Path) -> str:
    payload = path.read_bytes()
    if path.name.lower().endswith(".b64"):
        payload = base64.b64decode(payload)
    return hashlib.sha256(payload).hexdigest()


def parse_smoke_markers(output_text: str) -> dict[str, str]:
    line = next(
        (line for line in output_text.splitlines() if line.startswith("real AV1 JNI smoke:")),
        None,
    )
    if line is None:
        raise RuntimeError(f"AV1 JNI smoke output has no result line: {output_text!r}")
    markers = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))
    if not markers:
        raise RuntimeError(f"AV1 JNI smoke result has no key/value markers: {line!r}")
    return markers


def parse_resource_tuple(value: str) -> dict[str, int]:
    parts = value.split("/")
    if len(parts) != len(CURRENT_RESOURCE_FIELDS):
        raise RuntimeError(f"invalid native resource tuple: {value!r}")
    return dict(zip(CURRENT_RESOURCE_FIELDS, (int(part) for part in parts), strict=True))


def command_json(command: list[str]) -> Any:
    try:
        completed = subprocess.run(
            command,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
        return json.loads(completed.stdout)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as error:
        return {"probeError": f"{type(error).__name__}: {error}"}


def collect_device_inventory(platform_name: str) -> dict[str, Any]:
    os_name, arch = verify_native_runtime.SUPPORTED_PLATFORMS[platform_name]
    inventory: dict[str, Any] = {
        "os": os_name,
        "arch": arch,
        "osDescription": host_platform.platform(),
        "runnerName": os.environ.get("RUNNER_NAME", "local"),
        "runnerLabels": [
            label.strip()
            for label in os.environ.get("RUNNER_LABELS", "").split(",")
            if label.strip()
        ],
    }
    if os_name == "darwin":
        probe = command_json(["/usr/sbin/system_profiler", "-json", "SPDisplaysDataType"])
        inventory["probe"] = "system_profiler SPDisplaysDataType"
        inventory["gpus"] = probe.get("SPDisplaysDataType", []) if isinstance(probe, dict) else []
        if isinstance(probe, dict) and "probeError" in probe:
            inventory["probeError"] = probe["probeError"]
    elif os_name == "windows":
        powershell = shutil.which("pwsh") or shutil.which("powershell") or "powershell.exe"
        probe = command_json(
            [
                powershell,
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,AdapterRAM,PNPDeviceID,Status | ConvertTo-Json -Compress",
            ]
        )
        inventory["probe"] = "Win32_VideoController"
        if isinstance(probe, list):
            inventory["gpus"] = probe
        elif isinstance(probe, dict) and "probeError" not in probe:
            inventory["gpus"] = [probe]
        else:
            inventory["gpus"] = []
            if isinstance(probe, dict):
                inventory["probeError"] = probe.get("probeError")
    else:
        gpus = []
        drm_root = Path("/sys/class/drm")
        for device in sorted(drm_root.glob("card[0-9]*/device")):
            entry: dict[str, str] = {"card": device.parent.name}
            for field in ("vendor", "device", "subsystem_vendor", "subsystem_device"):
                value_path = device / field
                try:
                    entry[field] = value_path.read_text(encoding="utf-8").strip()
                except OSError:
                    pass
            try:
                entry["driver"] = (device / "driver").resolve(strict=True).name
            except OSError:
                pass
            if len(entry) > 1:
                gpus.append(entry)
        inventory["probe"] = "Linux DRM sysfs"
        inventory["gpus"] = gpus
        vainfo = shutil.which("vainfo")
        if vainfo is not None:
            try:
                completed = subprocess.run(
                    [vainfo, "--display", "drm"],
                    check=False,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    timeout=30,
                )
                inventory["vainfo"] = completed.stdout[-16_384:]
            except (OSError, subprocess.SubprocessError) as error:
                inventory["vainfoError"] = f"{type(error).__name__}: {error}"
    return inventory


def write_report(
    report: Path,
    *,
    platform_name: str,
    requested: str,
    metadata: verify_native_runtime.BundleMetadata,
    fixture: Path,
    seek_fixture: Path | None,
    output_text: str,
    markers: dict[str, str],
    device_inventory: dict[str, Any] | None = None,
) -> None:
    integer_markers = {
        key: int(value)
        for key, value in markers.items()
        if key not in {
            "requested",
            "actual",
            "outputFormat",
            "baselineResources",
            "activeResources",
            "afterCloseResources",
        }
    }
    video = {
        "width": integer_markers.pop("width"),
        "height": integer_markers.pop("height"),
        "fps": integer_markers.pop("fps"),
        "outputFormat": markers["outputFormat"],
    }
    document = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "status": "passed",
        "platform": platform_name,
        "runner": {
            "os": verify_native_runtime.normalized_os(),
            "arch": verify_native_runtime.normalized_arch(),
        },
        "device": device_inventory
        if device_inventory is not None
        else collect_device_inventory(platform_name),
        "bundle": {
            "release": metadata.release,
            "sourceCommit": metadata.source_commit,
            "ffmpegRuntimeVersion": metadata.runtime_version_for(platform_name),
        },
        "decoder": {
            "requested": requested,
            "actual": markers["actual"],
        },
        "video": video,
        "fixtures": {
            "initial": {
                "path": fixture.name,
                "decodedSha256": decoded_fixture_sha256(fixture),
            },
            "seek": None
            if seek_fixture is None
            else {
                "path": seek_fixture.name,
                "decodedSha256": decoded_fixture_sha256(seek_fixture),
            },
        },
        "decode": integer_markers,
        "resources": {
            "baseline": parse_resource_tuple(markers["baselineResources"]),
            "active": parse_resource_tuple(markers["activeResources"]),
            "afterClose": parse_resource_tuple(markers["afterCloseResources"]),
        },
        "rawOutput": output_text,
    }
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_failure_report(
    report: Path,
    *,
    platform_name: str,
    requested: str,
    native_root: Path,
    error: Exception,
) -> None:
    try:
        metadata = verify_native_runtime.read_bundle_metadata(native_root)
        bundle: dict[str, str] | None = {
            "release": metadata.release,
            "sourceCommit": metadata.source_commit,
            "ffmpegRuntimeVersion": metadata.runtime_version_for(platform_name),
        }
    except Exception:
        bundle = None
    raw_output = ""
    if isinstance(error, subprocess.CalledProcessError):
        raw_output = str(error.stdout or error.output or "")
    document = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "status": "failed",
        "platform": platform_name,
        "runner": {
            "os": verify_native_runtime.normalized_os(),
            "arch": verify_native_runtime.normalized_arch(),
        },
        "device": collect_device_inventory(platform_name),
        "bundle": bundle,
        "decoder": {"requested": requested},
        "failure": {
            "type": type(error).__name__,
            "message": str(error),
        },
        "rawOutput": raw_output,
    }
    cleanup_line = next(
        (line for line in raw_output.splitlines() if line.startswith("real AV1 JNI cleanup:")),
        None,
    )
    if cleanup_line is not None:
        cleanup_markers = dict(
            re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^\s]+)", cleanup_line)
        )
        baseline = cleanup_markers.get("baselineResources")
        after_close = cleanup_markers.get("afterCloseResources")
        if baseline is not None and after_close is not None and after_close != "unavailable":
            document["resources"] = {
                "baseline": parse_resource_tuple(baseline),
                "afterClose": parse_resource_tuple(after_close),
                "converged": baseline == after_close,
            }
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run_smoke(
    platform_name: str,
    native_root: Path,
    source: Path,
    fixture: Path,
    hwaccel: str = "auto",
    seek_fixture: Path | None = None,
    report: Path | None = None,
) -> str:
    metadata = verify_native_runtime.read_bundle_metadata(native_root)
    require_matching_runner(platform_name)
    requested = resolve_hwaccel(platform_name, hwaccel)
    if metadata.release != verify_native_runtime.CURRENT_RELEASE:
        raise RuntimeError(
            f"native hardware device validation requires {verify_native_runtime.CURRENT_RELEASE}: "
            f"platform={platform_name} release={metadata.release}"
        )
    if requested == "none":
        raise RuntimeError("the embedded bundle has no AV1 software decoder; use auto or an exact hardware backend")

    native_dir = (native_root / platform_name).resolve(strict=True)
    source = source.resolve(strict=True)
    fixture = fixture.resolve(strict=True)
    if seek_fixture is not None:
        seek_fixture = seek_fixture.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="ncpb-native-av1-smoke-") as output:
        subprocess.run(
            [executable("javac"), "-encoding", "UTF-8", "-d", output, str(source)],
            check=True,
        )
        command = [
                executable("java"),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                output,
                CLASS_NAME,
                str(native_dir),
                str(fixture),
                requested,
            ]
        if seek_fixture is not None:
            command.append(str(seek_fixture))
        completed = subprocess.run(
            command,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            env=smoke_environment(platform_name, native_dir),
        )
    output_text = completed.stdout.strip()
    markers = parse_smoke_markers(output_text)
    required = [
        "real AV1 JNI smoke:",
        f"requested={requested}",
        "packets=125",
        "framesAfterEofDrain=125",
        "firstPts=0",
        "lastPts=4960000000",
        "width=682",
        "height=360",
        "fps=25",
        "outputFormat=NV12",
        "initialDecodeNanos=",
        "baselineResources=",
        "activeResources=",
        "afterCloseResources=",
    ]
    required.append(f"actual={requested}")
    if seek_fixture is not None:
        required.extend(
            (
                "seekPackets=125",
                "seekFramesAfterEofDrain=125",
                "seekFirstPts=35000000000",
                "seekLastPts=39960000000",
                "seekDecodeNanos=",
            )
        )
    missing = [marker for marker in required if marker not in output_text]
    if missing:
        raise RuntimeError(
            f"AV1 JNI smoke output is missing required markers {missing}: {output_text!r}"
        )
    duration_markers = ["initialDecodeNanos"]
    if seek_fixture is not None:
        duration_markers.append("seekDecodeNanos")
    for duration_marker in duration_markers:
        if int(markers.get(duration_marker, "0")) <= 0:
            raise RuntimeError(f"AV1 JNI smoke has no positive {duration_marker}: {output_text!r}")
    baseline = parse_resource_tuple(markers["baselineResources"])
    after_close = parse_resource_tuple(markers["afterCloseResources"])
    if after_close != baseline:
        raise RuntimeError(
            f"native resources did not return to baseline: baseline={baseline}, afterClose={after_close}"
        )
    if report is not None:
        write_report(
            report,
            platform_name=platform_name,
            requested=requested,
            metadata=metadata,
            fixture=fixture,
            seek_fixture=seek_fixture,
            output_text=output_text,
            markers=markers,
        )
    print(
        f"native AV1 decode smoke passed: platform={platform_name} "
        f"release={metadata.release} ffmpeg={metadata.runtime_version_for(platform_name)}"
    )
    print(output_text)
    return output_text


def main() -> int:
    repository = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--platform", required=True, choices=sorted(verify_native_runtime.SUPPORTED_PLATFORMS)
    )
    parser.add_argument(
        "--native-root", type=Path, default=repository / "src/main/resources/native"
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=repository
        / "tools/native_av1_smoke/com/zhongbai233/net_music_can_play_bili/media/codec/VideoJni.java",
    )
    parser.add_argument(
        "--fixture",
        type=Path,
        default=repository
        / "src/test/resources/bili/real-av1/init-index-first-fragment.m4s.b64",
    )
    parser.add_argument(
        "--seek-fixture",
        type=Path,
        default=repository / "src/test/resources/bili/real-av1/seek-fragment-35s.m4s.b64",
    )
    parser.add_argument(
        "--hwaccel",
        default="auto",
        choices=("auto", "videotoolbox", "d3d11va", "vaapi"),
        help="auto selects the platform hardware backend; no AV1 software decoder is bundled",
    )
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        run_smoke(
            args.platform,
            args.native_root,
            args.source,
            args.fixture,
            args.hwaccel,
            args.seek_fixture,
            args.report,
        )
    except Exception as error:
        if args.report is not None:
            try:
                requested = resolve_hwaccel(args.platform, args.hwaccel)
            except Exception:
                requested = args.hwaccel
            write_failure_report(
                args.report,
                platform_name=args.platform,
                requested=requested,
                native_root=args.native_root,
                error=error,
            )
        raise
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
