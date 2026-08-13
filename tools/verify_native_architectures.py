#!/usr/bin/env python3
"""Statically verify all six embedded native binary formats and architectures."""

from __future__ import annotations

import argparse
from pathlib import Path

from prepare_native_bundle import PLATFORM_FILES, validate_binary_identity
from verify_native_runtime import read_bundle_metadata


def verify_architectures(native_root: Path) -> int:
    native_root = native_root.resolve(strict=True)
    metadata = read_bundle_metadata(native_root)
    checked = 0
    for platform, names in PLATFORM_FILES.items():
        for name in names:
            if name.endswith(".txt"):
                continue
            binary = native_root / platform / name
            if not binary.is_file():
                raise RuntimeError(f"required native binary is missing: {binary}")
            validate_binary_identity(platform, name, binary.read_bytes())
            checked += 1
    print(
        f"native architecture audit passed: release={metadata.release} "
        f"platforms={len(PLATFORM_FILES)} binaries={checked}"
    )
    return checked


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--native-root",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "src/main/resources/native",
    )
    args = parser.parse_args()
    verify_architectures(args.native_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
