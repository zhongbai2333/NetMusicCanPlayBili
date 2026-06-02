#!/usr/bin/env python3
"""
B站视频流解码性能研究脚本
================================

研究目标：
1. 分析 B站 DASH 视频流的编码格式和分辨率
2. 用 FFmpeg 解码视频帧并测量性能
3. 测试字符模拟渲染（降采样 + run-length 编码）的可行性
4. 评估不同分辨率的 CPU 开销

用法:
    python bench/bili_video_research.py BV1xxx        # 分析视频流格式
    python bench/bili_video_research.py BV1xxx --decode   # 解码性能测试
    python bench/bili_video_research.py BV1xxx --char 40 22  # 文字模拟测试

依赖:
    pip install numpy pillow
    需要 ffmpeg/ffprobe 在 PATH 中
"""

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import urllib.request
import urllib.parse
from pathlib import Path

# ── B站 API 基础 ──────────────────────────────────────────────

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


def http_get(url: str, headers: dict = None, timeout: int = 15) -> bytes:
    req = urllib.request.Request(url, headers=headers or {})
    req.add_header("User-Agent", USER_AGENT)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def http_get_json(url: str, headers: dict = None, timeout: int = 15) -> dict:
    return json.loads(http_get(url, headers, timeout).decode("utf-8"))


def get_wbi_key() -> str:
    cache_file = Path.home() / ".cache" / "bili_wbi_key.txt"
    if cache_file.exists():
        try:
            cached = cache_file.read_text().strip()
            parts = cached.split("|")
            if len(parts) == 2 and float(parts[1]) > time.time():
                return parts[0]
        except Exception:
            pass

    resp = http_get_json("https://api.bilibili.com/x/web-interface/nav")
    wbi = resp["data"]["wbi_img"]

    def extract_filename(url: str) -> str:
        name = url.rsplit("/", 1)[-1]
        dot = name.rfind(".")
        return name[:dot] if dot > 0 else name

    img_key = extract_filename(wbi["img_url"])
    sub_key = extract_filename(wbi["sub_url"])
    mixed = img_key + sub_key

    key_chars = []
    for idx in MIXIN_KEY_ENC_TAB:
        if idx < len(mixed):
            key_chars.append(mixed[idx])
    wbi_key = "".join(key_chars)[:32]

    cache_file.parent.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(f"{wbi_key}|{time.time() + 30 * 60}")
    return wbi_key


def sign_params(params: dict) -> dict:
    wbi_key = get_wbi_key()
    signed = dict(params)
    signed["wts"] = str(int(time.time()))
    sorted_items = sorted(signed.items(), key=lambda x: x[0])
    query = "&".join(f"{k}={urllib.parse.quote(str(v), safe='')}"
                     for k, v in sorted_items)
    w_rid = hashlib.md5((query + wbi_key).encode()).hexdigest()
    signed["w_rid"] = w_rid
    return signed


def build_query(params: dict) -> str:
    return "&".join(
        f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in params.items()
    )


# ── DASH 视频流分析 ────────────────────────────────────────────


def analyze_video_streams(video_id: str, sessdata: str = ""):
    """分析 B站 DASH 视频流的所有可用格式。"""
    print(f"\n{'='*70}")
    print(f"  B站视频流分析: {video_id}")
    print(f"{'='*70}")

    # 1. 解析 ID
    bv_match = re.match(r"^[Bb][Vv]([0-9A-Za-z]{10})$", video_id.strip())
    av_match = re.match(r"^[Aa][Vv](\d+)$", video_id.strip())
    if bv_match:
        vid_kind, vid_val = "bvid", "BV" + bv_match.group(1)
    elif av_match:
        vid_kind, vid_val = "aid", av_match.group(1)
    else:
        raise ValueError(f"无效的 BV/AV 号: {video_id}")

    # 2. 获取视频信息
    info_params = sign_params({vid_kind: vid_val})
    info_url = "https://api.bilibili.com/x/web-interface/view?" + build_query(
        info_params
    )
    info = http_get_json(info_url)
    if info.get("code") != 0:
        raise RuntimeError(f"view API 返回 {info['code']}: {info.get('message')}")

    data = info["data"]
    pages = data.get("pages", [])
    if not pages:
        cid = data["cid"]
    else:
        cid = pages[0]["cid"]

    title = data["title"]
    duration = data["duration"]
    print(f"\n  标题: {title}")
    print(f"  时长: {duration}s ({duration // 60}:{duration % 60:02d})")
    print(f"  cid: {cid}")

    # 3. 获取 DASH 流
    play_params = {}
    if vid_kind == "bvid":
        play_params["bvid"] = vid_val
    else:
        play_params["avid"] = vid_val
    play_params["cid"] = str(cid)
    play_params["fnval"] = "4048"
    play_params["fnver"] = "0"
    play_params["fourk"] = "1"
    play_params["platform"] = "pc"

    signed = sign_params(play_params)
    play_url = "https://api.bilibili.com/x/player/wbi/playurl?" + build_query(signed)

    headers = {"Referer": "https://www.bilibili.com/"}
    if sessdata:
        headers["Cookie"] = f"SESSDATA={sessdata}"

    play_data = http_get_json(play_url, headers)
    if play_data.get("code") != 0:
        raise RuntimeError(
            f"playurl API 返回 {play_data['code']}: {play_data.get('message')}"
        )

    dash = play_data["data"]["dash"]

    # 4. 分析视频流
    video_streams = dash.get("video")
    if not isinstance(video_streams, list):
        video_streams = []
        print("\n  ⚠ 没有 DASH 视频流数据")
        return

    # B站视频质量 ID → 名称
    QUALITY_NAMES = {
        6: "240P 极速",
        16: "360P 流畅",
        32: "480P 清晰",
        64: "720P 高清",
        74: "720P60 高帧率",
        80: "1080P 高清",
        112: "1080P+ 高码率",
        116: "1080P60 高帧率",
        120: "4K 超清",
        125: "HDR 真彩",
        126: "杜比视界",
        127: "8K 超高清",
    }

    # 视频编码 ID → 名称
    CODEC_NAMES = {
        7: "AVC (H.264)",
        12: "HEVC (H.265)",
        13: "AV1",
    }

    print(f"\n  可用视频流 ({len(video_streams)} 个):")
    print(f"  {'─'*60}")
    print(f"  {'ID':<6} {'编码':<16} {'分辨率':<12} {'帧率':<8} {'码率':<10}")
    print(f"  {'─'*60}")

    for s in video_streams:
        qid = s.get("id", 0)
        codec_id = s.get("codecid", 0)
        width = s.get("width", 0)
        height = s.get("height", 0)
        fps = s.get("frameRate", s.get("frame_rate", "?"))
        bitrate = s.get("bandwidth", 0)

        qname = QUALITY_NAMES.get(qid, f"未知({qid})")
        cname = CODEC_NAMES.get(codec_id, f"未知({codec_id})")

        print(
            f"  {qid:<6} {cname:<16} {width}x{height:<7} "
            f"{str(fps):<8} {bitrate / 1000:>7.0f}kbps"
        )

    print(f"  {'─'*60}")

    # 5. 分析音频流
    audio_streams = []
    for section in ["audio", "dolby", "flac"]:
        raw = dash.get(section)
        if raw is None or (isinstance(raw, bool) and not raw):
            continue
        if section == "audio":
            arr = raw if isinstance(raw, list) else []
        elif section == "dolby":
            arr = raw.get("audio") if isinstance(raw, dict) else None
            arr = arr if isinstance(arr, list) else []
        elif section == "flac":
            audio_item = raw.get("audio") if isinstance(raw, dict) else None
            arr = [audio_item] if isinstance(audio_item, dict) else []
        else:
            continue
        if arr:
            for item in arr:
                if isinstance(item, dict) and "id" in item:
                    audio_streams.append(item)

    if audio_streams:
        print(f"\n  可用音频流 ({len(audio_streams)} 个):")
        print(f"  {'─'*60}")
        AUDIO_NAMES = {
            30216: "AAC 64k",
            30232: "AAC 132k",
            30280: "FLAC Hi-Res",
            30250: "Dolby Atmos EC-3",
            30251: "Dolby Atmos EC-3",
        }
        for a in audio_streams:
            aid = a.get("id", 0)
            aname = AUDIO_NAMES.get(aid, f"未知({aid})")
            print(f"  {aid:<8} {aname}")

    return {
        "video_streams": video_streams,
        "audio_streams": audio_streams,
        "cid": cid,
        "title": title,
        "duration": duration,
    }


# ── FFmpeg 视频解码性能测试 ────────────────────────────────────


def test_decode_performance(video_url: str, output_prefix: str = "bench_frame"):
    """用 FFmpeg 解码视频并测量性能。

    使用 subprocess 调用 ffmpeg，解码前 N 帧到 raw RGB，
    测量每帧解码耗时。
    """
    print(f"\n{'='*70}")
    print(f"  FFmpeg 视频解码性能测试")
    print(f"{'='*70}")

    # 先获取视频信息
    print("\n  获取视频信息...")
    probe_cmd = [
        "ffprobe",
        "-v", "quiet",
        "-print_format", "json",
        "-show_streams",
        "-show_format",
        video_url,
    ]
    try:
        probe_result = subprocess.run(
            probe_cmd, capture_output=True, text=True, timeout=30
        )
        probe = json.loads(probe_result.stdout)
    except Exception as e:
        print(f"  ❌ ffprobe 失败: {e}")
        print(f"  stderr: {probe_result.stderr}")
        return

    video_stream = None
    for s in probe.get("streams", []):
        if s.get("codec_type") == "video":
            video_stream = s
            break

    if not video_stream:
        print("  ❌ 未找到视频流")
        return

    codec = video_stream.get("codec_name", "?")
    width = video_stream.get("width", 0)
    height = video_stream.get("height", 0)
    pix_fmt = video_stream.get("pix_fmt", "?")
    fps_str = video_stream.get("r_frame_rate", "?")
    duration = float(probe.get("format", {}).get("duration", 0))

    print(f"\n  视频信息:")
    print(f"    编码: {codec}")
    print(f"    分辨率: {width}x{height}")
    print(f"    像素格式: {pix_fmt}")
    print(f"    帧率: {fps_str}")
    print(f"    时长: {duration:.1f}s")

    # 测试不同解码分辨率
    resolutions = [
        (width, height, "原始"),
        (854, 480, "480P"),
        (640, 360, "360P"),
        (426, 240, "240P"),
    ]

    print(f"\n  解码性能 (前 120 帧, 单线程):")
    print(f"  {'─'*55}")
    print(f"  {'分辨率':<14} {'像素':<10} {'总耗时':<10} {'每帧':<10} {'≈FPS':<8}")
    print(f"  {'─'*55}")

    for w, h, label in resolutions:
        if w > width:
            w, h = width, height

        start = time.time()
        decode_cmd = [
            "ffmpeg",
            "-v", "error",
            "-i", video_url,
            "-vf", f"fps=30,scale={w}:{h}:flags=bilinear",
            "-vframes", "120",
            "-pix_fmt", "rgba",
            "-f", "rawvideo",
            "-",
        ]

        try:
            result = subprocess.run(
                decode_cmd, capture_output=True, timeout=60
            )
            elapsed = time.time() - start
            frames = 120
            per_frame = elapsed / frames * 1000
            fps = frames / elapsed

            total_bytes = len(result.stdout)
            expected = w * h * 4 * frames
            actual_frames = total_bytes // (w * h * 4)

            print(
                f"  {w}x{h:<8} {w*h:>8}  {elapsed:>7.2f}s  "
                f"{per_frame:>6.1f}ms  {fps:>6.1f}"
            )

            # 保存一帧作为样本
            if label == "原始" and actual_frames > 0:
                import numpy as np
                from PIL import Image
                sample = np.frombuffer(
                    result.stdout[: w * h * 4], dtype=np.uint8
                ).reshape((h, w, 4))
                img = Image.fromarray(sample, "RGBA")
                img.save(f"bench/{output_prefix}_{w}x{h}.png")
                print(f"    样本帧已保存: bench/{output_prefix}_{w}x{h}.png")

        except subprocess.TimeoutExpired:
            print(f"  {w}x{h:<8} {'─':>8}  {'超时!':>9}")
        except Exception as e:
            print(f"  {w}x{h:<8} {'─':>8}  {'错误: ' + str(e)[:20]}")

    print(f"  {'─'*55}")


# ── 字符模拟渲染测试 ────────────────────────────────────────


def test_char_rendering(video_url: str, cols: int = 40, rows: int = 22):
    """测试字符模拟视频渲染。

    从视频解码帧 → 降采样到 cols×rows → run-length 压缩 →
    统计每帧需要的 submitText 调用数和数据量。
    """
    import numpy as np

    print(f"\n{'='*70}")
    print(f"  字符模拟渲染测试 ({cols}x{rows})")
    print(f"{'='*70}")

    # 解码 30 帧用于测试
    print(f"\n  正在解码 30 帧...")
    decode_cmd = [
        "ffmpeg",
        "-v", "error",
        "-i", video_url,
        "-vf", f"fps=30,scale={cols}:{rows}:flags=bilinear",
        "-vframes", "30",
        "-pix_fmt", "rgba",
        "-f", "rawvideo",
        "-",
    ]

    try:
        result = subprocess.run(decode_cmd, capture_output=True, timeout=30)
    except subprocess.TimeoutExpired:
        print("  ❌ 解码超时")
        return

    frame_size = cols * rows * 4
    total_frames = len(result.stdout) // frame_size
    if total_frames == 0:
        print(f"  ❌ 未解码到帧（期望 {frame_size}B/帧，实际 {len(result.stdout)}B）")
        return

    print(f"  解码到 {total_frames} 帧")

    # 统计 run-length 压缩效果
    segment_counts = []
    char_counts = []
    color_uniqueness = []

    for i in range(min(total_frames, 30)):
        offset = i * frame_size
        frame = np.frombuffer(
            result.stdout[offset:offset + frame_size], dtype=np.uint8
        ).reshape((rows, cols, 4))

        # 转换为 0xAARRGGBB 整数数组（MC 颜色格式）
        rgb = frame[:, :, :3]  # RGBA → RGB
        # 打包为 ARGB int（alpha 设为 FF）
        argb = (
            (frame[:, :, 3].astype(np.uint32) << 24)
            | (rgb[:, :, 0].astype(np.uint32) << 16)
            | (rgb[:, :, 1].astype(np.uint32) << 8)
            | (rgb[:, :, 2].astype(np.uint32))
        )

        total_segments = 0
        total_chars = 0

        for row_idx in range(rows):
            row_data = argb[row_idx]
            # Run-length 编码：统计颜色段数
            segments = 1
            prev_color = row_data[0]
            for col_idx in range(1, cols):
                if row_data[col_idx] != prev_color:
                    segments += 1
                    prev_color = row_data[col_idx]
            total_segments += segments
            total_chars += cols

        segment_counts.append(total_segments)
        char_counts.append(total_chars)
        color_uniqueness.append(len(np.unique(argb)))

    avg_segments = np.mean(segment_counts)
    avg_chars = np.mean(char_counts)
    avg_colors = np.mean(color_uniqueness)

    print(f"\n  渲染统计 ({cols}x{rows}, 30帧平均):")
    print(f"  {'─'*50}")
    print(f"  每帧像素数:       {cols * rows:>6}")
    print(f"  每帧颜色段数:     {avg_segments:>6.0f}  ← submitText() 调用数")
    print(f"  每帧字符数:       {avg_chars:>6.0f}")
    print(f"  每帧唯一颜色数:   {avg_colors:>6.0f}")
    print(f"  {'─'*50}")

    # 性能预估
    fps = 30
    submissions_per_sec = avg_segments * fps
    print(f"\n  性能预估 (@{fps}fps):")
    print(f"  submitText 调用/秒: {submissions_per_sec:>8.0f}")

    if submissions_per_sec < 5000:
        verdict = "✅ 非常安全，性能富余"
    elif submissions_per_sec < 15000:
        verdict = "✅ 安全，正常范围"
    elif submissions_per_sec < 30000:
        verdict = "⚠️ 临界值，建议降低分辨率或帧率"
    else:
        verdict = "❌ 过高，必须降低分辨率"

    print(f"  评估: {verdict}")

    # 压缩比
    compression_ratio = avg_segments / avg_chars * 100
    print(f"\n  压缩比: {compression_ratio:.1f}% (段数/字符数)")

    # 生成 ASCII 预览（用灰度等级）
    print(f"\n  第 0 帧 ASCII 预览 (灰度):")
    frame0 = np.frombuffer(
        result.stdout[:frame_size], dtype=np.uint8
    ).reshape((rows, cols, 4))
    gray = np.mean(frame0[:, :, :3], axis=2).astype(np.uint8)

    CHARS = " .:-=+*#%@"
    for row_idx in range(min(rows, 22)):
        line = ""
        for col_idx in range(min(cols, 80)):
            idx = gray[row_idx, col_idx] * (len(CHARS) - 1) // 255
            line += CHARS[idx]
        print(f"  {line}")

    return {
        "cols": cols,
        "rows": rows,
        "avg_segments": avg_segments,
        "submissions_per_sec": submissions_per_sec,
        "verdict": verdict,
    }


# ── 主入口 ──────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="B站视频流解码性能研究"
    )
    parser.add_argument("video_id", help="B站 BV/AV 号")
    parser.add_argument(
        "--decode", action="store_true", help="运行 FFmpeg 解码性能测试"
    )
    parser.add_argument(
        "--char", nargs=2, type=int, metavar=("COLS", "ROWS"),
        help="运行字符模拟渲染测试 (例如: --char 40 22)"
    )
    parser.add_argument(
        "--quality", type=int, default=32,
        help="视频质量 ID (默认: 32 = 480P)"
    )
    parser.add_argument(
        "--sessdata", type=str, default="",
        help="B站 SESSDATA Cookie"
    )
    args = parser.parse_args()

    # 创建 bench 目录
    Path("bench").mkdir(exist_ok=True)

    # 1. 分析视频流
    analysis = analyze_video_streams(args.video_id, args.sessdata)

    if analysis is None:
        return

    # 找到对应质量的视频流 URL
    video_streams = analysis["video_streams"]
    target_url = None
    for s in video_streams:
        if s.get("id") == args.quality:
            target_url = s.get("baseUrl") or s.get("base_url")
            # B站 CDN 可能需要 backup URL
            if not target_url and "backupUrl" in s:
                target_url = s["backupUrl"][0] if s["backupUrl"] else None
            break

    if not target_url:
        # 取最低质量
        target_url = (
            video_streams[-1].get("baseUrl")
            or video_streams[-1].get("base_url")
        )
        print(f"\n  ⚠ 未找到质量 ID {args.quality}，使用最低质量")

    if not target_url:
        print("  ❌ 无法获取视频流 URL")
        return

    print(f"\n  使用视频流: {target_url[:80]}...")

    # 2. 解码性能测试
    if args.decode:
        test_decode_performance(target_url)

    # 3. 字符模拟渲染测试
    if args.char:
        cols, rows = args.char
        # 限制最大尺寸
        cols = min(cols, 120)
        rows = min(rows, 80)
        test_char_rendering(target_url, cols, rows)

    # 默认：无额外参数时只做流分析
    if not args.decode and not args.char:
        print(f"\n  提示: 使用 --decode 测试解码性能，--char COLS ROWS 测试文字模拟")


if __name__ == "__main__":
    main()
