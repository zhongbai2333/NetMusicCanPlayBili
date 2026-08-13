#!/usr/bin/env python3
"""Load one embedded native bundle on its real OS/architecture and verify JNI exports."""

from __future__ import annotations

import argparse
import ctypes
from dataclasses import dataclass
import os
from pathlib import Path
import platform as host_platform
import re
import sys


LEGACY_V38_FFMPEG_VERSION = "git-2026-08-10-3dbd669"
CURRENT_RELEASE = "media-min-v48"
SUPPORTED_RELEASES = {"media-min-v38", CURRENT_RELEASE}
SUPPORTED_PLATFORMS = {
    "linux-arm64": ("linux", "arm64"),
    "linux-x86_64": ("linux", "x86_64"),
    "macos-arm64": ("darwin", "arm64"),
    "macos-x86_64": ("darwin", "x86_64"),
    "windows-arm64": ("windows", "arm64"),
    "windows-x86_64": ("windows", "x86_64"),
}
VIDEO_METHODS = (
    "decoderOpen",
    "decoderOpenForCodec",
    "decoderOpenForCodecWithHwaccel",
    "sendPacket",
    "sendPacketWithPts",
    "getVideoFrame",
    "getVideoFrameInto",
    "getVideoFrameYuv420",
    "getVideoFrameNv12",
    "getVideoFrameNv12IntoDirect",
    "receiveFrameNoCopy",
    "getLastFramePtsNanos",
    "flush",
    "getHwaccelName",
    "close",
    "getDimensions",
    "getNativeMemoryStats",
)
CURRENT_VIDEO_METHODS = VIDEO_METHODS + ("sendEndOfStream",)
EAC3_METHODS = ("decoderOpen", "decode", "flush", "close", "version")
JNI_PREFIX = "Java_com_zhongbai233_net_1music_1can_1play_1bili_media_codec_"


@dataclass(frozen=True)
class BundleMetadata:
    release: str
    source_commit: str
    ffmpeg_runtime_versions: dict[str, str]

    @property
    def video_methods(self) -> tuple[str, ...]:
        return CURRENT_VIDEO_METHODS if self.release == CURRENT_RELEASE else VIDEO_METHODS

    def runtime_version_for(self, platform_name: str) -> str:
        try:
            return self.ffmpeg_runtime_versions[platform_name]
        except KeyError as error:
            raise RuntimeError(f"native README has no runtime version for {platform_name}") from error


def unique_readme_value(text: str, pattern: str, label: str) -> str:
    values = re.findall(pattern, text, flags=re.MULTILINE)
    if len(values) != 1:
        raise RuntimeError(f"native README must contain exactly one {label}; found={values}")
    return values[0]


def read_bundle_metadata(native_root: Path) -> BundleMetadata:
    readme = native_root / "README.md"
    if not readme.is_file():
        raise RuntimeError(f"native bundle README is missing: {readme}")
    text = readme.read_text(encoding="utf-8-sig")
    release = unique_readme_value(text, r"^- Release: `(media-min-v[0-9]+)`$", "release")
    if release not in SUPPORTED_RELEASES:
        raise RuntimeError(f"unsupported embedded native release: {release}")
    source_commit = unique_readme_value(
        text, r"^- Source commit: `([0-9a-f]{40})`$", "full source commit"
    )
    runtime_pairs = re.findall(
        r"^- FFmpeg runtime version \(([a-z0-9_-]+)\): `([^`]+)`$",
        text,
        flags=re.MULTILINE,
    )
    if release == "media-min-v38":
        expected_version = LEGACY_V38_FFMPEG_VERSION
        if runtime_pairs:
            raise RuntimeError(
                f"v38 native README must not declare per-platform runtime versions: {runtime_pairs}"
            )
        runtime_versions = {platform: expected_version for platform in SUPPORTED_PLATFORMS}
    else:
        runtime_versions = dict(runtime_pairs)
        if len(runtime_pairs) != len(runtime_versions) or set(runtime_versions) != set(SUPPORTED_PLATFORMS):
            raise RuntimeError(
                f"{CURRENT_RELEASE} native README must contain exactly one FFmpeg runtime "
                f"version for every platform; found={runtime_pairs}"
            )
    return BundleMetadata(release, source_commit, runtime_versions)


def normalized_os() -> str:
    if sys.platform.startswith("linux"):
        return "linux"
    if sys.platform == "darwin":
        return "darwin"
    if sys.platform in {"win32", "cygwin"}:
        return "windows"
    return sys.platform.lower()


def normalized_arch() -> str:
    machine = host_platform.machine().lower().replace("-", "_")
    if machine in {"arm64", "aarch64"}:
        return "arm64"
    if machine in {"amd64", "x86_64", "x64"}:
        return "x86_64"
    return machine


def library_names(platform_name: str) -> tuple[list[str], list[str], str, str]:
    os_name, _ = SUPPORTED_PLATFORMS[platform_name]
    if os_name == "windows":
        return (
            ["libwinpthread-1.dll", "avutil-61.dll", "swscale-10.dll", "avcodec-63.dll"],
            ["eac3_jni.dll", "video_jni.dll"],
            "avutil-61.dll",
            "windows",
        )
    if os_name == "darwin":
        return (
            ["libavutil.61.dylib", "libswscale.10.dylib", "libavcodec.63.dylib"],
            ["libeac3_jni.dylib", "libvideo_jni.dylib"],
            "libavutil.61.dylib",
            "posix",
        )
    return (
        ["libavutil.so.61", "libswscale.so.10", "libavcodec.so.63"],
        ["libeac3_jni.so", "libvideo_jni.so"],
        "libavutil.so.61",
        "posix",
    )


def load_library(path: Path, loader_kind: str) -> ctypes.CDLL:
    if not path.is_file():
        raise RuntimeError(f"required native library is missing: {path}")
    if loader_kind == "windows":
        return ctypes.WinDLL(str(path))
    return ctypes.CDLL(str(path), mode=ctypes.RTLD_GLOBAL)


def require_exports(library: ctypes.CDLL, class_name: str, methods: tuple[str, ...]) -> None:
    missing = []
    for method in methods:
        symbol = f"{JNI_PREFIX}{class_name}_{method}"
        try:
            getattr(library, symbol)
        except AttributeError:
            missing.append(symbol)
    if missing:
        raise RuntimeError(f"missing JNI exports in {library._name}: {missing}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", required=True, choices=sorted(SUPPORTED_PLATFORMS))
    parser.add_argument(
        "--native-root",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "src/main/resources/native",
    )
    args = parser.parse_args()

    metadata = read_bundle_metadata(args.native_root)

    expected_os, expected_arch = SUPPORTED_PLATFORMS[args.platform]
    actual_os = normalized_os()
    actual_arch = normalized_arch()
    if (actual_os, actual_arch) != (expected_os, expected_arch):
        raise RuntimeError(
            f"runner mismatch for {args.platform}: expected={expected_os}/{expected_arch}, "
            f"actual={actual_os}/{actual_arch}"
        )

    native_dir = (args.native_root / args.platform).resolve(strict=True)
    ffmpeg_names, jni_names, avutil_name, loader_kind = library_names(args.platform)
    dll_directory = None
    if loader_kind == "windows":
        dll_directory = os.add_dll_directory(str(native_dir))
    try:
        loaded: dict[str, ctypes.CDLL] = {}
        for name in (*ffmpeg_names, *jni_names):
            loaded[name] = load_library(native_dir / name, loader_kind)

        avutil = loaded[avutil_name]
        avutil.av_version_info.argtypes = []
        avutil.av_version_info.restype = ctypes.c_char_p
        raw_version = avutil.av_version_info()
        version = raw_version.decode("utf-8") if raw_version else ""
        expected_version = metadata.runtime_version_for(args.platform)
        if version != expected_version:
            raise RuntimeError(
                f"FFmpeg version mismatch: expected={expected_version}, actual={version!r}"
            )

        require_exports(loaded[jni_names[0]], "Eac3Jni", EAC3_METHODS)
        require_exports(loaded[jni_names[1]], "VideoJni", metadata.video_methods)
    finally:
        if dll_directory is not None:
            dll_directory.close()

    print(
        f"native runtime smoke passed: platform={args.platform} "
        f"release={metadata.release} ffmpeg={metadata.runtime_version_for(args.platform)} "
        f"source={metadata.source_commit} eac3_exports={len(EAC3_METHODS)} "
        f"video_exports={len(metadata.video_methods)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
