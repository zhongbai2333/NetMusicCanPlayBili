#!/usr/bin/env python3
"""Perceptual A/V latency bench for NetMusicCanPlayBili.

Workflow:
1. Start this script before triggering Minecraft.
2. Press Enter in the script. It starts screen/audio capture immediately.
3. Trigger Minecraft command:
   /netmusicbili bench perceptual trigger <label>
   Either let this script type it with --type-command, or run it manually.
4. Minecraft writes run/perceptual-bench-trigger.json, flashes the HUD white, and plays a click.
5. This script detects the trigger file, first visible flash frame, and first audio peak.

Recommended dependencies on Windows:
  pip install numpy mss soundcard pyautogui

Notes:
- soundcard loopback captures system output and is preferred.
- sounddevice/default input can be used for microphone capture, but it measures speaker+room+mic path.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import queue
import statistics
import sys
import threading
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any


@dataclass
class ScreenSample:
    t_ns: int
    mean: float
    white_ratio: float


@dataclass
class AudioBlock:
    t_ns: int
    peak: float
    rms: float


@dataclass
class Report:
    run_id: str
    trigger_file: str
    trigger_detect_ns: int
    trigger_wall_millis: int | None
    screen_first_flash_ns: int | None
    audio_first_peak_ns: int | None
    screen_delay_ms: float | None
    audio_delay_ms: float | None
    av_delta_ms: float | None
    screen_baseline_mean: float | None
    screen_max_mean: float | None
    screen_max_white_ratio: float | None
    audio_baseline_peak: float | None
    screen_samples: int
    audio_blocks: int
    audio_backend: str
    notes: list[str]


def now_ns() -> int:
    return time.perf_counter_ns()


def require_module(name: str) -> Any:
    try:
        return __import__(name)
    except ImportError as exc:
        raise RuntimeError(f"缺少 Python 包 {name!r}。请安装依赖：pip install numpy mss soundcard pyautogui") from exc


class ScreenCapture(threading.Thread):
    def __init__(self, fps: float, monitor_index: int, region: tuple[int, int, int, int] | None, stop: threading.Event):
        super().__init__(name="screen-capture", daemon=True)
        self.fps = max(1.0, fps)
        self.monitor_index = monitor_index
        self.region = region
        self.stop = stop
        self.samples: list[ScreenSample] = []
        self.error: Exception | None = None

    def run(self) -> None:
        try:
            mss = require_module("mss")
            np = require_module("numpy")
            period = 1.0 / self.fps
            with mss.MSS() as sct:
                if self.region:
                    left, top, width, height = self.region
                    monitor = {"left": left, "top": top, "width": width, "height": height}
                else:
                    monitors = sct.monitors
                    idx = min(max(1, self.monitor_index), len(monitors) - 1)
                    monitor = monitors[idx]
                next_t = time.perf_counter()
                while not self.stop.is_set():
                    shot = sct.grab(monitor)
                    t = now_ns()
                    arr = np.asarray(shot, dtype=np.uint8)[:, :, :3]
                    # mss is BGRA; mean is channel-order agnostic. White ratio catches the full-screen flash.
                    mean = float(arr.mean())
                    white_ratio = float(((arr[:, :, 0] > 235) & (arr[:, :, 1] > 235) & (arr[:, :, 2] > 235)).mean())
                    self.samples.append(ScreenSample(t, mean, white_ratio))
                    next_t += period
                    sleep = next_t - time.perf_counter()
                    if sleep > 0:
                        time.sleep(sleep)
                    else:
                        next_t = time.perf_counter()
        except Exception as exc:  # noqa: BLE001 - report user environment issues clearly
            self.error = exc
            self.stop.set()


class SoundcardLoopbackCapture(threading.Thread):
    def __init__(self, sample_rate: int, block_ms: int, stop: threading.Event):
        super().__init__(name="soundcard-loopback", daemon=True)
        self.sample_rate = sample_rate
        self.block_frames = max(64, int(sample_rate * max(1, block_ms) / 1000))
        self.stop = stop
        self.blocks: list[AudioBlock] = []
        self.error: Exception | None = None
        self.backend_name = "soundcard-loopback"

    def run(self) -> None:
        try:
            np = require_module("numpy")
            soundcard = require_module("soundcard")
            speaker = soundcard.default_speaker()
            mic = soundcard.get_microphone(speaker.name, include_loopback=True)
            with mic.recorder(samplerate=self.sample_rate, channels=2) as recorder:
                while not self.stop.is_set():
                    data = recorder.record(numframes=self.block_frames)
                    t = now_ns()
                    if data.size == 0:
                        continue
                    mono = np.asarray(data, dtype=np.float32)
                    peak = float(np.max(np.abs(mono)))
                    rms = float(math.sqrt(float(np.mean(mono * mono))))
                    self.blocks.append(AudioBlock(t, peak, rms))
        except Exception as exc:  # noqa: BLE001
            self.error = exc
            self.stop.set()


class NullAudioCapture(threading.Thread):
    def __init__(self, stop: threading.Event, reason: str):
        super().__init__(name="no-audio", daemon=True)
        self.stop = stop
        self.blocks: list[AudioBlock] = []
        self.error: Exception | None = RuntimeError(reason)
        self.backend_name = "none"

    def run(self) -> None:
        while not self.stop.is_set():
            time.sleep(0.05)


def parse_region(value: str | None) -> tuple[int, int, int, int] | None:
    if not value:
        return None
    parts = [int(p.strip()) for p in value.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("region 格式应为 left,top,width,height")
    return tuple(parts)  # type: ignore[return-value]


def read_trigger(path: Path, previous_run_id: str | None, min_mtime_ns: int) -> tuple[dict[str, Any] | None, int | None]:
    if not path.exists():
        return None, None
    try:
        stat = path.stat()
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None, None
    run_id = str(data.get("runId", ""))
    if not run_id:
        return None, None
    if run_id == previous_run_id and stat.st_mtime_ns < min_mtime_ns:
        return None, None
    return data, stat.st_mtime_ns


def wait_for_trigger(paths: list[Path], timeout_s: float, previous_run_ids: dict[Path, str | None], min_mtime_ns: int) -> tuple[Path, dict[str, Any], int]:
    deadline = time.perf_counter() + timeout_s
    while time.perf_counter() < deadline:
        for path in paths:
            data, _mtime = read_trigger(path, previous_run_ids.get(path), min_mtime_ns)
            if data is not None:
                return path, data, now_ns()
        time.sleep(0.005)
    joined = ", ".join(str(path) for path in paths)
    raise TimeoutError(f"等待 trigger 文件超时: {joined}")


def trigger_paths(primary: str) -> list[Path]:
    paths: list[Path] = []
    script_root = Path(__file__).resolve().parents[1]
    raw_candidates = (
        Path(primary),
        Path("run/perceptual-bench-trigger.json"),
        Path("run/run/perceptual-bench-trigger.json"),
        script_root / primary,
        script_root / "run/perceptual-bench-trigger.json",
        script_root / "run/run/perceptual-bench-trigger.json",
    )
    for candidate in raw_candidates:
        path = candidate if candidate.is_absolute() else Path.cwd() / candidate
        path = path.resolve()
        if path not in paths:
            paths.append(path)
    return paths


def describe_monitors() -> list[str]:
    try:
        mss = require_module("mss")
        with mss.MSS() as sct:
            lines = []
            for i, monitor in enumerate(sct.monitors):
                lines.append(
                    f"monitor {i}: left={monitor['left']} top={monitor['top']} "
                    f"width={monitor['width']} height={monitor['height']}"
                )
            return lines
    except Exception as exc:  # noqa: BLE001
        return [f"monitor listing failed: {exc}"]


def maybe_type_command(args: argparse.Namespace) -> None:
    if not args.type_command:
        print("请现在在 Minecraft 里执行: /netmusicbili bench perceptual trigger", flush=True)
        return
    try:
        pyautogui = require_module("pyautogui")
    except RuntimeError as exc:
        print(f"无法自动输入命令：{exc}", file=sys.stderr)
        print("请手动在 Minecraft 执行: /netmusicbili bench perceptual trigger", flush=True)
        return
    print(f"{args.command_delay:.1f}s 后自动输入 MC 命令，请把焦点切到 Minecraft 窗口...", flush=True)
    time.sleep(max(0.0, args.command_delay))
    command = f"/netmusicbili bench perceptual trigger {args.label}"
    pyautogui.press("t")
    time.sleep(0.05)
    pyautogui.write(command, interval=0.001)
    pyautogui.press("enter")


def detect_screen(samples: list[ScreenSample], trigger_ns: int, args: argparse.Namespace) -> tuple[int | None, float | None]:
    before = [s for s in samples if trigger_ns - 1_500_000_000 <= s.t_ns < trigger_ns]
    baseline = statistics.median([s.mean for s in before]) if before else None
    threshold = args.screen_white_ratio
    for s in samples:
        if s.t_ns < trigger_ns:
            continue
        bright_enough = baseline is None or s.mean >= baseline + args.screen_mean_delta
        if s.white_ratio >= threshold and bright_enough:
            return s.t_ns, baseline
    return None, baseline


def detect_audio(blocks: list[AudioBlock], trigger_ns: int, args: argparse.Namespace) -> tuple[int | None, float | None]:
    before = [b for b in blocks if trigger_ns - 1_500_000_000 <= b.t_ns < trigger_ns]
    baseline = statistics.median([b.peak for b in before]) if before else 0.0
    threshold = max(args.audio_min_peak, baseline * args.audio_peak_multiplier)
    for b in blocks:
        if b.t_ns < trigger_ns:
            continue
        if b.peak >= threshold and b.rms >= args.audio_min_rms:
            return b.t_ns, baseline
    return None, baseline


def write_report(report: Report, output: Path, screen: list[ScreenSample], audio: list[AudioBlock]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = asdict(report)
    payload["screenTail"] = [asdict(s) for s in screen[-300:]]
    payload["audioTail"] = [asdict(a) for a in audio[-300:]]
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Record screen/audio and align Minecraft perceptual bench trigger.")
    parser.add_argument("--trigger-file", default="run/perceptual-bench-trigger.json")
    parser.add_argument("--output", default="run/perceptual-bench-report.json")
    parser.add_argument("--label", default="manual")
    parser.add_argument("--fps", type=float, default=120.0)
    parser.add_argument("--monitor", type=int, default=0, help="mss monitor index; 0 captures the virtual full desktop")
    parser.add_argument("--list-monitors", action="store_true", help="List screen capture monitors and exit")
    parser.add_argument("--region", type=parse_region, default=None, help="Screen capture region: left,top,width,height")
    parser.add_argument("--sample-rate", type=int, default=48_000)
    parser.add_argument("--audio-block-ms", type=int, default=5)
    parser.add_argument("--post-seconds", type=float, default=3.0)
    parser.add_argument("--trigger-timeout", type=float, default=20.0)
    parser.add_argument("--type-command", action="store_true", help="Use pyautogui to type the Minecraft command after Enter")
    parser.add_argument("--command-delay", type=float, default=2.0, help="Seconds to focus Minecraft before auto typing")
    parser.add_argument("--arm-now", action="store_true", help="Start capture immediately instead of waiting for Enter")
    parser.add_argument("--no-audio", action="store_true")
    parser.add_argument("--screen-white-ratio", type=float, default=0.55)
    parser.add_argument("--screen-mean-delta", type=float, default=35.0)
    parser.add_argument("--audio-min-peak", type=float, default=0.04)
    parser.add_argument("--audio-min-rms", type=float, default=0.002)
    parser.add_argument("--audio-peak-multiplier", type=float, default=5.0)
    args = parser.parse_args()

    if args.list_monitors:
        for line in describe_monitors():
            print(line)
        return 0

    paths = trigger_paths(args.trigger_file)
    output_path = Path(args.output)
    previous_run_ids: dict[Path, str | None] = {}
    for path in paths:
        previous_run_id = None
        if path.exists():
            try:
                previous_run_id = json.loads(path.read_text(encoding="utf-8")).get("runId")
            except Exception:
                previous_run_id = None
        previous_run_ids[path] = previous_run_id

    print("监听 Minecraft trigger 文件:", flush=True)
    for path in paths:
        old = previous_run_ids.get(path)
        exists = "exists" if path.exists() else "missing"
        print(f"  - {path} [{exists}, previousRunId={old or '-'}]", flush=True)
    print("可用屏幕捕获目标:", flush=True)
    for line in describe_monitors():
        print(f"  - {line}", flush=True)
    print(f"当前使用 monitor={args.monitor}；0=全桌面。若要减少开销，可换成 Minecraft 所在屏幕或 --region left,top,width,height", flush=True)

    if args.arm_now:
        print("已立即开始录屏/录音并等待 Minecraft trigger...", flush=True)
    else:
        print("按一次 Enter 开始录屏/录音；不要按 Ctrl+C。开始后再去 Minecraft 执行 trigger 命令。", flush=True)
        input()
        print("已开始录屏/录音并等待 Minecraft trigger...", flush=True)
    arm_mtime_ns = time.time_ns()

    stop = threading.Event()
    screen_capture = ScreenCapture(args.fps, args.monitor, args.region, stop)
    if args.no_audio:
        audio_capture: SoundcardLoopbackCapture | NullAudioCapture = NullAudioCapture(stop, "--no-audio")
    else:
        audio_capture = SoundcardLoopbackCapture(args.sample_rate, args.audio_block_ms, stop)

    screen_capture.start()
    audio_capture.start()
    time.sleep(0.2)
    maybe_type_command(args)

    notes: list[str] = []
    try:
        trigger_path, trigger, trigger_detect_ns = wait_for_trigger(paths, args.trigger_timeout, previous_run_ids,
                                        arm_mtime_ns)
        print(f"检测到 Minecraft trigger: {trigger.get('runId')} ({trigger_path})，继续采集 {args.post_seconds:.1f}s...", flush=True)
        time.sleep(max(0.1, args.post_seconds))
    except Exception as exc:
        stop.set()
        raise
    finally:
        stop.set()
        screen_capture.join(timeout=2.0)
        audio_capture.join(timeout=2.0)

    if screen_capture.error:
        notes.append(f"screen capture error: {screen_capture.error}")
    if audio_capture.error:
        notes.append(f"audio capture error: {audio_capture.error}")

    screen_ns, screen_baseline = detect_screen(screen_capture.samples, trigger_detect_ns, args)
    audio_ns, audio_baseline = detect_audio(audio_capture.blocks, trigger_detect_ns, args)
    screen_max_mean = max((sample.mean for sample in screen_capture.samples), default=None)
    screen_max_white_ratio = max((sample.white_ratio for sample in screen_capture.samples), default=None)

    screen_delay = (screen_ns - trigger_detect_ns) / 1_000_000.0 if screen_ns else None
    audio_delay = (audio_ns - trigger_detect_ns) / 1_000_000.0 if audio_ns else None
    av_delta = (screen_ns - audio_ns) / 1_000_000.0 if screen_ns and audio_ns else None

    report = Report(
        run_id=str(trigger.get("runId", "")),
        trigger_file=str(trigger_path),
        trigger_detect_ns=trigger_detect_ns,
        trigger_wall_millis=int(trigger["wallMillis"]) if "wallMillis" in trigger else None,
        screen_first_flash_ns=screen_ns,
        audio_first_peak_ns=audio_ns,
        screen_delay_ms=screen_delay,
        audio_delay_ms=audio_delay,
        av_delta_ms=av_delta,
        screen_baseline_mean=screen_baseline,
        screen_max_mean=screen_max_mean,
        screen_max_white_ratio=screen_max_white_ratio,
        audio_baseline_peak=audio_baseline,
        screen_samples=len(screen_capture.samples),
        audio_blocks=len(audio_capture.blocks),
        audio_backend=getattr(audio_capture, "backend_name", "unknown"),
        notes=notes,
    )
    write_report(report, output_path, screen_capture.samples, audio_capture.blocks)

    print("\n=== Perceptual Bench Report ===")
    print(f"runId: {report.run_id}")
    print(f"screen delay from trigger detect: {report.screen_delay_ms} ms")
    print(f"audio delay from trigger detect:  {report.audio_delay_ms} ms")
    print(f"screen - audio delta:            {report.av_delta_ms} ms")
    print(f"screen max mean/white ratio:     {report.screen_max_mean} / {report.screen_max_white_ratio}")
    print(f"samples: screen={report.screen_samples}, audio={report.audio_blocks}, backend={report.audio_backend}")
    if notes:
        print("notes:")
        for note in notes:
            print(f"  - {note}")
    print(f"report written: {output_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("Interrupted", file=sys.stderr)
        raise SystemExit(130)
