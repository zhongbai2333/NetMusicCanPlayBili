#!/usr/bin/env python3
"""Validate FFmpeg release assets and stage one complete embedded native bundle."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import tarfile
import tempfile


PLATFORM_FILES: dict[str, tuple[str, ...]] = {
    "linux-arm64": (
        "FFmpeg-LGPL-2.1.txt", "libavcodec.so.63",
        "libavutil.so.61", "libeac3_jni.so", "libswscale.so.10", "libvideo_jni.so",
    ),
    "linux-x86_64": (
        "FFmpeg-LGPL-2.1.txt", "libavcodec.so.63",
        "libavutil.so.61", "libeac3_jni.so", "libswscale.so.10", "libvideo_jni.so",
    ),
    "macos-arm64": (
        "FFmpeg-LGPL-2.1.txt", "libavcodec.63.dylib",
        "libavutil.61.dylib", "libeac3_jni.dylib", "libswscale.10.dylib", "libvideo_jni.dylib",
    ),
    "macos-x86_64": (
        "FFmpeg-LGPL-2.1.txt", "libavcodec.63.dylib",
        "libavutil.61.dylib", "libeac3_jni.dylib", "libswscale.10.dylib", "libvideo_jni.dylib",
    ),
    "windows-arm64": (
        "FFmpeg-LGPL-2.1.txt", "avcodec-63.dll", "avutil-61.dll",
        "eac3_jni.dll", "libwinpthread-1.dll", "swscale-10.dll", "video_jni.dll",
    ),
    "windows-x86_64": (
        "FFmpeg-LGPL-2.1.txt", "avcodec-63.dll", "avutil-61.dll",
        "eac3_jni.dll", "libwinpthread-1.dll", "swscale-10.dll", "video_jni.dll",
    ),
}

CURRENT_RELEASE = "media-min-v48"
FFMPEG_SOURCE_COMMIT = "3b3d6f46bbd34049fcac013d743d75e953452431"
FFMPEG_UPSTREAM_BASE = "b397eba2f0d3d86daf1098d0f27daffccc74fea5"
FFMPEG_RUNTIME_VERSIONS = {
    "linux-arm64": "git-2026-08-13-3b3d6f4",
    "linux-x86_64": "git-2026-08-13-3b3d6f4",
    "macos-arm64": "git-2026-08-13-3b3d6f4",
    "macos-x86_64": "git-2026-08-13-3b3d6f4",
    "windows-arm64": "8.0.git",
    "windows-x86_64": "8.0.git",
}
FFMPEG_SOURCE_FILES = {
    "COPYING.LGPLv2.1": "246041b6ecf9bc32d718a62c57877c78b5eb397b6467e74ed7ae2626ab189c30",
    ".github/workflows/build.yml": "029bcb010faa532e9908e0e0d10634c8e574657ab65bdb8541bf4d02539af9af",
    "configure": "68a001bbee07696d4a44e90e9e2ac5783fcfe58a8c90b36d0672c63e979bd6e6",
    "libavcodec/allcodecs.c": "85e5d76f4729e5a01061066e59cbd916f3eb3d2b5ad033343d54a6026e75ade2",
    "eac3_jni.c": "e67a454a35460b4a0e358d3f03f03c6caad3562faafb6bf91b0b6ff0c1217d8b",
    "video_jni.c": "550ddc8a6ffd27270ae4d5b1034e40d0c823843c0fba51c997419b01d328d491",
}
SHA_LINE = re.compile(r"^([0-9a-f]{64})  ([A-Za-z0-9._+-]+)$")
ELF_MACHINES = {"arm64": 183, "x86_64": 62}
MACHO_CPUS = {"arm64": 0x0100000C, "x86_64": 0x01000007}
PE_MACHINES = {"arm64": 0xAA64, "x86_64": 0x8664}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_release_hashes(asset_dir: Path) -> dict[str, str]:
    manifest = asset_dir / "SHA256SUMS.txt"
    if not manifest.is_file():
        raise ValueError(f"release manifest is missing: {manifest}")
    hashes: dict[str, str] = {}
    for number, raw in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
        match = SHA_LINE.fullmatch(raw.strip())
        if not match:
            raise ValueError(f"invalid release SHA256SUMS line {number}: {raw!r}")
        name = match.group(2)
        if name in hashes:
            raise ValueError(f"duplicate release asset hash: {name}")
        hashes[name] = match.group(1)
    return hashes


def parse_provenance(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ValueError(f"release provenance is missing: {path}")
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        if key in values:
            raise ValueError(f"duplicate release provenance key: {key}")
        if key and value:
            values[key] = value
    return values


def required_release_assets(tag: str) -> set[str]:
    return {
        *(f"ffmpeg-media-{platform}.tar.gz" for platform in PLATFORM_FILES),
        f"ffmpeg-media-source-{tag}.tar.gz",
        "changes.diff",
        "BUILD-PROVENANCE.txt",
    }


def validate_source_archive(archive: Path, root: str, required_files: dict[str, str]) -> None:
    found: dict[str, tarfile.TarInfo] = {}
    try:
        bundle = tarfile.open(archive, "r:gz")
    except (tarfile.TarError, OSError) as error:
        raise ValueError(f"corresponding-source archive is unreadable: {archive.name}") from error
    with bundle:
        for member in bundle.getmembers():
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != root:
                raise ValueError(f"unsafe corresponding-source path in {archive.name}: {member.name}")
            relative = str(PurePosixPath(*path.parts[1:]))
            if relative in found:
                raise ValueError(f"duplicate corresponding-source member in {archive.name}: {member.name}")
            if member.isdir():
                continue
            if not member.isfile():
                raise ValueError(
                    f"corresponding-source member must be a regular file: {archive.name}:{member.name}"
                )
            found[relative] = member
        missing = set(required_files) - set(found)
        empty = sorted(name for name in required_files if name in found and found[name].size == 0)
        if missing or empty:
            raise ValueError(
                f"corresponding-source archive is incomplete: {archive.name}, "
                f"missing={sorted(missing)}, empty={empty}"
            )
        for name, expected_hash in required_files.items():
            extracted = bundle.extractfile(found[name])
            if extracted is None:
                raise ValueError(f"cannot read corresponding-source member: {archive.name}:{name}")
            actual_hash = hashlib.sha256(extracted.read()).hexdigest()
            if actual_hash != expected_hash:
                raise ValueError(
                    f"corresponding-source SHA-256 mismatch: {archive.name}:{name}, "
                    f"expected={expected_hash}, actual={actual_hash}"
                )


def validate_binary_identity(platform: str, name: str, data: bytes) -> None:
    """Reject cross-platform or cross-architecture binaries before staging."""
    if name.endswith(".txt"):
        return
    os_name, arch = platform.split("-", 1)
    if os_name == "linux":
        if len(data) < 20 or data[:4] != b"\x7fELF":
            raise ValueError(f"binary format mismatch for {platform}/{name}: expected ELF")
        if data[4] != 2 or data[5] != 1:
            raise ValueError(f"ELF class/endianness mismatch for {platform}/{name}: expected ELF64 little-endian")
        if struct.unpack_from("<H", data, 16)[0] != 3:
            raise ValueError(f"ELF image is not ET_DYN for {platform}/{name}")
        machine = struct.unpack_from("<H", data, 18)[0]
        expected = ELF_MACHINES[arch]
        if machine != expected:
            raise ValueError(
                f"binary architecture mismatch for {platform}/{name}: "
                f"expected ELF e_machine={expected}, actual={machine}"
            )
        return
    if os_name == "macos":
        if len(data) < 16 or data[:4] != b"\xcf\xfa\xed\xfe":
            raise ValueError(f"binary format mismatch for {platform}/{name}: expected thin 64-bit Mach-O")
        cpu = struct.unpack_from("<I", data, 4)[0]
        expected = MACHO_CPUS[arch]
        if cpu != expected:
            raise ValueError(
                f"binary architecture mismatch for {platform}/{name}: "
                f"expected Mach-O cputype=0x{expected:08x}, actual=0x{cpu:08x}"
            )
        if struct.unpack_from("<I", data, 12)[0] != 6:
            raise ValueError(f"Mach-O image is not MH_DYLIB for {platform}/{name}")
        return
    if len(data) < 0x40 or data[:2] != b"MZ":
        raise ValueError(f"binary format mismatch for {platform}/{name}: expected PE/COFF")
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if pe_offset > len(data) - 26 or data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise ValueError(f"invalid PE header for {platform}/{name}")
    machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
    expected = PE_MACHINES[arch]
    if machine != expected:
        raise ValueError(
            f"binary architecture mismatch for {platform}/{name}: "
            f"expected PE Machine=0x{expected:04x}, actual=0x{machine:04x}"
        )
    optional_size = struct.unpack_from("<H", data, pe_offset + 20)[0]
    if optional_size < 2 or pe_offset + 24 + optional_size > len(data):
        raise ValueError(f"invalid PE optional header for {platform}/{name}")
    if struct.unpack_from("<H", data, pe_offset + 24)[0] != 0x20B:
        raise ValueError(f"PE image is not PE32+ for {platform}/{name}")
    if struct.unpack_from("<H", data, pe_offset + 22)[0] & 0x2000 == 0:
        raise ValueError(f"PE image is not marked as a DLL for {platform}/{name}")


def validate_release(asset_dir: Path, tag: str) -> tuple[dict[str, str], dict[str, str]]:
    hashes = parse_release_hashes(asset_dir)
    required = required_release_assets(tag)
    if set(hashes) != required:
        raise ValueError(
            f"release asset set mismatch: missing={sorted(required - set(hashes))}, "
            f"unexpected={sorted(set(hashes) - required)}"
        )
    for name, expected in hashes.items():
        path = asset_dir / name
        if not path.is_file():
            raise ValueError(f"release asset is missing: {path}")
        if path.stat().st_size == 0:
            raise ValueError(f"release asset is empty: {path}")
        actual = sha256(path)
        if actual != expected:
            raise ValueError(f"release asset SHA-256 mismatch: {name}, expected={expected}, actual={actual}")

    validate_source_archive(
        asset_dir / f"ffmpeg-media-source-{tag}.tar.gz",
        f"ffmpeg-media-source-{tag}",
        FFMPEG_SOURCE_FILES,
    )
    provenance = parse_provenance(asset_dir / "BUILD-PROVENANCE.txt")
    expected_values = {
        "release_tag": tag,
        "source_commit": FFMPEG_SOURCE_COMMIT,
        "upstream_base": FFMPEG_UPSTREAM_BASE,
    }
    for key, expected in expected_values.items():
        if provenance.get(key) != expected:
            raise ValueError(
                f"release provenance mismatch for {key}: expected={expected}, actual={provenance.get(key)}"
            )
    for platform, expected in FFMPEG_RUNTIME_VERSIONS.items():
        key = f"ffmpeg_runtime_version_{platform.replace('-', '_')}"
        if provenance.get(key) != expected:
            raise ValueError(
                f"release provenance mismatch for {key}: "
                f"expected={expected}, actual={provenance.get(key)}"
            )
    for key in ("linux_arm64_glibc_floor", "linux_x86_64_glibc_floor"):
        if not re.fullmatch(r"GLIBC_[0-9]+\.[0-9]+", provenance.get(key, "")):
            raise ValueError(f"release provenance has no valid {key}: {provenance.get(key)!r}")
    return hashes, provenance


def archive_payload(archive: Path, platform: str) -> dict[str, tuple[tarfile.TarInfo, bytes]]:
    expected = set(PLATFORM_FILES[platform])
    payload: dict[str, tuple[tarfile.TarInfo, bytes]] = {}
    with tarfile.open(archive, "r:gz") as bundle:
        for member in bundle.getmembers():
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"unsafe archive path in {archive.name}: {member.name}")
            if member.isdir():
                continue
            if not member.isfile() or member.issym() or member.islnk():
                raise ValueError(f"archive member must be a regular file: {archive.name}:{member.name}")
            if len(path.parts) != 2 or path.parts[0] != platform:
                raise ValueError(f"archive member is outside its platform root: {archive.name}:{member.name}")
            name = path.parts[1]
            if name in payload:
                raise ValueError(f"duplicate archive member: {archive.name}:{name}")
            extracted = bundle.extractfile(member)
            if extracted is None:
                raise ValueError(f"cannot read archive member: {archive.name}:{member.name}")
            data = extracted.read()
            if name in expected:
                validate_binary_identity(platform, name, data)
            payload[name] = (member, data)
    if set(payload) != expected:
        raise ValueError(
            f"archive file set mismatch for {platform}: missing={sorted(expected - set(payload))}, "
            f"unexpected={sorted(set(payload) - expected)}"
        )
    return payload


def render_readme(tag: str, hashes: dict[str, str], provenance: dict[str, str]) -> str:
    rows = []
    labels = {
        "linux-arm64": "Linux ARM64", "linux-x86_64": "Linux x86_64",
        "macos-arm64": "macOS ARM64", "macos-x86_64": "macOS x86_64",
        "windows-arm64": "Windows ARM64", "windows-x86_64": "Windows x86_64",
    }
    for platform in PLATFORM_FILES:
        asset = f"ffmpeg-media-{platform}.tar.gz"
        rows.append(f"| {labels[platform]} | `{asset}` | `{hashes[asset]}` |")
    runtime_rows = []
    for platform in PLATFORM_FILES:
        key = f"ffmpeg_runtime_version_{platform.replace('-', '_')}"
        runtime_rows.append(f"- FFmpeg runtime version ({platform}): `{provenance[key]}`")
    runtime_lines = os.linesep.join(runtime_rows)
    return f"""# Embedded FFmpeg native libraries

> **Hardware-only AV1 bundle:** all six platform directories come from `{tag}` as one indivisible bundle.
> AV1 requires the platform hardware backend; an unavailable or failed AV1 backend falls back to H.264 hardware
> or software decoding. No AV1 software decoder and no HEVC decoder are bundled.

- Release: `{tag}`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/{tag}>
- Source commit: `{provenance['source_commit']}`
- Upstream base: `{provenance['upstream_base']}`
{runtime_lines}

| Platform | Asset | SHA-256 |
| --- | --- | --- |
{os.linesep.join(rows)}

`native/SHA256SUMS` is the authoritative exact extracted-file manifest. Every platform contains the unmodified
`FFmpeg-LGPL-2.1.txt`. The release also carries the FFmpeg corresponding-source archive,
`changes.diff`, `BUILD-PROVENANCE.txt`, and its release-level `SHA256SUMS.txt`.

The libraries are architecture-specific and must not be mixed between releases. Linux dynamically requires the
host libva/libva-drm/libdrm dependency closure recorded by the release audit. The extracted ELF symbol-version
requirements require the host glibc to provide at least `{provenance['linux_arm64_glibc_floor']}` on Linux ARM64
and at least `{provenance['linux_x86_64_glibc_floor']}` on Linux x86_64. Windows includes the matching winpthread
runtime; iconv is disabled. macOS uses thin Mach-O files with loader-relative dependencies.
"""


def prepare_bundle(asset_dir: Path, output_root: Path, tag: str) -> None:
    asset_dir = asset_dir.resolve(strict=True)
    if output_root.exists():
        raise ValueError(f"output root already exists: {output_root}")
    hashes, provenance = validate_release(asset_dir, tag)
    staged_payload = {
        platform: archive_payload(asset_dir / f"ffmpeg-media-{platform}.tar.gz", platform)
        for platform in PLATFORM_FILES
    }

    output_parent = output_root.parent.resolve()
    output_parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output_root.name}.", dir=output_parent))
    try:
        extracted_hashes: list[tuple[str, str]] = []
        for platform, payload in staged_payload.items():
            platform_dir = temporary / platform
            platform_dir.mkdir()
            for name, (member, data) in payload.items():
                target = platform_dir / name
                target.write_bytes(data)
                target.chmod(member.mode & 0o777)
                extracted_hashes.append((f"{platform}/{name}", hashlib.sha256(data).hexdigest()))
        manifest = "".join(f"{digest}  {path}\n" for path, digest in sorted(extracted_hashes))
        (temporary / "SHA256SUMS").write_text(manifest, encoding="utf-8")
        (temporary / "README.md").write_text(render_readme(tag, hashes, provenance), encoding="utf-8")
        if output_root.exists():
            raise ValueError(f"output root appeared while staging: {output_root}")
        os.replace(temporary, output_root)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets-dir", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--release-tag", default=CURRENT_RELEASE)
    args = parser.parse_args()
    prepare_bundle(args.assets_dir, args.output_root, args.release_tag)
    print(f"prepared verified native bundle: {args.output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
