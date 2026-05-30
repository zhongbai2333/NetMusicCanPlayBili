#!/usr/bin/env python3
"""
B站 Dolby Atmos (E-AC-3) 音频下载工具
用于测试 NetMusicCanPlayBili 的 E-AC-3 解码器。

用法:
    python bili_download_dolby.py BV1dakuYcENi
    python bili_download_dolby.py BV1dakuYcENi --page 1
    python bili_download_dolby.py BV1dakuYcENi --output test.ec3

SESSDATA 自动检测:
  优先从游戏 run/config/net_music_can_play_bili.json 读取已登录的 SESSDATA
  也支持通过 --sessdata 或环境变量 BILI_SESSDATA 手动指定
"""

import argparse
import hashlib
import os
import re
import sys
import time
import urllib.request
import urllib.parse
import json
from pathlib import Path

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)

# Dolby E-AC-3 quality IDs (priority order)
DOLBY_QUALITY_IDS = [30250, 30251, 30280]

# WBI mixin key permutation table (from BiliWbiSigner.java)
MIXIN_KEY_ENC_TAB = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 52, 44, 34,
]


def http_get(url: str, headers: dict = None, timeout: int = 15) -> bytes:
    """简单的 HTTP GET，返回响应体。"""
    req = urllib.request.Request(url, headers=headers or {})
    req.add_header("User-Agent", USER_AGENT)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def http_get_json(url: str, headers: dict = None, timeout: int = 15) -> dict:
    """HTTP GET 并解析 JSON 响应。"""
    data = http_get(url, headers, timeout)
    return json.loads(data.decode("utf-8"))


def get_wbi_key() -> str:
    """
    获取 B站 WBI 签名密钥。
    缓存 30 分钟，和 Java 版本逻辑一致。
    """
    # 简单缓存: 写临时文件
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

    # 缓存 30 分钟
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(f"{wbi_key}|{time.time() + 30 * 60}")

    return wbi_key


def sign_params(params: dict) -> dict:
    """
    B站 WBI 签名。
    对参数排序，追加 wts 时间戳，拼接 query string 后追加 key，
    取 MD5 得到 w_rid。
    """
    wbi_key = get_wbi_key()
    signed = dict(params)
    signed["wts"] = str(int(time.time()))

    # 按 key 排序
    sorted_items = sorted(signed.items(), key=lambda x: x[0])
    query = "&".join(f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in sorted_items)

    # w_rid = MD5(query + wbi_key)
    w_rid = hashlib.md5((query + wbi_key).encode()).hexdigest()
    signed["w_rid"] = w_rid
    return signed


def build_query(params: dict) -> str:
    """构建 URL query string。"""
    return "&".join(f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in params.items())


def extract_video_id(raw: str) -> tuple:
    """解析 BV/AV 号，返回 (kind, value)。"""
    bv_match = re.match(r"^[Bb][Vv]([0-9A-Za-z]{10})$", raw.strip())
    if bv_match:
        return ("bvid", "BV" + bv_match.group(1))
    av_match = re.match(r"^[Aa][Vv](\d+)$", raw.strip())
    if av_match:
        return ("aid", av_match.group(1))
    raise ValueError(f"无效的 BV/AV 号: {raw}")


def get_video_info(video_kind: str, video_value: str, page: int = 1) -> dict:
    """获取视频信息（cid、分P 等）。"""
    params = sign_params({video_kind: video_value})
    url = "https://api.bilibili.com/x/web-interface/view?" + build_query(params)
    resp = http_get_json(url)

    if resp.get("code") != 0:
        raise RuntimeError(f"view API 返回 code={resp['code']}: {resp.get('message', 'unknown')}")

    data = resp["data"]
    pages = data.get("pages", [])
    if not pages:
        cid = data["cid"]
        title = data["title"]
        duration = data["duration"]
    else:
        if page < 1 or page > len(pages):
            raise ValueError(f"该视频只有 {len(pages)} 个分P，请求的是 P{page}")
        p = pages[page - 1]
        cid = p["cid"]
        title = data["title"]
        part = p.get("part", "")
        if part:
            title = f"{title} - P{page} {part}"
        duration = p.get("duration", data["duration"])

    owner = data.get("owner", {})
    author = owner.get("name", "unknown")

    return {
        "title": title,
        "author": author,
        "cid": cid,
        "duration": duration,
        "page": page,
        "total_pages": len(pages) if pages else 1,
    }


def get_play_url(video_kind: str, video_value: str, cid: int, sessdata: str = "") -> dict:
    """
    获取 DASH 音频流地址。
    返回 {"dolby_url": str, "quality_id": int} 或 None（无 Dolby 音质）。
    """
    params = {}
    if video_kind == "bvid":
        params["bvid"] = video_value
    else:
        params["avid"] = video_value
    params["cid"] = str(cid)
    params["fnval"] = "4048"  # 请求 Dolby + FLAC + 4K
    params["fnver"] = "0"
    params["fourk"] = "1"
    params["platform"] = "pc"

    signed = sign_params(params)
    url = "https://api.bilibili.com/x/player/wbi/playurl?" + build_query(signed)

    headers = {
        "Referer": "https://www.bilibili.com/",
    }
    if sessdata:
        headers["Cookie"] = f"SESSDATA={sessdata}"

    resp = http_get_json(url, headers)

    if resp.get("code") != 0:
        raise RuntimeError(f"playurl API 返回 code={resp['code']}: {resp.get('message', 'unknown')}")

    dash = resp["data"]["dash"]

    # 收集所有音频流
    all_streams = {}
    for section in ["audio", "dolby", "flac"]:
        if section in dash and dash[section]:
            arr = dash[section]
            if section == "dolby":
                arr = arr.get("audio", [])
            elif section == "flac":
                arr = [arr.get("audio")] if arr.get("audio") else []
            for item in arr:
                if item and "id" in item and "baseUrl" in item:
                    all_streams[item["id"]] = item["baseUrl"]

    # 按优先级选择
    for qid in DOLBY_QUALITY_IDS:
        if qid in all_streams:
            return {"url": all_streams[qid], "quality_id": qid, "urls": all_streams}

    return None


def download_ec3(url: str, output_path: str, title: str = "") -> None:
    """下载 EC-3 音频流到本地文件。"""
    print(f"⬇ 开始下载...")
    print(f"   URL: {url[:80]}...")

    headers = {
        "Referer": "https://www.bilibili.com/",
        "Origin": "https://www.bilibili.com",
    }

    req = urllib.request.Request(url, headers=headers)
    req.add_header("User-Agent", USER_AGENT)

    total = 0
    last_report = time.time()

    with urllib.request.urlopen(req, timeout=60) as resp:
        content_length = resp.headers.get("Content-Length")
        total_size = int(content_length) if content_length else None

        with open(output_path, "wb") as f:
            while True:
                chunk = resp.read(64 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                total += len(chunk)

                now = time.time()
                if now - last_report >= 1.0:
                    if total_size:
                        pct = total / total_size * 100
                        print(f"\r   已下载: {total / 1024 / 1024:.1f} MB / {total_size / 1024 / 1024:.1f} MB ({pct:.0f}%)", end="")
                    else:
                        print(f"\r   已下载: {total / 1024 / 1024:.1f} MB", end="")
                    last_report = now

    print(f"\n✅ 下载完成: {total / 1024 / 1024:.2f} MB → {output_path}")
    if title:
        print(f"   视频: {title}")


def verify_ec3(filepath: str) -> bool:
    """快速验证文件是否为有效的 E-AC-3 流（检查 0x0B77 同步字）。"""
    with open(filepath, "rb") as f:
        header = f.read(8192)

    sync_count = 0
    for i in range(len(header) - 1):
        if header[i] == 0x0B and header[i + 1] == 0x77:
            sync_count += 1

    if sync_count > 0:
        print(f"🔍 验证: 检测到 {sync_count} 个 E-AC-3 同步帧 (0x0B77)")
        return True
    else:
        print("⚠ 警告: 文件中未检测到 E-AC-3 同步字 (0x0B77)")
        print("   该文件可能不是有效的 Dolby EC-3 音频流")
        return False


def auto_detect_sessdata() -> str:
    """
    自动从游戏 run 目录或当前项目目录查找 NetMusicCanPlayBili 配置文件，
    提取已登录的 SESSDATA。
    """
    config_filename = "net_music_can_play_bili.json"
    search_dirs = [
        Path.cwd() / "run" / "config",        # dev run/config/
        Path.cwd(),                           # 当前目录
        Path(__file__).resolve().parent / "run" / "config",  # 脚本所在项目 run/config/
    ]
    # 也尝试往上找 run/config（从当前目录向上遍历）
    p = Path.cwd()
    for _ in range(5):
        candidate = p / "run" / "config"
        if candidate.is_dir() and candidate not in search_dirs:
            search_dirs.append(candidate)
        if p.parent == p:
            break
        p = p.parent

    for d in search_dirs:
        config_path = d / config_filename
        if config_path.exists():
            try:
                data = json.loads(config_path.read_text(encoding="utf-8"))
                sessdata = data.get("sessdata", "").strip()
                if sessdata:
                    print(f"🔑 从游戏配置自动读取 SESSDATA: {config_path}")
                    return sessdata
            except Exception:
                pass
    return ""


def parse_bool_env(name: str, default: bool = False) -> bool:
    """解析环境变量为布尔值（支持 0/1/true/false/yes/no）。"""
    val = os.environ.get(name, "").strip().lower()
    if not val:
        return default
    return val in ("1", "true", "yes", "on")


def main():
    parser = argparse.ArgumentParser(
        description="B站 Dolby Atmos (E-AC-3) 音频下载工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python bili_download_dolby.py BV1dakuYcENi
  python bili_download_dolby.py BV1dakuYcENi --page 2
  python bili_download_dolby.py BV1dakuYcENi --output my_dolby.ec3
  python bili_download_dolby.py BV1dakuYcENi --sessdata "abc123..."
  set BILI_SESSDATA=abc123... && python bili_download_dolby.py BV1dakuYcENi

SESSDATA 获取方法:
  1. 浏览器打开 bilibili.com 并登录大会员账号
  2. F12 → Application → Cookies → bilibili.com
  3. 找到 SESSDATA，复制其值（通常是带 %2C 的编码字符串）
  4. 通过 --sessdata 参数或 BILI_SESSDATA 环境变量传入
        """,
    )
    parser.add_argument("video", help="B站视频 BV 号或 AV 号 (如 BV1dakuYcENi)")
    parser.add_argument("--page", "-p", type=int, default=1, help="分P编号 (默认: 1)")
    parser.add_argument("--output", "-o", default=None, help="输出文件路径 (默认: 视频标题.ec3)")
    parser.add_argument("--sessdata", "-s", default=None, help="B站 SESSDATA Cookie (也可通过环境变量 BILI_SESSDATA 设置)")
    parser.add_argument("--no-verify", action="store_true", help="跳过 EC-3 格式验证")
    args = parser.parse_args()

    # SESSDATA: 命令行 > 环境变量 > 游戏配置自动检测
    sessdata = args.sessdata or os.environ.get("BILI_SESSDATA", "")
    if not sessdata:
        sessdata = auto_detect_sessdata()
    if not sessdata:
        print("⚠ 未找到 SESSDATA。将尝试获取普通音质（可能无法获取 Dolby Atmos）。")
        print("  SESSDATA 来源优先级:")
        print("  1. --sessdata 命令行参数")
        print("  2. BILI_SESSDATA 环境变量")
        print("  3. run/config/net_music_can_play_bili.json (游戏内已登录)")
        print("  4. 浏览器 F12 → Application → Cookies → bilibili.com → SESSDATA\n")

    try:
        # 1. 解析视频 ID
        kind, value = extract_video_id(args.video)
        print(f"📺 解析视频: {kind}={value}, P{args.page}")

        # 2. 获取视频信息
        print("🔍 获取视频信息...")
        info = get_video_info(kind, value, args.page)
        print(f"   标题: {info['title']}")
        print(f"   UP主: {info['author']}")
        print(f"   时长: {info['duration']}s, CID: {info['cid']}")

        # 3. 获取播放地址
        print("🔑 获取 DASH 播放地址...")
        result = get_play_url(kind, value, info["cid"], sessdata)

        if not result:
            print("\n❌ 该视频没有 Dolby Atmos 音质。")
            print("   可能原因:")
            print("   1. 该视频本身不提供 Dolby 音质")
            print("   2. 需要大会员 SESSDATA")
            print("   3. SESSDATA 已过期或无效")

            # 列出可用的音质
            if result is None:
                pass  # 已经是 None
            sys.exit(1)

        print(f"   音质 ID: {result['quality_id']}")

        # 列出所有可用音质
        print("   可用音质:")
        quality_names = {
            30216: "AAC 64k",
            30232: "AAC 132k",
            30280: "Dolby Atmos 448k",
            30250: "Dolby Atmos (Hi-Res)",
            30251: "Dolby Atmos (Hi-Res)",
        }
        for qid, qurl in sorted(result["urls"].items()):
            name = quality_names.get(qid, f"未知({qid})")
            marker = " ← 已选择" if qid == result["quality_id"] else ""
            print(f"      {name}{marker}")

        # 4. 确定输出文件名
        if args.output:
            output_path = args.output
        else:
            safe_title = re.sub(r'[\\/:*?"<>|]', "_", info["title"])
            output_path = f"{safe_title}.ec3"

        # 5. 下载
        download_ec3(result["url"], output_path, info["title"])

        # 6. 验证
        if not args.no_verify:
            verify_ec3(output_path)

        print(f"\n🎯 文件可用于测试 NetMusicCanPlayBili:")
        print(f"   将 {output_path} 放到 HTTP 服务器上，")
        print(f"   然后在 Minecraft 唱片中输入该 URL 即可播放 Dolby Atmos 空间音频。")

    except Exception as e:
        print(f"\n❌ 错误: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
