from __future__ import annotations

import argparse
import struct
import zlib
from pathlib import Path

WIDTH = 320
HEIGHT = 180
BG = 0xFF08090D
PANEL = 0xFF151923
GOLD = 0xFFFFD166
GOLD_DIM = 0xFF7A6230
TEXT = 0xFFE8E8E8
SHADOW = 0xAA000000
TRANSPARENT = 0x00000000


def argb_to_rgba(color: int) -> tuple[int, int, int, int]:
    return ((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF, (color >> 24) & 0xFF)


def set_pixel(px: bytearray, x: int, y: int, color: int) -> None:
    r, g, b, a = argb_to_rgba(color)
    offset = (y * WIDTH + x) * 4
    px[offset:offset + 4] = bytes((r, g, b, a))


def fill(px: bytearray, x: int, y: int, w: int, h: int, color: int) -> None:
    max_x = min(WIDTH, x + max(0, w))
    max_y = min(HEIGHT, y + max(0, h))
    for py in range(max(0, y), max_y):
        for px_i in range(max(0, x), max_x):
            set_pixel(px, px_i, py, color)


def rect(px: bytearray, x: int, y: int, w: int, h: int, color: int) -> None:
    fill(px, x, y, w, 1, color)
    fill(px, x, y + h - 1, w, 1, color)
    fill(px, x, y, 1, h, color)
    fill(px, x + w - 1, y, 1, h, color)


def dots(phase: int) -> str:
    if phase == 1:
        return "."
    if phase == 2:
        return ".."
    if phase == 3:
        return "..."
    return ""


def text_width(text: str) -> int:
    width = 0
    for ch in text:
        width += 4 if ch == " " else 12
    return max(0, width - 2)


def glyph(ch: str) -> list[int]:
    table = {
        "A": [0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001],
        "C": [0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110],
        "D": [0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110],
        "E": [0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111],
        "G": [0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110],
        "H": [0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001],
        "I": [0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111],
        "K": [0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001],
        "L": [0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111],
        "M": [0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001],
        "N": [0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001],
        "O": [0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110],
        "P": [0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000],
        "R": [0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001],
        "S": [0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110],
        "T": [0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100],
        "U": [0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110],
        "V": [0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100],
        "W": [0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001],
        "Y": [0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100],
        ".": [0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100],
    }
    return table.get(ch.upper(), [0, 0, 0, 0, 0, 0, 0])


def draw_text_raw(px: bytearray, text: str, x: int, y: int, color: int) -> None:
    cursor = x
    for ch in text.upper():
        if ch == " ":
            cursor += 4
            continue
        g = glyph(ch)
        for row in range(7):
            bits = g[row]
            for col in range(5):
                if bits & (1 << (4 - col)):
                    fill(px, cursor + col * 2, y + row * 2, 2, 2, color)
        cursor += 12


def draw_text(px: bytearray, text: str, x: int, y: int, color: int) -> None:
    draw_text_raw(px, text, x + 1, y + 1, SHADOW)
    draw_text_raw(px, text, x, y, color)


def draw_centered_text(px: bytearray, text: str, y: int, color: int) -> None:
    draw_text(px, text, (WIDTH - text_width(text)) // 2, y, color)


def draw_loading(elapsed_ns: int, queued_frames: int, capacity: int, iris_warning: bool) -> bytearray:
    px = bytearray(WIDTH * HEIGHT * 4)
    fill(px, 0, 0, WIDTH, HEIGHT, BG)
    fill(px, 18, 18, WIDTH - 36, HEIGHT - 36, PANEL)
    rect(px, 18, 18, WIDTH - 36, HEIGHT - 36, GOLD_DIM)

    phase = (elapsed_ns // 300_000_000) % 4
    if iris_warning:
        draw_centered_text(px, "VIDEO ACTIVE", 54, TEXT)
        draw_centered_text(px, "TRANSLUCENT", 78, GOLD)
        draw_centered_text(px, "MAY HIDE VIDEO", 102, 0xFFB8C1CC)
    else:
        draw_centered_text(px, "LOADING" + dots(int(phase)), 62, TEXT)
        draw_centered_text(px, "DECODING", 84, 0xFFB8C1CC)
        draw_centered_text(px, "PLEASE WAIT", 100, 0xFF8F9BA8)

    bar_x = 58
    bar_y = 126
    bar_w = 204
    bar_h = 10
    rect(px, bar_x, bar_y, bar_w, bar_h, GOLD_DIM)
    segment_w = 42
    moving_x = bar_x + 2 + ((elapsed_ns // 12_000_000) % max(1, bar_w - segment_w - 4))
    fill(px, int(moving_x), bar_y + 2, segment_w, bar_h - 4, GOLD)

    buffered = 0 if capacity <= 0 else min(bar_w - 4, max(0, queued_frames) * (bar_w - 4) // capacity)
    fill(px, bar_x + 2, bar_y + bar_h + 8, buffered, 3, GOLD_DIM)
    return px


def draw_loading_base(phase: int, iris_warning: bool = False) -> bytearray:
    px = bytearray(WIDTH * HEIGHT * 4)
    fill(px, 0, 0, WIDTH, HEIGHT, BG)
    fill(px, 18, 18, WIDTH - 36, HEIGHT - 36, PANEL)
    rect(px, 18, 18, WIDTH - 36, HEIGHT - 36, GOLD_DIM)

    if iris_warning:
        draw_centered_text(px, "VIDEO ACTIVE", 54, TEXT)
        draw_centered_text(px, "TRANSLUCENT", 78, GOLD)
        draw_centered_text(px, "MAY HIDE VIDEO", 102, 0xFFB8C1CC)
    else:
        draw_centered_text(px, "LOADING" + dots(phase), 62, TEXT)
        draw_centered_text(px, "DECODING", 84, 0xFFB8C1CC)
        draw_centered_text(px, "PLEASE WAIT", 100, 0xFF8F9BA8)
    return px


def draw_network_error_base() -> bytearray:
    px = bytearray(WIDTH * HEIGHT * 4)
    fill(px, 0, 0, WIDTH, HEIGHT, BG)
    fill(px, 18, 18, WIDTH - 36, HEIGHT - 36, PANEL)
    rect(px, 18, 18, WIDTH - 36, HEIGHT - 36, 0xFF9B493F)
    draw_centered_text(px, "NETWORK ERROR", 52, 0xFFFF8A78)
    draw_centered_text(px, "CHECK CONNECTION", 78, TEXT)
    draw_centered_text(px, "TRY AGAIN", 102, 0xFFB8C1CC)

    bar_x = 58
    bar_y = 126
    bar_w = 204
    bar_h = 10
    rect(px, bar_x, bar_y, bar_w, bar_h, 0xFF63342F)
    for x in range(bar_x + 4, bar_x + bar_w - 4, 18):
        fill(px, x, bar_y + 3, 9, bar_h - 6, 0xFF9B493F)
    return px


def draw_progress_layer(elapsed_ns: int, queued_frames: int, capacity: int) -> bytearray:
    px = bytearray(WIDTH * HEIGHT * 4)
    fill(px, 0, 0, WIDTH, HEIGHT, TRANSPARENT)
    bar_x = 58
    bar_y = 126
    bar_w = 204
    bar_h = 10
    rect(px, bar_x, bar_y, bar_w, bar_h, GOLD_DIM)
    segment_w = 42
    moving_x = bar_x + 2 + ((elapsed_ns // 12_000_000) % max(1, bar_w - segment_w - 4))
    fill(px, int(moving_x), bar_y + 2, segment_w, bar_h - 4, GOLD)

    buffered = 0 if capacity <= 0 else min(bar_w - 4, max(0, queued_frames) * (bar_w - 4) // capacity)
    fill(px, bar_x + 2, bar_y + bar_h + 8, buffered, 3, GOLD_DIM)
    return px


def draw_progress_frame(sprite_width: int, sprite_height: int) -> bytearray:
    px = bytearray(sprite_width * sprite_height * 4)

    def set_local(x: int, y: int, color: int) -> None:
        r, g, b, a = argb_to_rgba(color)
        offset = (y * sprite_width + x) * 4
        px[offset:offset + 4] = bytes((r, g, b, a))

    def fill_local(x: int, y: int, w: int, h: int, color: int) -> None:
        max_x = min(sprite_width, x + max(0, w))
        max_y = min(sprite_height, y + max(0, h))
        for py in range(max(0, y), max_y):
            for px_i in range(max(0, x), max_x):
                set_local(px_i, py, color)

    def rect_local(x: int, y: int, w: int, h: int, color: int) -> None:
        fill_local(x, y, w, 1, color)
        fill_local(x, y + h - 1, w, 1, color)
        fill_local(x, y, 1, h, color)
        fill_local(x + w - 1, y, 1, h, color)

    fill_local(0, 0, sprite_width, sprite_height, TRANSPARENT)
    rect_local(0, 0, sprite_width, 10, GOLD_DIM)
    return px


def draw_progress_segment(sprite_width: int, sprite_height: int) -> bytearray:
    px = bytearray(sprite_width * sprite_height * 4)
    rgba = argb_to_rgba(GOLD)
    for y in range(sprite_height):
        for x in range(sprite_width):
            offset = (y * sprite_width + x) * 4
            px[offset:offset + 4] = bytes(rgba)
    return px


def draw_holographic_privacy_overlay() -> bytearray:
    px = bytearray(WIDTH * HEIGHT * 4)
    fill(px, 0, 0, WIDTH, HEIGHT, 0xEE071017)
    fill(px, 8, 8, WIDTH - 16, HEIGHT - 16, 0xAA0D2B36)
    rect(px, 8, 8, WIDTH - 16, HEIGHT - 16, 0xFF55E6FF)
    rect(px, 18, 18, WIDTH - 36, HEIGHT - 36, 0xFF1B6F82)
    fill(px, 28, 72, WIDTH - 56, 36, 0xC9111A22)
    draw_centered_text(px, "STREAMER", 42, 0xFF8EF6FF)
    draw_centered_text(px, "SAFE MODE", 66, 0xFFFFFFFF)
    draw_centered_text(px, "VIDEO HIDDEN", 94, 0xFFBFEFFF)
    for x in range(0, WIDTH, 18):
        fill(px, x, 0, 9, 2, 0x8855E6FF)
        fill(px, WIDTH - x - 9, HEIGHT - 2, 9, 2, 0x8855E6FF)
    return px


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def save_png(path: Path, rgba: bytearray, width: int = WIDTH, height: int = HEIGHT) -> None:
    rows = bytearray()
    stride = width * 4
    for y in range(height):
        rows.append(0)  # PNG filter type 0
        start = y * stride
        rows.extend(rgba[start:start + stride])
    data = b"\x89PNG\r\n\x1a\n"
    data += png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    data += png_chunk(b"IDAT", zlib.compress(bytes(rows), level=9))
    data += png_chunk(b"IEND", b"")
    path.write_bytes(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=Path("build/generated-preview/loading-ui"))
    parser.add_argument("--capacity", type=int, default=3)
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    frames = [0, 300_000_000, 600_000_000, 900_000_000]
    base_dir = args.out / "base"
    progress_dir = args.out / "progress"
    overlay_dir = args.out / "overlay"
    for directory in (base_dir, progress_dir, overlay_dir):
        directory.mkdir(parents=True, exist_ok=True)

    for index, elapsed in enumerate(frames):
        save_png(base_dir / f"loading_base_phase{index}.png", draw_loading_base(index, False))
    save_png(progress_dir / "progress_frame_204x10.png", draw_progress_frame(204, 10), 204, 10)
    save_png(progress_dir / "progress_segment_42x6.png", draw_progress_segment(42, 6), 42, 6)

    save_png(base_dir / "iris_translucent_warning_base.png", draw_loading_base(0, True))
    save_png(base_dir / "network_error_base.png", draw_network_error_base())
    save_png(overlay_dir / "holographic_privacy_overlay.png", draw_holographic_privacy_overlay())
    print(f"Generated loading UI previews in {args.out.resolve()}")


if __name__ == "__main__":
    main()
