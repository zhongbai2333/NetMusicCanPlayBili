#!/usr/bin/env python3
"""
Bilibili DASH / fMP4 timeline diagnostics for NetMusicCanPlayBili.

This script intentionally mirrors the Java mod's playback inputs without launching Minecraft:
- resolve Bilibili view/playurl for a BV/AV + page
- list DASH video/audio track metadata returned by Bilibili
- inspect selected track with ffprobe when available
- optionally sample video frame PTS and simulate the Java video frame scheduler

Examples:
  python tools/bili_timeline_probe.py BV17q9EBEE9w --page 2
  python tools/bili_timeline_probe.py BV17q9EBEE9w --page 2 --quality 80 --sample-start 5317 --sample-duration 20
  python tools/bili_timeline_probe.py BV17q9EBEE9w --page 2 --frames --max-frames 900
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)

MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 52, 44, 34,
]

QUALITY_NAMES = {
    127: "8K / HDR-ish depending on account",
    126: "Dolby Vision",
    125: "HDR",
    120: "4K",
    116: "1080P60",
    112: "1080P+",
    80: "1080P",
    74: "720P60",
    64: "720P",
    32: "480P",
    16: "360P",
    30280: "Dolby Atmos",
    30250: "Dolby Atmos / Hi-Res",
    30251: "Dolby Atmos / Hi-Res",
    30232: "AAC 132k",
    30216: "AAC 64k",
}


@dataclass(frozen=True)
class VideoId:
    kind: str
    value: str

    def playurl_key(self) -> str:
        return "bvid" if self.kind == "bvid" else "avid"


@dataclass
class FrameSample:
    index: int
    pts_ms: float
    best_effort_ms: float | None
    pkt_dts_ms: float | None
    duration_ms: float | None
    key_frame: bool


@dataclass
class SidxEntry:
    index: int
    time_ms: float
    duration_ms: float
    byte_start: int
    byte_end: int
    size: int


@dataclass
class MoofTiming:
    base_decode_time: int | None
    sample_count: int
    first_pts_ms: float | None
    last_pts_ms: float | None
    first_duration_ms: float | None


@dataclass
class SegmentProbe:
    label: str
    timescale: int | None
    sidx_timescale: int | None
    entry: SidxEntry | None
    timing: MoofTiming | None


@dataclass
class SubtitleInfo:
    lan: str
    url: str


@dataclass
class SubtitleLine:
    start_ms: float
    end_ms: float
    content: str


@dataclass(frozen=True)
class SyncScanConfig:
    audio_latency_ms: float
    video_latency_ms: float
    subtitle_latency_ms: float
    speaker_latency_ms: float
    tolerance_ms: float
    scan_min_ms: int
    scan_max_ms: int
    scan_step_ms: int
    local_timeline: str


def http_get(url: str, headers: dict[str, str] | None = None, timeout: int = 20) -> bytes:
    req = urllib.request.Request(url, headers=headers or {})
    req.add_header("User-Agent", USER_AGENT)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def http_range(url: str, start: int, end: int | None, sessdata: str = "", timeout: int = 30) -> bytes:
    headers = request_headers(sessdata)
    headers["Range"] = f"bytes={max(0, start)}-" + ("" if end is None else str(max(start, end)))
    return http_get(url, headers, timeout)


def http_json(url: str, headers: dict[str, str] | None = None, timeout: int = 20) -> dict[str, Any]:
    return json.loads(http_get(url, headers, timeout).decode("utf-8"))


def get_wbi_key() -> str:
    cache_file = Path.home() / ".cache" / "bili_wbi_key.txt"
    if cache_file.exists():
        try:
            key, expire = cache_file.read_text(encoding="utf-8").strip().split("|", 1)
            if float(expire) > time.time():
                return key
        except Exception:
            pass

    body = http_json("https://api.bilibili.com/x/web-interface/nav")
    wbi = body["data"]["wbi_img"]

    def filename(value: str) -> str:
        name = value.rsplit("/", 1)[-1]
        return name.rsplit(".", 1)[0]

    mixed = filename(wbi["img_url"]) + filename(wbi["sub_url"])
    key = "".join(mixed[i] for i in MIXIN_KEY_ENC_TAB if i < len(mixed))[:32]
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(f"{key}|{time.time() + 1800}", encoding="utf-8")
    return key


def build_query(params: dict[str, Any]) -> str:
    return "&".join(f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in params.items())


def sign_params(params: dict[str, Any]) -> dict[str, Any]:
    signed = dict(params)
    signed["wts"] = int(time.time())
    sorted_items = sorted(signed.items(), key=lambda kv: kv[0])
    query = "&".join(f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in sorted_items)
    signed["w_rid"] = hashlib.md5((query + get_wbi_key()).encode("utf-8")).hexdigest()
    return signed


def parse_video_id(raw: str) -> VideoId:
    raw = raw.strip()
    m = re.search(r"(?i)(BV[0-9A-Za-z]{10})", raw)
    if m:
        return VideoId("bvid", m.group(1))
    m = re.search(r"(?i)av(\d+)", raw)
    if m:
        return VideoId("aid", m.group(1))
    raise ValueError(f"无法解析 BV/AV: {raw}")


def auto_sessdata() -> str:
    explicit = os.environ.get("BILI_SESSDATA", "").strip()
    if explicit:
        return explicit
    roots = [Path.cwd(), Path(__file__).resolve().parents[1]]
    for root in roots:
        config = root / "run" / "config" / "net_music_can_play_bili.json"
        if config.exists():
            try:
                value = json.loads(config.read_text(encoding="utf-8")).get("sessdata", "").strip()
                if value:
                    print(f"[auth] using SESSDATA from {config}")
                    return value
            except Exception:
                pass
    return ""


def request_headers(sessdata: str = "") -> dict[str, str]:
    headers = {
        "Referer": "https://www.bilibili.com/",
        "Origin": "https://www.bilibili.com",
    }
    if sessdata:
        headers["Cookie"] = f"SESSDATA={sessdata}"
    return headers


def get_view(video_id: VideoId, page: int) -> dict[str, Any]:
    params = sign_params({video_id.kind: video_id.value})
    url = "https://api.bilibili.com/x/web-interface/view?" + build_query(params)
    body = http_json(url, request_headers())
    if body.get("code") != 0:
        raise RuntimeError(f"view API code={body.get('code')}: {body.get('message')}")
    data = body["data"]
    pages = data.get("pages") or []
    if not pages:
        return {
            "aid": data.get("aid"),
            "cid": data["cid"],
            "title": data.get("title", ""),
            "part": "",
            "duration": data.get("duration", 0),
            "page": 1,
            "total_pages": 1,
        }
    if page < 1 or page > len(pages):
        raise ValueError(f"该视频只有 {len(pages)} 个分P，请求 P{page}")
    p = pages[page - 1]
    return {
        "aid": data.get("aid"),
        "cid": p["cid"],
        "title": data.get("title", ""),
        "part": p.get("part", ""),
        "duration": p.get("duration", data.get("duration", 0)),
        "page": page,
        "total_pages": len(pages),
    }


def get_view_raw(video_id: VideoId) -> dict[str, Any]:
    params = sign_params({video_id.kind: video_id.value})
    url = "https://api.bilibili.com/x/web-interface/view?" + build_query(params)
    body = http_json(url, request_headers())
    if body.get("code") != 0:
        raise RuntimeError(f"view API code={body.get('code')}: {body.get('message')}")
    return body.get("data") or {}


def get_playurl(video_id: VideoId, cid: int, sessdata: str, quality: int) -> dict[str, Any]:
    params = {
        video_id.playurl_key(): video_id.value,
        "cid": cid,
        "qn": quality,
        "fnval": 4048,
        "fnver": 0,
        "fourk": 1,
        "high_quality": 1,
        "platform": "pc",
    }
    url = "https://api.bilibili.com/x/player/wbi/playurl?" + build_query(sign_params(params))
    body = http_json(url, request_headers(sessdata))
    if body.get("code") != 0:
        raise RuntimeError(f"playurl API code={body.get('code')}: {body.get('message')}")
    return body["data"]


def safe_get(obj: dict[str, Any], key: str, default: Any = None) -> Any:
    value = obj.get(key, default)
    return default if value is None else value


def stream_duration_ms(stream: dict[str, Any]) -> int | None:
    # Bilibili DASH entries may contain SegmentBase with indexRange/initRange but usually no explicit duration.
    # Some API variants expose duration/base_url_backup metadata; keep this generic for diagnostics.
    for key in ("duration", "duration_ms", "timelength", "length"):
        value = stream.get(key)
        if isinstance(value, (int, float)) and value > 0:
            # Heuristic: duration is usually seconds if small, ms if large.
            return int(round(value * 1000 if value < 100000 else value))
    return None


def parse_byte_range(value: str | None) -> tuple[int, int] | None:
    if not value:
        return None
    m = re.match(r"\s*(\d+)\s*-\s*(\d+)\s*$", str(value))
    if not m:
        return None
    return int(m.group(1)), int(m.group(2))


def u32(data: bytes, off: int) -> int:
    return int.from_bytes(data[off:off + 4], "big", signed=False)


def i32(data: bytes, off: int) -> int:
    return int.from_bytes(data[off:off + 4], "big", signed=True)


def u64(data: bytes, off: int) -> int:
    return int.from_bytes(data[off:off + 8], "big", signed=False)


def iter_boxes(data: bytes, start: int = 0, end: int | None = None):
    limit = len(data) if end is None else min(len(data), end)
    pos = max(0, start)
    while pos + 8 <= limit:
        size = u32(data, pos)
        box_type = data[pos + 4:pos + 8].decode("latin1", errors="replace")
        header = 8
        if size == 1:
            if pos + 16 > limit:
                break
            size = u64(data, pos + 8)
            header = 16
        elif size == 0:
            size = limit - pos
        if size < header or pos + size > limit:
            break
        yield pos, int(size), header, box_type, pos + header, pos + int(size)
        pos += int(size)


def find_child(data: bytes, parent_payload_start: int, parent_payload_end: int, wanted: str):
    for box in iter_boxes(data, parent_payload_start, parent_payload_end):
        if box[3] == wanted:
            return box
    return None


def parse_moov_video_timescale(init_bytes: bytes) -> int | None:
    for moov in iter_boxes(init_bytes):
        if moov[3] != "moov":
            continue
        for trak in iter_boxes(init_bytes, moov[4], moov[5]):
            if trak[3] != "trak":
                continue
            mdia = find_child(init_bytes, trak[4], trak[5], "mdia")
            if not mdia:
                continue
            hdlr = find_child(init_bytes, mdia[4], mdia[5], "hdlr")
            mdhd = find_child(init_bytes, mdia[4], mdia[5], "mdhd")
            if not hdlr or not mdhd:
                continue
            payload = init_bytes[hdlr[4]:hdlr[5]]
            handler = payload[8:12].decode("latin1", errors="replace") if len(payload) >= 12 else ""
            if handler != "vide":
                continue
            mdhd_payload = init_bytes[mdhd[4]:mdhd[5]]
            if len(mdhd_payload) < 20:
                continue
            version = mdhd_payload[0]
            if version == 1 and len(mdhd_payload) >= 32:
                return u32(mdhd_payload, 20)
            return u32(mdhd_payload, 12)
    return None


def parse_moov_track_timescale(init_bytes: bytes, handler_type: str) -> int | None:
    for moov in iter_boxes(init_bytes):
        if moov[3] != "moov":
            continue
        for trak in iter_boxes(init_bytes, moov[4], moov[5]):
            if trak[3] != "trak":
                continue
            mdia = find_child(init_bytes, trak[4], trak[5], "mdia")
            if not mdia:
                continue
            hdlr = find_child(init_bytes, mdia[4], mdia[5], "hdlr")
            mdhd = find_child(init_bytes, mdia[4], mdia[5], "mdhd")
            if not hdlr or not mdhd:
                continue
            payload = init_bytes[hdlr[4]:hdlr[5]]
            handler = payload[8:12].decode("latin1", errors="replace") if len(payload) >= 12 else ""
            if handler != handler_type:
                continue
            mdhd_payload = init_bytes[mdhd[4]:mdhd[5]]
            if len(mdhd_payload) < 20:
                continue
            version = mdhd_payload[0]
            if version == 1 and len(mdhd_payload) >= 32:
                return u32(mdhd_payload, 20)
            return u32(mdhd_payload, 12)
    return None


def parse_sidx(data: bytes, absolute_start: int) -> tuple[int, list[SidxEntry]] | None:
    # Data is expected to start at a sidx box, or contain one.
    sidx_box = None
    for box in iter_boxes(data):
        if box[3] == "sidx":
            sidx_box = box
            break
    if not sidx_box:
        return None
    box_off, box_size, _header, _typ, payload_start, payload_end = sidx_box
    payload = data[payload_start:payload_end]
    if len(payload) < 28:
        return None
    version = payload[0]
    pos = 4
    _reference_id = u32(payload, pos)
    pos += 4
    timescale = u32(payload, pos)
    pos += 4
    if version == 0:
        earliest = u32(payload, pos)
        first_offset = u32(payload, pos + 4)
        pos += 8
    else:
        earliest = u64(payload, pos)
        first_offset = u64(payload, pos + 8)
        pos += 16
    pos += 2  # reserved
    if pos + 2 > len(payload) or timescale <= 0:
        return None
    count = int.from_bytes(payload[pos:pos + 2], "big")
    pos += 2
    first_subsegment = absolute_start + box_off + box_size + int(first_offset)
    current_byte = first_subsegment
    current_time = earliest
    entries: list[SidxEntry] = []
    for idx in range(count):
        if pos + 12 > len(payload):
            break
        ref = u32(payload, pos)
        duration = u32(payload, pos + 4)
        pos += 12
        ref_type = (ref >> 31) & 1
        size = ref & 0x7FFFFFFF
        if ref_type != 0:
            current_time += duration
            current_byte += size
            continue
        time_ms = current_time * 1000.0 / timescale
        duration_ms = duration * 1000.0 / timescale
        entries.append(SidxEntry(idx, time_ms, duration_ms, current_byte, current_byte + size - 1, size))
        current_time += duration
        current_byte += size
    return timescale, entries


def parse_moof_timing(data: bytes, timescale: int, fps: int) -> MoofTiming | None:
    moof = None
    for box in iter_boxes(data):
        if box[3] == "moof":
            moof = box
            break
    if not moof:
        return None
    base_decode_time: int | None = None
    sample_count = 0
    durations: list[int] = []
    comp_offsets: list[int] = []
    default_duration = max(1, round(max(1, timescale) / max(1, fps)))
    for traf in iter_boxes(data, moof[4], moof[5]):
        if traf[3] != "traf":
            continue
        traf_default_duration = default_duration
        for child in iter_boxes(data, traf[4], traf[5]):
            payload = data[child[4]:child[5]]
            if child[3] == "tfhd" and len(payload) >= 8:
                flags = (payload[1] << 16) | (payload[2] << 8) | payload[3]
                p = 8
                if flags & 0x000001:
                    p += 8
                if flags & 0x000002:
                    p += 4
                if flags & 0x000008 and p + 4 <= len(payload):
                    traf_default_duration = u32(payload, p)
                    p += 4
            elif child[3] == "tfdt" and len(payload) >= 8:
                version = payload[0]
                base_decode_time = u64(payload, 4) if version == 1 and len(payload) >= 12 else u32(payload, 4)
            elif child[3] == "trun" and len(payload) >= 8:
                version = payload[0]
                flags = (payload[1] << 16) | (payload[2] << 8) | payload[3]
                count = u32(payload, 4)
                p = 8
                if flags & 0x000001:
                    p += 4
                if flags & 0x000004:
                    p += 4
                has_duration = bool(flags & 0x000100)
                has_size = bool(flags & 0x000200)
                has_flags = bool(flags & 0x000400)
                has_cto = bool(flags & 0x000800)
                sample_count += count
                for _ in range(count):
                    dur = traf_default_duration
                    cto = 0
                    if has_duration and p + 4 <= len(payload):
                        dur = u32(payload, p)
                        p += 4
                    if has_size:
                        p += 4
                    if has_flags:
                        p += 4
                    if has_cto and p + 4 <= len(payload):
                        cto = i32(payload, p) if version == 1 else u32(payload, p)
                        p += 4
                    durations.append(dur)
                    comp_offsets.append(cto)
    if base_decode_time is None:
        return MoofTiming(None, sample_count, None, None, None)
    scale = max(1, timescale)
    decode = base_decode_time
    pts: list[float] = []
    for i in range(sample_count):
        dur = durations[i] if i < len(durations) and durations[i] > 0 else default_duration
        cto = comp_offsets[i] if i < len(comp_offsets) else 0
        pts.append(max(0, decode + cto) * 1000.0 / scale)
        decode += dur
    first_duration_ms = (durations[0] if durations else default_duration) * 1000.0 / scale
    return MoofTiming(base_decode_time, sample_count, min(pts) if pts else None, max(pts) if pts else None,
                      first_duration_ms)


def probe_segment_base(stream: dict[str, Any], sessdata: str, target_seconds: float, fallback_timescale: int,
                       fps: int, label: str, handler_type: str) -> SegmentProbe:
    url = stream.get("baseUrl") or stream.get("base_url")
    seg = stream.get("SegmentBase") or stream.get("segment_base") or {}
    init_range = parse_byte_range(seg.get("Initialization"))
    index_range = parse_byte_range(seg.get("indexRange"))
    if not url or not init_range or not index_range:
        return SegmentProbe(label, None, None, None, None)
    init = http_range(url, init_range[0], init_range[1], sessdata)
    timescale = parse_moov_track_timescale(init, handler_type) or fallback_timescale
    index = http_range(url, index_range[0], index_range[1], sessdata)
    parsed = parse_sidx(index, index_range[0])
    if not parsed:
        return SegmentProbe(label, timescale, None, None, None)
    sidx_timescale, entries = parsed
    if not entries:
        return SegmentProbe(label, timescale, sidx_timescale, None, None)
    target_ms = target_seconds * 1000.0
    chosen = max((e for e in entries if e.time_ms <= target_ms + 50.0), key=lambda e: e.time_ms, default=entries[0])
    moof_bytes = http_range(url, chosen.byte_start, min(chosen.byte_start + 1024 * 1024 - 1, chosen.byte_end), sessdata)
    timing = parse_moof_timing(moof_bytes, timescale, fps)
    return SegmentProbe(label, timescale, sidx_timescale, chosen, timing)


def print_segment_probe(probe: SegmentProbe, target_seconds: float) -> None:
    print(f"  [{probe.label}]")
    if probe.entry is None:
        print("    no usable SegmentBase/sidx")
        return
    entry = probe.entry
    timing = probe.timing
    target_ms = target_seconds * 1000.0
    print(f"    timescale={probe.timescale} sidxTimescale={probe.sidx_timescale} entry#{entry.index} "
          f"time={entry.time_ms:.3f}ms dur={entry.duration_ms:.3f}ms bytes={entry.byte_start}-{entry.byte_end}")
    if timing:
        print(f"    moof tfdt={timing.base_decode_time} samples={timing.sample_count} "
              f"firstPts={fmt_ms(timing.first_pts_ms)} lastPts={fmt_ms(timing.last_pts_ms)} "
              f"sampleDur≈{fmt_ms(timing.first_duration_ms)}")
        if timing.first_pts_ms is not None:
            print(f"    residual target-firstPts={target_ms - timing.first_pts_ms:.3f}ms; "
                  f"target-entryTime={target_ms - entry.time_ms:.3f}ms")


def analyze_segment_base(video: dict[str, Any], sessdata: str, target_seconds: float, fps: int) -> None:
    print("\n[fMP4 SegmentBase / Java seek probe]")
    probe = probe_segment_base(video, sessdata, target_seconds, 16000, fps, "video", "vide")
    print_segment_probe(probe, target_seconds)
    print("  note: Java should prefer SegmentBase/sidx exact fragment offsets over elapsed/total byte guessing.")


def get_player_v2(video_id: VideoId, aid: int, cid: int, sessdata: str) -> dict[str, Any]:
    params = {"aid": aid, "cid": cid, video_id.playurl_key(): video_id.value}
    url = "https://api.bilibili.com/x/player/wbi/v2?" + build_query(sign_params(params))
    body = http_json(url, request_headers(sessdata))
    if body.get("code") != 0:
        raise RuntimeError(f"player v2 API code={body.get('code')}: {body.get('message')}")
    return body.get("data") or {}


def subtitle_infos_from_player(data: dict[str, Any]) -> list[SubtitleInfo]:
    subtitle = data.get("subtitle") or {}
    arr = subtitle.get("subtitles") or []
    result: list[SubtitleInfo] = []
    for item in arr:
        url = item.get("subtitle_url") or ""
        if url:
            result.append(SubtitleInfo(str(item.get("lan") or "unknown"), normalize_subtitle_url(url)))
    return result


def subtitle_infos_from_view(view_data: dict[str, Any]) -> list[SubtitleInfo]:
    subtitle = view_data.get("subtitle") or {}
    arr = subtitle.get("list") or []
    result: list[SubtitleInfo] = []
    for item in arr:
        url = item.get("subtitle_url") or ""
        if url:
            result.append(SubtitleInfo(str(item.get("lan") or "unknown"), normalize_subtitle_url(url)))
    return result


def normalize_subtitle_url(url: str) -> str:
    return "https:" + url if url.startswith("//") else url


def choose_subtitle(subtitles: list[SubtitleInfo]) -> SubtitleInfo | None:
    if not subtitles:
        return None
    def score(sub: SubtitleInfo) -> int:
        lan = sub.lan.lower()
        if lan in ("zh-cn", "zh", "zh-hans", "ai-zh") or "zh" in lan:
            return 0
        if "en" in lan:
            return 1
        return 2
    return sorted(subtitles, key=score)[0]


def fetch_subtitle_lines(info: SubtitleInfo, sessdata: str) -> list[SubtitleLine]:
    body = http_json(info.url, request_headers(sessdata), timeout=20)
    lines: list[SubtitleLine] = []
    for item in body.get("body") or []:
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        start = parse_float(item.get("from")) or 0.0
        end = parse_float(item.get("to")) or start
        lines.append(SubtitleLine(start * 1000.0, end * 1000.0, content))
    return lines


def nearest_subtitle(lines: list[SubtitleLine], target_seconds: float) -> tuple[SubtitleLine | None, SubtitleLine | None, SubtitleLine | None]:
    target_ms = target_seconds * 1000.0
    prev_line = None
    active = None
    next_line = None
    for line in lines:
        if line.start_ms <= target_ms:
            prev_line = line
        if line.start_ms <= target_ms <= line.end_ms and active is None:
            active = line
        if line.start_ms > target_ms:
            next_line = line
            break
    return prev_line, active, next_line


def print_subtitle_probe(lines: list[SubtitleLine], target_seconds: float) -> None:
    print("  [subtitle]")
    if not lines:
        print("    no subtitle lines")
        return
    target_ms = target_seconds * 1000.0
    prev_line, active, next_line = nearest_subtitle(lines, target_seconds)
    print(f"    lines={len(lines)} first={lines[0].start_ms:.3f}ms last={lines[-1].start_ms:.3f}ms target={target_ms:.3f}ms")
    if active:
        print(f"    active {active.start_ms:.3f}-{active.end_ms:.3f}ms driftStart={target_ms - active.start_ms:.3f}ms text={active.content[:80]}")
    elif prev_line:
        print(f"    previous {prev_line.start_ms:.3f}-{prev_line.end_ms:.3f}ms target-prevStart={target_ms - prev_line.start_ms:.3f}ms text={prev_line.content[:80]}")
    if next_line:
        print(f"    next {next_line.start_ms:.3f}-{next_line.end_ms:.3f}ms next-target={next_line.start_ms - target_ms:.3f}ms text={next_line.content[:80]}")


def analyze_triad(video: dict[str, Any], audio: dict[str, Any] | None, subtitles: list[SubtitleLine], sessdata: str,
                  target_seconds: float, fps: int) -> None:
    print(f"\n[triad timeline probe @ {target_seconds:.3f}s]")
    video_probe = probe_segment_base(video, sessdata, target_seconds, 16000, fps, "video", "vide")
    print_segment_probe(video_probe, target_seconds)
    if audio:
        audio_probe = probe_segment_base(audio, sessdata, target_seconds, 48000, 50, "audio", "soun")
        print_segment_probe(audio_probe, target_seconds)
    else:
        print("  [audio]\n    no selected audio")
    print_subtitle_probe(subtitles, target_seconds)

    target_ms = target_seconds * 1000.0
    video_pts = video_probe.timing.first_pts_ms if video_probe.timing else None
    audio_pts = audio_probe.timing.first_pts_ms if audio and audio_probe.timing else None
    prev_line, active, next_line = nearest_subtitle(subtitles, target_seconds)
    sub_ms = active.start_ms if active else (prev_line.start_ms if prev_line else (next_line.start_ms if next_line else None))
    print("  [combined drift to target]")
    print(f"    videoFirstPts-target={fmt_delta(video_pts, target_ms)} audioFirstPts-target={fmt_delta(audio_pts, target_ms)} subtitleAnchor-target={fmt_delta(sub_ms, target_ms)}")


def fmt_delta(value_ms: float | None, target_ms: float) -> str:
    return "n/a" if value_ms is None else f"{value_ms - target_ms:+.3f}ms"


def quality_name(qid: int) -> str:
    return QUALITY_NAMES.get(qid, "unknown")


def summarize_dash(data: dict[str, Any]) -> None:
    dash = data.get("dash") or {}
    print("\n[DASH summary]")
    print(f"  API timelength: {data.get('timelength')} ms")
    print(f"  accept_quality: {data.get('accept_quality')}")
    print(f"  accept_description: {data.get('accept_description')}")
    print(f"  dash.duration: {dash.get('duration')} s")
    print(f"  minBufferTime: {dash.get('minBufferTime')}")

    videos = dash.get("video") or []
    audios = list(dash.get("audio") or [])
    dolby = dash.get("dolby") or {}
    audios.extend(dolby.get("audio") or [])
    flac = dash.get("flac") or {}
    if isinstance(flac.get("audio"), dict):
        audios.append(flac["audio"])

    print(f"\n  video streams: {len(videos)}")
    for i, v in enumerate(videos):
        seg = v.get("SegmentBase") or v.get("segment_base") or {}
        print(
            "    #{:02d} id={}({}) codecId={} codecs={} {}x{} fps={} bandwidth={} duration={}ms host={} init={} index={}".format(
                i,
                v.get("id"), quality_name(int(v.get("id", 0) or 0)),
                v.get("codecid"), v.get("codecs"), v.get("width"), v.get("height"),
                v.get("frameRate"), v.get("bandwidth"), stream_duration_ms(v), host_of(v.get("baseUrl", "")),
                seg.get("Initialization"), seg.get("indexRange"),
            )
        )

    print(f"\n  audio streams: {len(audios)}")
    for i, a in enumerate(audios):
        print(
            "    #{:02d} id={}({}) codecs={} bandwidth={} duration={}ms host={}".format(
                i, a.get("id"), quality_name(int(a.get("id", 0) or 0)), a.get("codecs"),
                a.get("bandwidth"), stream_duration_ms(a), host_of(a.get("baseUrl", "")),
            )
        )


def host_of(url: str) -> str:
    try:
        return urllib.parse.urlparse(url).hostname or "unknown"
    except Exception:
        return "unknown"


def choose_video(data: dict[str, Any], quality: int, codec_id: int = 7) -> dict[str, Any]:
    videos = list((data.get("dash") or {}).get("video") or [])
    if not videos:
        raise RuntimeError("DASH has no video streams")

    def q(v: dict[str, Any]) -> int:
        return int(v.get("id", 0) or 0)

    def c(v: dict[str, Any]) -> int:
        return int(v.get("codecid", 0) or 0)

    candidates = [v for v in videos if q(v) == quality and (codec_id <= 0 or c(v) == codec_id)]
    if candidates:
        return candidates[0]
    candidates = [v for v in videos if q(v) <= quality and (codec_id <= 0 or c(v) == codec_id)]
    if candidates:
        return max(candidates, key=q)
    candidates = [v for v in videos if q(v) <= quality]
    if candidates:
        return max(candidates, key=q)
    return min(videos, key=q)


def choose_audio(data: dict[str, Any]) -> dict[str, Any] | None:
    dash = data.get("dash") or {}
    audios: dict[int, dict[str, Any]] = {}
    for a in dash.get("audio") or []:
        if isinstance(a, dict) and a.get("id") is not None:
            audios[int(a["id"])] = a
    dolby = dash.get("dolby") or {}
    for a in dolby.get("audio") or []:
        if isinstance(a, dict) and a.get("id") is not None:
            audios[int(a["id"])] = a
    flac = dash.get("flac") or {}
    if isinstance(flac.get("audio"), dict) and flac["audio"].get("id") is not None:
        audios[int(flac["audio"]["id"])] = flac["audio"]
    for qid in (30280, 30250, 30251, 30232, 30216):
        if qid in audios:
            return audios[qid]
    return next(iter(audios.values()), None)


def find_ffprobe(explicit: str = "") -> str | None:
    if explicit:
        return explicit
    return shutil.which("ffprobe")


def run_ffprobe_json(ffprobe: str, url: str, sessdata: str, entries: str, extra: list[str] | None = None) -> dict[str, Any]:
    headers = "User-Agent: " + USER_AGENT + "\r\nReferer: https://www.bilibili.com/\r\nOrigin: https://www.bilibili.com\r\n"
    if sessdata:
        headers += "Cookie: SESSDATA=" + sessdata + "\r\n"
    cmd = [
        ffprobe,
        "-v", "error",
        "-headers", headers,
        "-print_format", "json",
        "-show_entries", entries,
    ]
    if extra:
        cmd.extend(extra)
    cmd.append(url)
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"ffprobe failed ({proc.returncode}): {proc.stderr.strip()}")
    return json.loads(proc.stdout or "{}")


def ffprobe_format(ffprobe: str, label: str, stream: dict[str, Any], sessdata: str) -> None:
    url = stream.get("baseUrl") or stream.get("base_url")
    if not url:
        return
    print(f"\n[ffprobe {label}]")
    try:
        data = run_ffprobe_json(
            ffprobe,
            url,
            sessdata,
            "format=duration,size,bit_rate:stream=index,codec_type,codec_name,codec_time_base,time_base,start_time,duration,nb_frames,r_frame_rate,avg_frame_rate,width,height,sample_rate,channels",
        )
    except Exception as exc:
        print(f"  ffprobe failed: {exc}")
        return
    print(json.dumps(data, ensure_ascii=False, indent=2))


def parse_float(value: Any) -> float | None:
    if value is None or value == "N/A":
        return None
    try:
        return float(value)
    except Exception:
        return None


def ffprobe_frames(ffprobe: str, video: dict[str, Any], sessdata: str, start: float | None, duration: float | None,
                   max_frames: int) -> list[FrameSample]:
    url = video.get("baseUrl") or video.get("base_url")
    if not url:
        raise RuntimeError("selected video has no URL")
    extra = ["-select_streams", "v:0", "-show_frames"]
    if max_frames > 0:
        extra = ["-read_intervals", f"%+#{max_frames}"] + extra
    if start is not None or duration is not None:
        s = 0.0 if start is None else max(0.0, start)
        if duration is None:
            interval = f"{s}%"
        else:
            interval = f"{s}%+{max(0.001, duration)}"
        extra = ["-read_intervals", interval, "-select_streams", "v:0", "-show_frames"]
    data = run_ffprobe_json(
        ffprobe,
        url,
        sessdata,
        "frame=media_type,key_frame,pkt_pts_time,pts_time,best_effort_timestamp_time,pkt_dts_time,pkt_duration_time",
        extra,
    )
    samples: list[FrameSample] = []
    for frame in data.get("frames", []):
        if frame.get("media_type") != "video":
            continue
        pts_s = parse_float(frame.get("pts_time") or frame.get("pkt_pts_time") or frame.get("best_effort_timestamp_time"))
        if pts_s is None:
            continue
        idx = len(samples)
        samples.append(FrameSample(
            index=idx,
            pts_ms=pts_s * 1000.0,
            best_effort_ms=(parse_float(frame.get("best_effort_timestamp_time")) or math.nan) * 1000.0
                if parse_float(frame.get("best_effort_timestamp_time")) is not None else None,
            pkt_dts_ms=(parse_float(frame.get("pkt_dts_time")) or math.nan) * 1000.0
                if parse_float(frame.get("pkt_dts_time")) is not None else None,
            duration_ms=(parse_float(frame.get("pkt_duration_time")) or math.nan) * 1000.0
                if parse_float(frame.get("pkt_duration_time")) is not None else None,
            key_frame=str(frame.get("key_frame", "0")) == "1",
        ))
    return samples


def print_frame_stats(frames: list[FrameSample]) -> None:
    print("\n[frame PTS sample]")
    if not frames:
        print("  no frames sampled")
        return
    pts = [f.pts_ms for f in frames]
    deltas = [b - a for a, b in zip(pts, pts[1:])]
    backward = sum(1 for d in deltas if d < -0.001)
    zero_or_dup = sum(1 for d in deltas if abs(d) <= 0.001)
    print(f"  frames={len(frames)} first={pts[0]:.3f}ms last={pts[-1]:.3f}ms span={pts[-1]-pts[0]:.3f}ms")
    if deltas:
        print(f"  delta min/avg/max={min(deltas):.3f}/{sum(deltas)/len(deltas):.3f}/{max(deltas):.3f}ms backward={backward} duplicate={zero_or_dup}")
    print("  first 12 frames:")
    for f in frames[:12]:
        print(f"    #{f.index:04d} pts={f.pts_ms:.3f}ms best={fmt_ms(f.best_effort_ms)} dts={fmt_ms(f.pkt_dts_ms)} dur={fmt_ms(f.duration_ms)} key={f.key_frame}")
    if len(frames) > 12:
        print("  last 12 frames:")
        for f in frames[-12:]:
            print(f"    #{f.index:04d} pts={f.pts_ms:.3f}ms best={fmt_ms(f.best_effort_ms)} dts={fmt_ms(f.pkt_dts_ms)} dur={fmt_ms(f.duration_ms)} key={f.key_frame}")


def fmt_ms(value: float | None) -> str:
    return "n/a" if value is None or math.isnan(value) else f"{value:.3f}ms"


def simulate_java_queue(frames: list[FrameSample], fps: int, queue_capacity: int, early_ms: float, render_fps: int) -> None:
    print("\n[Java queue simulation]")
    if not frames:
        print("  no frames sampled")
        return
    frame_interval = 1000.0 / max(1, fps)
    render_interval = 1000.0 / max(1, render_fps)
    early = max(early_ms, frame_interval / 2.0)
    queue: list[FrameSample] = []
    produced = 0
    uploads = 0
    dry = 0
    blocked_old_fifo = 0
    dropped = 0
    playback = frames[0].pts_ms
    end = frames[-1].pts_ms
    while playback <= end and produced < len(frames):
        while produced < len(frames) and len(queue) < queue_capacity and frames[produced].pts_ms <= playback + 1000.0:
            queue.append(frames[produced])
            produced += 1
        visible_until = playback + early
        eligible = [f for f in queue if f.pts_ms <= visible_until]
        if eligible:
            best = max(eligible, key=lambda f: f.pts_ms)
            before = len(queue)
            queue = [f for f in queue if f.pts_ms > visible_until]
            dropped += before - len(queue) - 1
            uploads += 1
        else:
            dry += 1
            if queue and queue[0].pts_ms > visible_until and any(f.pts_ms <= visible_until for f in queue[1:]):
                blocked_old_fifo += 1
        playback += render_interval
    print(f"  fps={fps} renderFps={render_fps} capacity={queue_capacity} early={early:.3f}ms")
    print(f"  uploads={uploads} dryTicks={dry} droppedOldFrames={dropped} oldFifoHeadBlocks={blocked_old_fifo}")


def analyze_sync_parameters(config: SyncScanConfig) -> None:
    """Scan the minimal media-phase parameters needed to align outputs.

    Sign convention used here matches the GUI and the intended Java knobs:
    - positive video_delay_ms means display an older video frame, so video is delayed
    - positive subtitle_delay_ms means lookup older subtitle time, so subtitle is delayed
    - positive audio_lead_ms means seek/feed audio ahead to compensate output latency

    The important distinction is whether the local timeline is still the server clock, or is
    re-anchored to measured audio-consumed time. If the local timeline is audio-consumed,
    video/subtitle usually need little or no fixed compensation; if it is server time, video
    and subtitle must be delayed by roughly the measured audio output latency.
    """
    print("\n[sync parameter scan]")
    print(f"  measured audio main latency   : {config.audio_latency_ms:.1f}ms")
    print(f"  measured speaker relay latency: {config.speaker_latency_ms:.1f}ms")
    print(f"  measured video startup latency: {config.video_latency_ms:.1f}ms (first queue→submit; not a steady-state phase knob)")
    print(f"  subtitle model latency        : {config.subtitle_latency_ms:.1f}ms")
    print(f"  acceptable drift tolerance    : ±{config.tolerance_ms:.1f}ms")
    print(f"  local timeline model          : {config.local_timeline}")

    if config.local_timeline == "audio":
        # Local time is the audible/consumed media time. Everything should target this directly.
        baseline_audio_drift = 0.0
        baseline_video_drift = 0.0
        baseline_subtitle_drift = 0.0
        note = "local timeline already follows audio consumed time"
    else:
        # Local time is still server/global media time. Compare video/subtitle against audible audio,
        # not against the server clock; otherwise the scan would incorrectly report that audio itself
        # fails just because OpenAL has a real output buffer.
        baseline_audio_drift = 0.0
        baseline_video_drift = config.audio_latency_ms
        baseline_subtitle_drift = config.audio_latency_ms
        note = "local timeline is server time; video/subtitle must be delayed to match audible audio"

    print(f"  model note                    : {note}")

    candidates: list[tuple[int, int, int, float, float, float, float]] = []
    for video_delay in range(config.scan_min_ms, config.scan_max_ms + 1, max(1, config.scan_step_ms)):
        video_drift = baseline_video_drift - video_delay
        for subtitle_delay in range(config.scan_min_ms, config.scan_max_ms + 1, max(1, config.scan_step_ms)):
            subtitle_drift = baseline_subtitle_drift - subtitle_delay
            audio_drift = baseline_audio_drift
            max_abs = max(abs(video_drift), abs(audio_drift), abs(subtitle_drift))
            changed = int(video_delay != 0) + int(subtitle_delay != 0)
            cost = abs(video_delay) + abs(subtitle_delay)
            if max_abs <= config.tolerance_ms:
                candidates.append((changed, cost, video_delay, subtitle_delay, video_drift, audio_drift, subtitle_drift))

    if candidates:
        candidates.sort(key=lambda x: (x[0], x[1], abs(x[4]) + abs(x[5]) + abs(x[6])))
        changed, cost, video_delay, subtitle_delay, video_drift, audio_drift, subtitle_drift = candidates[0]
        print("  best candidate within tolerance:")
        print(f"    changed parameters : {changed}")
        print(f"    video_delay_ms     : {video_delay:+d}")
        print(f"    subtitle_delay_ms  : {subtitle_delay:+d}")
        print(f"    drifts after model : video={video_drift:+.1f}ms audio={audio_drift:+.1f}ms subtitle={subtitle_drift:+.1f}ms")
    else:
        print("  no candidate found within tolerance in requested scan range")

    print("\n  direct recommendations:")
    if config.local_timeline == "audio":
        print("    1) Java should first re-anchor ModernTurntableTimeline to audio consumed media time.")
        print("    2) Then keep video_delay_ms≈0 and subtitle_delay_ms≈0 unless subjective tests show subtitle art delay.")
        print("    3) Keep audio_lead_ms=0 for steady-state sync; use consumedMedia, not fedMedia, as the clock.")
    else:
        recommended = int(round(config.audio_latency_ms / max(1, config.scan_step_ms)) * config.scan_step_ms)
        print("    1) If keeping local timeline as server time, delay video/subtitle by the audio output latency.")
        print(f"    2) Recommended video_delay_ms≈{recommended} and subtitle_delay_ms≈{recommended}.")
        print("    3) This is a fallback; audio-consumed local timeline is cleaner and needs fewer knobs.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe Bilibili DASH/fMP4 timelines without launching Minecraft.")
    parser.add_argument("video", help="BV/AV id or URL")
    parser.add_argument("--page", "-p", type=int, default=2, help="page number, default P2 for the current test video")
    parser.add_argument("--quality", "-q", type=int, default=80, help="preferred video quality ceiling, Java startup default is 80")
    parser.add_argument("--codec-id", type=int, default=7, help="preferred codec id, 7=H.264 like Java default")
    parser.add_argument("--sessdata", default="", help="SESSDATA; default auto reads env/config")
    parser.add_argument("--ffprobe", default="", help="path to ffprobe; default PATH")
    parser.add_argument("--frames", action="store_true", help="sample video frame PTS with ffprobe")
    parser.add_argument("--sample-start", type=float, default=None, help="ffprobe read interval start seconds")
    parser.add_argument("--sample-duration", type=float, default=None, help="ffprobe read interval duration seconds")
    parser.add_argument("--seek-probe", type=float, default=None,
                        help="probe exact SegmentBase/sidx/moof fragment at target seconds")
    parser.add_argument("--triad-probe", type=float, action="append", default=[],
                        help="probe video+audio+subtitle timelines together at target seconds; can be repeated")
    parser.add_argument("--max-frames", type=int, default=600, help="frame sample count when --frames without interval")
    parser.add_argument("--queue-capacity", type=int, default=8)
    parser.add_argument("--early-ms", type=float, default=12.0)
    parser.add_argument("--render-fps", type=int, default=60)
    parser.add_argument("--dump-playurl", default="", help="optional path to write raw playurl JSON")
    parser.add_argument("--sync-scan", action="store_true",
                        help="scan sync knobs from measured bench latencies without changing Java")
    parser.add_argument("--sync-local-timeline", choices=("audio", "server"), default="audio",
                        help="timeline model for --sync-scan: audio=local timeline follows consumed audio; server=local timeline is server/global clock")
    parser.add_argument("--bench-audio-latency-ms", type=float, default=340.0,
                        help="measured queue-to-first-consumed / audible audio latency; latest auto bench avg ≈340ms")
    parser.add_argument("--bench-speaker-latency-ms", type=float, default=327.0,
                        help="measured speaker relay buffered latency; latest auto bench avg ≈327ms")
    parser.add_argument("--bench-video-latency-ms", type=float, default=147.0,
                        help="measured first video queue-to-submit latency; latest auto bench avg ≈147ms")
    parser.add_argument("--bench-subtitle-latency-ms", type=float, default=0.0,
                        help="subtitle lookup/render model latency; default 0 because it is timeline-driven")
    parser.add_argument("--sync-tolerance-ms", type=float, default=50.0,
                        help="acceptable A/V/subtitle drift for --sync-scan")
    parser.add_argument("--sync-scan-min-ms", type=int, default=-600,
                        help="minimum delay/advance parameter to scan")
    parser.add_argument("--sync-scan-max-ms", type=int, default=600,
                        help="maximum delay/advance parameter to scan")
    parser.add_argument("--sync-scan-step-ms", type=int, default=10,
                        help="scan step for delay/advance parameters")
    args = parser.parse_args()

    if args.sync_scan:
        analyze_sync_parameters(SyncScanConfig(
            audio_latency_ms=args.bench_audio_latency_ms,
            video_latency_ms=args.bench_video_latency_ms,
            subtitle_latency_ms=args.bench_subtitle_latency_ms,
            speaker_latency_ms=args.bench_speaker_latency_ms,
            tolerance_ms=args.sync_tolerance_ms,
            scan_min_ms=args.sync_scan_min_ms,
            scan_max_ms=args.sync_scan_max_ms,
            scan_step_ms=args.sync_scan_step_ms,
            local_timeline=args.sync_local_timeline,
        ))
        return 0

    sessdata = args.sessdata.strip() or auto_sessdata()
    video_id = parse_video_id(args.video)
    view = get_view(video_id, args.page)
    print("[view]")
    print(f"  id={video_id.kind}:{video_id.value} page={view['page']}/{view['total_pages']} cid={view['cid']}")
    print(f"  title={view['title']} - P{view['page']} {view['part']}")
    print(f"  view/page duration={view['duration']}s ({view['duration'] * 1000}ms)")

    data = get_playurl(video_id, int(view["cid"]), sessdata, args.quality)
    if args.dump_playurl:
        Path(args.dump_playurl).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[dump] wrote playurl JSON: {args.dump_playurl}")
    summarize_dash(data)

    video = choose_video(data, args.quality, args.codec_id)
    audio = choose_audio(data)
    print("\n[selected like Java]")
    print(f"  video id={video.get('id')} codecId={video.get('codecid')} codecs={video.get('codecs')} size={video.get('width')}x{video.get('height')} fps={video.get('frameRate')} host={host_of(video.get('baseUrl', ''))}")
    if audio:
        print(f"  audio id={audio.get('id')} codecs={audio.get('codecs')} bandwidth={audio.get('bandwidth')} host={host_of(audio.get('baseUrl', ''))}")

    ffprobe = find_ffprobe(args.ffprobe)
    if not ffprobe:
        print("\n[ffprobe] not found on PATH; install/add ffmpeg bin to PATH or pass --ffprobe")
        return 0
    ffprobe_format(ffprobe, "video", video, sessdata)
    if audio:
        ffprobe_format(ffprobe, "audio", audio, sessdata)

    fps_raw = str(video.get("frameRate") or "30")
    try:
        fps = int(round(float(fps_raw.split("/", 1)[0]) / float(fps_raw.split("/", 1)[1]))) if "/" in fps_raw else int(round(float(fps_raw)))
    except Exception:
        fps = 30

    if args.seek_probe is not None:
        analyze_segment_base(video, sessdata, args.seek_probe, fps)

    if args.triad_probe:
        subtitles: list[SubtitleLine] = []
        try:
            player_v2 = get_player_v2(video_id, int(view["aid"]), int(view["cid"]), sessdata)
            infos = subtitle_infos_from_player(player_v2)
            if not infos:
                view_full = get_view_raw(video_id)
                infos = subtitle_infos_from_view(view_full)
            chosen_sub = choose_subtitle(infos)
            if chosen_sub:
                print(f"\n[selected subtitle] lan={chosen_sub.lan} url={chosen_sub.url}")
                subtitles = fetch_subtitle_lines(chosen_sub, sessdata)
            else:
                print("\n[selected subtitle] none")
        except Exception as exc:
            print(f"\n[selected subtitle] failed: {exc}")
        for target in args.triad_probe:
            analyze_triad(video, audio, subtitles, sessdata, target, fps)

    if args.frames:
        frames = ffprobe_frames(ffprobe, video, sessdata, args.sample_start, args.sample_duration, args.max_frames)
        print_frame_stats(frames)
        simulate_java_queue(frames, fps, args.queue_capacity, args.early_ms, args.render_fps)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
