#!/usr/bin/env python3
"""Re-fetch and verify the frozen real Bilibili AV1 fMP4 fixture.

The repository stores decoded fixture bytes as Base64 test resources. This tool
resolves a fresh signed CDN URL, downloads only the two frozen byte ranges, and
requires their SHA-256 values to match ``fixture.json``. Signed URLs and cookies
are never written to the repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "src/test/resources/bili/real-av1/fixture.json"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)


def request_bytes(url: str, *, referer: str, byte_range: str | None = None) -> bytes:
    headers = {"User-Agent": USER_AGENT, "Referer": referer, "Accept-Encoding": "identity"}
    if byte_range is not None:
        headers["Range"] = f"bytes={byte_range}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read()
        status = response.status
        if byte_range is None:
            return payload
        start_text, end_text = byte_range.split("-", 1)
        start, end = int(start_text), int(end_text)
        expected = end - start + 1
        if status == 206:
            if len(payload) != expected:
                raise RuntimeError(
                    f"partial response length mismatch for {byte_range}: {len(payload)} != {expected}"
                )
            return payload
        if status == 200 and len(payload) > end:
            return payload[start : end + 1]
        raise RuntimeError(f"CDN ignored/invalidated range {byte_range}: HTTP {status}, bytes={len(payload)}")


def resolve_stream(manifest: dict) -> tuple[str, str]:
    source = manifest["source"]
    referer = f"https://www.bilibili.com/video/{source['bvid']}/"
    query = urllib.parse.urlencode(
        {
            "bvid": source["bvid"],
            "cid": source["cid"],
            "qn": source["quality"],
            "fnver": 0,
            "fnval": 2064,
            "fourk": 0,
        }
    )
    response = json.loads(
        request_bytes(f"https://api.bilibili.com/x/player/playurl?{query}", referer=referer).decode("utf-8")
    )
    if response.get("code") != 0:
        raise RuntimeError(f"Bilibili playurl failed: {response.get('code')} {response.get('message')}")
    matches = [
        item
        for item in response.get("data", {}).get("dash", {}).get("video", [])
        if item.get("id") == source["quality"]
        and item.get("codecid") == source["codecId"]
        and item.get("codecs") == source["codecs"]
        and item.get("width") == source["width"]
        and item.get("height") == source["height"]
    ]
    if len(matches) != 1:
        raise RuntimeError(f"expected one exact frozen AV1 stream, got {len(matches)}")
    item = matches[0]
    segment_base = item.get("SegmentBase") or item.get("segment_base") or {}
    initialization = segment_base.get("Initialization") or segment_base.get("initialization")
    index_range = segment_base.get("indexRange") or segment_base.get("index_range")
    expected_segment = manifest["segmentBase"]
    if initialization != expected_segment["initialization"] or index_range != expected_segment["indexRange"]:
        raise RuntimeError(
            "SegmentBase changed: "
            f"init={initialization!r}, index={index_range!r}; expected {expected_segment!r}"
        )
    url = item.get("baseUrl") or item.get("base_url")
    if not url:
        raise RuntimeError("selected AV1 stream has no base URL")
    return url, referer


def fetch_fixture(manifest_path: Path, output_directory: Path) -> list[Path]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise RuntimeError(f"unsupported fixture schema: {manifest.get('schemaVersion')!r}")
    url, referer = resolve_stream(manifest)
    output_directory.mkdir(parents=True, exist_ok=True)
    outputs: list[Path] = []
    for name, spec in manifest["resources"].items():
        payload = request_bytes(url, referer=referer, byte_range=spec["sourceRange"])
        digest = hashlib.sha256(payload).hexdigest()
        if len(payload) != spec["decodedBytes"] or digest != spec["sha256"]:
            raise RuntimeError(
                f"frozen resource {name} changed: bytes={len(payload)}, sha256={digest}; "
                f"expected bytes={spec['decodedBytes']}, sha256={spec['sha256']}"
            )
        output = output_directory / spec["file"].removesuffix(".b64")
        output.write_bytes(payload)
        outputs.append(output)
        print(f"verified {name}: bytes={len(payload)} sha256={digest} output={output}")
    return outputs


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    fetch_fixture(args.manifest.resolve(), args.output_directory.resolve())


if __name__ == "__main__":
    main()
