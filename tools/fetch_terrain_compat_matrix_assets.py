#!/usr/bin/env python3
"""Download and verify the pinned, non-redistributed terrain compatibility matrix assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "tools" / "terrain_compat_matrix_assets.json"
USER_AGENT = "NetMusicCanPlayBili terrain compatibility matrix/1"


def verify_file(path: Path, spec: dict[str, object]) -> None:
    expected_bytes = int(spec["bytes"])
    expected_sha512 = str(spec["sha512"]).lower()
    digest = hashlib.sha512()
    actual_bytes = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            actual_bytes += len(chunk)
            digest.update(chunk)
    actual_sha512 = digest.hexdigest()
    if actual_bytes != expected_bytes or actual_sha512 != expected_sha512:
        raise RuntimeError(
            f"asset verification failed for {path.name}: bytes={actual_bytes}, sha512={actual_sha512}; "
            f"expected bytes={expected_bytes}, sha512={expected_sha512}"
        )


def download_asset(output: Path, spec: dict[str, object]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_file():
        verify_file(output, spec)
        print(f"verified cached {spec['id']}: {output}")
        return
    request = urllib.request.Request(str(spec["url"]), headers={"User-Agent": USER_AGENT})
    temporary_path: Path | None = None
    try:
        with urllib.request.urlopen(request, timeout=60) as response, tempfile.NamedTemporaryFile(
            prefix=f".{output.name}.", suffix=".part", dir=output.parent, delete=False
        ) as temporary:
            temporary_path = Path(temporary.name)
            while chunk := response.read(1024 * 1024):
                temporary.write(chunk)
        verify_file(temporary_path, spec)
        os.replace(temporary_path, output)
        temporary_path = None
        print(f"downloaded and verified {spec['id']}: {output}")
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.resolve().read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise RuntimeError(f"unsupported manifest schema: {manifest.get('schemaVersion')!r}")
    assets = manifest.get("assets")
    if not isinstance(assets, list) or not assets:
        raise RuntimeError("manifest must contain a non-empty assets array")
    seen_ids: set[str] = set()
    seen_files: set[str] = set()
    output_directory = args.output_directory.resolve()
    for spec in assets:
        asset_id = str(spec["id"])
        filename = str(spec["file"])
        if asset_id in seen_ids or filename in seen_files:
            raise RuntimeError(f"duplicate asset id or filename: {asset_id!r}, {filename!r}")
        if Path(filename).name != filename:
            raise RuntimeError(f"asset filename must not contain a path: {filename!r}")
        seen_ids.add(asset_id)
        seen_files.add(filename)
        download_asset(output_directory / filename, spec)


if __name__ == "__main__":
    main()
