#!/usr/bin/env python3
"""
Tk GUI timeline simulator for NetMusicCanPlayBili's Bilibili DASH playback path.

This intentionally reuses tools/bili_timeline_probe.py for Bilibili API, DASH stream
selection, SegmentBase/sidx probing, and subtitles. The GUI then runs FFmpeg against
the selected video stream and displays frames on a local media timeline similar to
the Java mod's renderer queue:

- one local timeline is the source of truth
- decoded video frames are queued with expected media PTS
- render ticks select the best frame <= expected time + early tolerance
- old frames are dropped instead of displayed late
- optional restart simulation can show when an over-aggressive chase policy would
  keep killing the decoder

Audio playback is optional and uses ffplay when available. The GUI still tracks the
audio expected/current timeline even when audio output is disabled, so it is useful
for sync diagnosis on machines without Python audio libraries.
"""

from __future__ import annotations

import argparse
import math
import queue
import shutil
import subprocess
import sys
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from tkinter import messagebox, ttk
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import bili_timeline_probe as probe  # noqa: E402


TARGET_W = 854
TARGET_H = 480
DEFAULT_VIDEO = "BV17q9EBEE9w"


@dataclass(frozen=True)
class StreamSelection:
    video_id: probe.VideoId
    view: dict[str, Any]
    playurl: dict[str, Any]
    video: dict[str, Any]
    audio: dict[str, Any] | None
    subtitles: list[probe.SubtitleLine]
    fps: float
    duration_ms: int
    sessdata: str
    video_first_pts_ms: float | None = None
    audio_first_pts_ms: float | None = None


@dataclass
class DecodedFrame:
    media_ms: float
    ppm: bytes


class LocalTimeline:
    def __init__(self) -> None:
        self.start_media_ms = 0.0
        self.started_ns = 0
        self.paused = False
        self.pause_started_ns = 0
        self.paused_total_ns = 0

    def start(self, start_media_ms: float) -> None:
        now = time.monotonic_ns()
        self.start_media_ms = max(0.0, start_media_ms)
        self.started_ns = now
        self.paused = False
        self.pause_started_ns = 0
        self.paused_total_ns = 0

    def media_ms(self) -> float:
        if self.started_ns <= 0:
            return self.start_media_ms
        now = self.pause_started_ns if self.paused else time.monotonic_ns()
        elapsed = max(0, now - self.started_ns - self.paused_total_ns) / 1_000_000.0
        return self.start_media_ms + elapsed

    def set_paused(self, paused: bool) -> None:
        if paused == self.paused:
            return
        now = time.monotonic_ns()
        if paused:
            self.pause_started_ns = now
            self.paused = True
        else:
            self.paused_total_ns += max(0, now - self.pause_started_ns)
            self.pause_started_ns = 0
            self.paused = False


class VideoDecoderThread(threading.Thread):
    def __init__(self, ffmpeg: str, video_url: str, sessdata: str, decoder_start_ms: float,
                 fps: float, out_queue: queue.Queue[DecodedFrame], stop_event: threading.Event,
                 log: queue.Queue[str]) -> None:
        super().__init__(name="bili-gui-video-decoder", daemon=True)
        self.ffmpeg = ffmpeg
        self.video_url = video_url
        self.sessdata = sessdata
        self.decoder_start_ms = max(0.0, decoder_start_ms)
        self.fps = max(1.0, fps)
        self.out_queue = out_queue
        self.stop_event = stop_event
        self.log = log
        self.proc: subprocess.Popen[bytes] | None = None

    def run(self) -> None:
        headers = (
            "User-Agent: " + probe.USER_AGENT + "\r\n"
            "Referer: https://www.bilibili.com/\r\n"
            "Origin: https://www.bilibili.com\r\n"
        )
        if self.sessdata:
            headers += "Cookie: SESSDATA=" + self.sessdata + "\r\n"
        # Use input-side seek for remote 2h fMP4. Output-side accurate seek may make FFmpeg decode
        # from the beginning of the HTTP resource, which looks like a black GUI forever. We already
        # choose the Java-like SegmentBase/sidx fragment start before launching FFmpeg, so fast seek
        # here still exercises the same fragment/preroll path while producing frames immediately.
        cmd = [
            self.ffmpeg,
            "-hide_banner", "-loglevel", "warning",
            "-headers", headers,
            "-ss", f"{self.decoder_start_ms / 1000.0:.3f}",
            "-i", self.video_url,
            "-an",
            "-vf", f"scale={TARGET_W}:{TARGET_H}:force_original_aspect_ratio=decrease,pad={TARGET_W}:{TARGET_H}:(ow-iw)/2:(oh-ih)/2",
            "-pix_fmt", "rgb24",
            "-f", "rawvideo",
            "pipe:1",
        ]
        frame_size = TARGET_W * TARGET_H * 3
        frame_index = 0
        try:
            self.log.put(f"[video] ffmpeg start @ {self.decoder_start_ms:.0f}ms")
            self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self._start_stderr_logger(self.proc)
            assert self.proc.stdout is not None
            while not self.stop_event.is_set():
                raw = self.proc.stdout.read(frame_size)
                if len(raw) != frame_size:
                    break
                media_ms = self.decoder_start_ms + frame_index * 1000.0 / self.fps
                frame_index += 1
                ppm = b"P6\n%d %d\n255\n" % (TARGET_W, TARGET_H) + raw
                while not self.stop_event.is_set():
                    try:
                        self.out_queue.put(DecodedFrame(media_ms, ppm), timeout=0.05)
                        break
                    except queue.Full:
                        try:
                            self.out_queue.get_nowait()
                        except queue.Empty:
                            pass
            self.log.put(f"[video] ffmpeg ended frames={frame_index}")
        except Exception as exc:
            self.log.put(f"[video] decoder failed: {exc}")
        finally:
            self.close()

    def _start_stderr_logger(self, proc: subprocess.Popen[bytes]) -> None:
        def worker() -> None:
            if proc.stderr is None:
                return
            for raw in iter(proc.stderr.readline, b""):
                if not raw:
                    break
                text = raw.decode("utf-8", errors="replace").strip()
                if text:
                    self.log.put("[ffmpeg] " + text)

        threading.Thread(target=worker, name="bili-gui-ffmpeg-stderr", daemon=True).start()

    def close(self) -> None:
        proc = self.proc
        self.proc = None
        if proc and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                proc.kill()


class TimelineGui(tk.Tk):
    def __init__(self, args: argparse.Namespace) -> None:
        super().__init__()
        self.title("Bili timeline GUI - NetMusicCanPlayBili simulator")
        self.geometry("1180x820")
        self.args = args
        self.timeline = LocalTimeline()
        self.frame_queue: queue.Queue[DecodedFrame] = queue.Queue(maxsize=args.queue_capacity)
        self.log_queue: queue.Queue[str] = queue.Queue()
        self.stop_event = threading.Event()
        self.decoder: VideoDecoderThread | None = None
        self.audio_proc: subprocess.Popen[Any] | None = None
        self.selection: StreamSelection | None = None
        self.current_image: tk.PhotoImage | None = None
        self.last_frame: DecodedFrame | None = None
        self.last_restart_at_ns = 0
        self.decoded_latest_ms = -1.0
        self.uploads = 0
        self.drops = 0
        self.dry_ticks = 0
        self.restarts = 0
        self._build_ui()
        self.protocol("WM_DELETE_WINDOW", self.destroy)
        self.after(50, self._ui_tick)
        if args.auto_start:
            self.after(200, self.resolve_and_play)

    def _build_ui(self) -> None:
        root = ttk.Frame(self, padding=8)
        root.pack(fill=tk.BOTH, expand=True)

        controls = ttk.Frame(root)
        controls.pack(fill=tk.X)

        self.video_var = tk.StringVar(value=self.args.video)
        self.page_var = tk.IntVar(value=self.args.page)
        self.quality_var = tk.IntVar(value=self.args.quality)
        self.codec_var = tk.IntVar(value=self.args.codec_id)
        self.start_var = tk.DoubleVar(value=self.args.start)
        self.audio_var = tk.BooleanVar(value=not self.args.no_audio)
        self.restart_var = tk.BooleanVar(value=self.args.simulate_restart)
        self.audio_latency_var = tk.DoubleVar(value=self.args.audio_latency_ms)
        self.audio_lead_var = tk.DoubleVar(value=self.args.audio_lead_ms)
        self.video_delay_var = tk.DoubleVar(value=self.args.video_delay_ms)
        self.subtitle_delay_var = tk.DoubleVar(value=self.args.subtitle_delay_ms)

        ttk.Label(controls, text="BV/AV").pack(side=tk.LEFT)
        ttk.Entry(controls, textvariable=self.video_var, width=18).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="P").pack(side=tk.LEFT)
        ttk.Spinbox(controls, from_=1, to=99, textvariable=self.page_var, width=4).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="quality").pack(side=tk.LEFT)
        ttk.Spinbox(controls, from_=16, to=127, textvariable=self.quality_var, width=6).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="codec").pack(side=tk.LEFT)
        ttk.Spinbox(controls, from_=0, to=13, textvariable=self.codec_var, width=4).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="start s").pack(side=tk.LEFT)
        ttk.Entry(controls, textvariable=self.start_var, width=8).pack(side=tk.LEFT, padx=4)
        ttk.Checkbutton(controls, text="ffplay audio", variable=self.audio_var).pack(side=tk.LEFT, padx=8)
        ttk.Checkbutton(controls, text="simulate restart", variable=self.restart_var).pack(side=tk.LEFT, padx=8)
        ttk.Label(controls, text="audio latency model ms").pack(side=tk.LEFT)
        ttk.Entry(controls, textvariable=self.audio_latency_var, width=7).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="audio lead ms").pack(side=tk.LEFT)
        ttk.Entry(controls, textvariable=self.audio_lead_var, width=7).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="video delay ms").pack(side=tk.LEFT)
        ttk.Entry(controls, textvariable=self.video_delay_var, width=7).pack(side=tk.LEFT, padx=4)
        ttk.Label(controls, text="subtitle delay ms").pack(side=tk.LEFT)
        ttk.Entry(controls, textvariable=self.subtitle_delay_var, width=7).pack(side=tk.LEFT, padx=4)
        ttk.Button(controls, text="Resolve + Play", command=self.resolve_and_play).pack(side=tk.LEFT, padx=4)
        ttk.Button(controls, text="Pause/Resume", command=self.toggle_pause).pack(side=tk.LEFT, padx=4)
        ttk.Button(controls, text="Stop", command=self.stop_playback).pack(side=tk.LEFT, padx=4)

        body = ttk.Frame(root)
        body.pack(fill=tk.BOTH, expand=True, pady=8)
        self.canvas = tk.Canvas(body, width=TARGET_W, height=TARGET_H, bg="#08090d", highlightthickness=0)
        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=False)

        side = ttk.Frame(body)
        side.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(8, 0))
        self.stats_var = tk.StringVar(value="未开始")
        ttk.Label(side, textvariable=self.stats_var, justify=tk.LEFT, font=("Consolas", 10)).pack(fill=tk.X)
        self.subtitle_var = tk.StringVar(value="字幕：-")
        ttk.Label(side, textvariable=self.subtitle_var, wraplength=280, justify=tk.LEFT).pack(fill=tk.X, pady=8)
        ttk.Label(side, text="日志").pack(anchor=tk.W)
        self.log_text = tk.Text(side, height=28, width=44, state=tk.DISABLED, font=("Consolas", 9))
        self.log_text.pack(fill=tk.BOTH, expand=True)

    def resolve_and_play(self) -> None:
        self.stop_playback()
        self.log("[resolve] start")
        threading.Thread(target=self._resolve_worker, daemon=True).start()

    def _resolve_worker(self) -> None:
        try:
            sessdata = self.args.sessdata.strip() or probe.auto_sessdata()
            video_id = probe.parse_video_id(self.video_var.get())
            view = probe.get_view(video_id, int(self.page_var.get()))
            playurl = probe.get_playurl(video_id, int(view["cid"]), sessdata, int(self.quality_var.get()))
            video = probe.choose_video(playurl, int(self.quality_var.get()), int(self.codec_var.get()))
            audio = probe.choose_audio(playurl)
            fps = self._parse_fps(str(video.get("frameRate") or "30"))
            subtitles: list[probe.SubtitleLine] = []
            try:
                player_v2 = probe.get_player_v2(video_id, int(view["aid"]), int(view["cid"]), sessdata)
                infos = probe.subtitle_infos_from_player(player_v2)
                if not infos:
                    infos = probe.subtitle_infos_from_view(probe.get_view_raw(video_id))
                chosen = probe.choose_subtitle(infos)
                if chosen:
                    subtitles = probe.fetch_subtitle_lines(chosen, sessdata)
                    self.log_queue.put(f"[subtitle] {chosen.lan} lines={len(subtitles)}")
            except Exception as exc:
                self.log_queue.put(f"[subtitle] failed: {exc}")
            duration_ms = int(playurl.get("timelength") or view.get("duration", 0) * 1000 or 0)
            start_seconds = max(0.0, float(self.start_var.get()))
            video_first_pts_ms: float | None = None
            audio_first_pts_ms: float | None = None
            try:
                vp = probe.probe_segment_base(video, sessdata, start_seconds, 16000, int(round(fps)), "video", "vide")
                video_first_pts_ms = vp.timing.first_pts_ms if vp.timing else None
                if audio:
                    ap = probe.probe_segment_base(audio, sessdata, start_seconds, 48000, 50, "audio", "soun")
                    audio_first_pts_ms = ap.timing.first_pts_ms if ap.timing else None
                if video_first_pts_ms is not None or audio_first_pts_ms is not None:
                    self.log_queue.put(
                        f"[pts] videoFirst={fmt_optional(video_first_pts_ms)}ms audioFirst={fmt_optional(audio_first_pts_ms)}ms "
                        f"audio-video={fmt_optional_delta(audio_first_pts_ms, video_first_pts_ms)}ms"
                    )
            except Exception as exc:
                self.log_queue.put(f"[pts] probe failed: {exc}")
            self.selection = StreamSelection(video_id, view, playurl, video, audio, subtitles, fps, duration_ms,
                                             sessdata, video_first_pts_ms, audio_first_pts_ms)
            self.log_queue.put(
                f"[selected] {view['title']} P{view['page']} {view['part']}\n"
                f"video q={video.get('id')} codec={video.get('codecid')} {video.get('width')}x{video.get('height')} fps={fps:.3f}\n"
                f"audio q={audio.get('id') if audio else 'none'} duration={duration_ms}ms"
            )
            self.after(0, self._start_selected)
        except Exception as exc:
            self.log_queue.put(f"[resolve] failed: {exc}")
            self.after(0, lambda: messagebox.showerror("Resolve failed", str(exc)))

    def _start_selected(self) -> None:
        selection = self.selection
        if not selection:
            return
        ffmpeg = self.args.ffmpeg or shutil.which("ffmpeg")
        if not ffmpeg:
            messagebox.showerror("ffmpeg not found", "请安装 FFmpeg 或用 --ffmpeg 指定路径")
            return
        start_ms = max(0.0, float(self.start_var.get()) * 1000.0)
        self.stop_event.clear()
        self.timeline.start(start_ms)
        self.uploads = self.drops = self.dry_ticks = self.restarts = 0
        self.last_frame = None
        self.decoded_latest_ms = -1.0
        self._clear_queue()
        video_url = selection.video.get("baseUrl") or selection.video.get("base_url")
        decoder_start_ms = self._fragment_start_ms(selection, start_ms)
        self.decoder = VideoDecoderThread(ffmpeg, video_url, selection.sessdata, decoder_start_ms, selection.fps,
                                          self.frame_queue, self.stop_event, self.log_queue)
        self.decoder.start()
        if self.audio_var.get() and selection.audio:
            self._start_audio(selection, start_ms)

    def _start_audio(self, selection: StreamSelection, start_ms: float) -> None:
        ffplay = self.args.ffplay or shutil.which("ffplay")
        if not ffplay:
            self.log("[audio] ffplay not found, audio clock simulation only")
            return
        audio_url = selection.audio.get("baseUrl") or selection.audio.get("base_url")
        audio_lead_ms = float(self.audio_lead_var.get())
        audio_seek_ms = max(0.0, start_ms + audio_lead_ms)
        try:
            ap = probe.probe_segment_base(selection.audio, selection.sessdata, audio_seek_ms / 1000.0,
                                          48000, 50, "audio", "soun")
            if ap.entry:
                self.log(
                    f"[audio-sidx] original={start_ms:.0f}ms lead={audio_lead_ms:+.0f}ms "
                    f"target={audio_seek_ms:.0f}ms fragment={ap.entry.time_ms:.0f}ms "
                    f"residual={audio_seek_ms - ap.entry.time_ms:.0f}ms"
                )
        except Exception as exc:
            self.log(f"[audio-sidx] probe failed: {exc}")
        headers = "User-Agent: " + probe.USER_AGENT + "\r\nReferer: https://www.bilibili.com/\r\nOrigin: https://www.bilibili.com\r\n"
        if selection.sessdata:
            headers += "Cookie: SESSDATA=" + selection.sessdata + "\r\n"
        cmd = [ffplay, "-nodisp", "-autoexit", "-loglevel", "warning", "-headers", headers,
               "-ss", f"{audio_seek_ms / 1000.0:.3f}", audio_url]
        try:
            self.audio_proc = subprocess.Popen(cmd)
            self.log(f"[audio] ffplay start @ {audio_seek_ms:.0f}ms lead={audio_lead_ms:+.0f}ms")
        except Exception as exc:
            self.log(f"[audio] failed: {exc}")

    def _ui_tick(self) -> None:
        self._drain_logs()
        selection = self.selection
        expected = self.timeline.media_ms()
        video_delay_ms = float(self.video_delay_var.get())
        video_expected = max(0.0, expected - video_delay_ms)
        frame = self._poll_best_frame(video_expected, self.args.early_ms)
        if frame:
            try:
                self.current_image = tk.PhotoImage(data=frame.ppm, format="PPM")
                self.canvas.create_image(0, 0, anchor=tk.NW, image=self.current_image)
                self.last_frame = frame
                self.uploads += 1
            except Exception as exc:
                self.log(f"[render] PhotoImage failed: {exc}")
        else:
            self.dry_ticks += 1
        self._maybe_restart(video_expected)
        self._update_stats(expected, video_expected)
        self.after(max(1, int(1000 / max(1, self.args.render_fps))), self._ui_tick)

    def _poll_best_frame(self, expected_ms: float, early_ms: float) -> DecodedFrame | None:
        visible_until = expected_ms + max(0.0, early_ms)
        best: DecodedFrame | None = None
        kept: list[DecodedFrame] = []
        while True:
            try:
                item = self.frame_queue.get_nowait()
            except queue.Empty:
                break
            self.decoded_latest_ms = max(self.decoded_latest_ms, item.media_ms)
            if self.last_frame and item.media_ms <= self.last_frame.media_ms:
                self.drops += 1
                continue
            if self.last_frame is None:
                self.log(f"[render] first frame media={item.media_ms:.0f}ms expected={expected_ms:.0f}ms drift={item.media_ms - expected_ms:+.0f}ms")
                for old in kept:
                    try:
                        self.frame_queue.put_nowait(old)
                    except queue.Full:
                        self.drops += 1
                return item
            if item.media_ms <= visible_until:
                if best is None or item.media_ms >= best.media_ms:
                    if best is not None:
                        self.drops += 1
                    best = item
                else:
                    self.drops += 1
            else:
                kept.append(item)
        for item in kept:
            try:
                self.frame_queue.put_nowait(item)
            except queue.Full:
                self.drops += 1
        return best

    def _maybe_restart(self, expected_ms: float) -> None:
        if not self.restart_var.get() or not self.selection or not self.decoder:
            return
        now = time.monotonic_ns()
        if now - self.last_restart_at_ns < self.args.restart_cooldown_ms * 1_000_000:
            return
        displayed = self.last_frame.media_ms if self.last_frame else -1.0
        queue_lag = expected_ms - self.decoded_latest_ms if self.decoded_latest_ms >= 0 else 0.0
        display_lag = expected_ms - displayed if displayed >= 0 else 0.0
        if displayed >= 0 and display_lag > self.args.hard_resync_ms and queue_lag > self.args.chase_window_ms:
            self.log(f"[restart-sim] displayLag={display_lag:.0f}ms queueLag={queue_lag:.0f}ms -> restart decoder")
            self.restarts += 1
            self.last_restart_at_ns = now
            if self.decoder:
                self.decoder.close()
            self._clear_queue()
            self.decoded_latest_ms = -1.0
            ffmpeg = self.args.ffmpeg or shutil.which("ffmpeg")
            video_url = self.selection.video.get("baseUrl") or self.selection.video.get("base_url")
            decoder_start_ms = self._fragment_start_ms(self.selection, expected_ms)
            self.decoder = VideoDecoderThread(ffmpeg, video_url, self.selection.sessdata, decoder_start_ms,
                                              self.selection.fps, self.frame_queue, self.stop_event, self.log_queue)
            self.decoder.start()

    def _fragment_start_ms(self, selection: StreamSelection, requested_ms: float) -> float:
        if self.args.no_sidx_seek:
            return requested_ms
        try:
            p = probe.probe_segment_base(selection.video, selection.sessdata, requested_ms / 1000.0,
                                         16000, int(round(selection.fps)), "video", "vide")
            if p.entry and p.entry.time_ms <= requested_ms + 50.0:
                residual = requested_ms - p.entry.time_ms
                self.log(f"[sidx] video fragment entry={p.entry.time_ms:.0f}ms requested={requested_ms:.0f}ms residual={residual:.0f}ms bytes={p.entry.byte_start}-{p.entry.byte_end}")
                return max(0.0, p.entry.time_ms)
        except Exception as exc:
            self.log(f"[sidx] probe failed, direct seek: {exc}")
        return requested_ms

    def _update_stats(self, expected_ms: float, video_expected_ms: float) -> None:
        displayed = self.last_frame.media_ms if self.last_frame else -1.0
        video_drift = displayed - expected_ms if displayed >= 0 else math.nan
        video_scheduler_drift = displayed - video_expected_ms if displayed >= 0 else math.nan
        audio_latency_ms = max(0.0, float(self.audio_latency_var.get()))
        audio_lead_ms = float(self.audio_lead_var.get())
        audio_current = max(0.0, expected_ms + audio_lead_ms - audio_latency_ms)
        audio_drift = audio_current - expected_ms
        pts_delta = math.nan
        if self.selection and self.selection.video_first_pts_ms is not None and self.selection.audio_first_pts_ms is not None:
            pts_delta = self.selection.audio_first_pts_ms - self.selection.video_first_pts_ms
        subtitle_delay_ms = float(self.subtitle_delay_var.get())
        subtitle_expected = max(0.0, expected_ms - subtitle_delay_ms)
        sub = self._active_subtitle(subtitle_expected)
        queued = self.frame_queue.qsize()
        self.stats_var.set(
            f"local/server expected : {expected_ms:,.0f} ms\n"
            f"video expected        : {video_expected_ms:,.0f} ms\n"
            f"video displayed      : {displayed:,.0f} ms\n"
            f"video drift          : {video_drift:+,.0f} ms\n"
            f"video scheduler drift: {video_scheduler_drift:+,.0f} ms\n"
            f"subtitle expected    : {subtitle_expected:,.0f} ms\n"
            f"video decoded latest : {self.decoded_latest_ms:,.0f} ms\n"
            f"audio current        : {audio_current:,.0f} ms\n"
            f"audio drift          : {audio_drift:+,.0f} ms\n"
            f"audio lead/latency   : {audio_lead_ms:+,.0f}/{audio_latency_ms:,.0f} ms\n"
            f"fMP4 audio-video PTS : {pts_delta:+,.0f} ms\n"
            f"queue                : {queued}/{self.args.queue_capacity}\n"
            f"uploads/drops/dry    : {self.uploads}/{self.drops}/{self.dry_ticks}\n"
            f"restart-sim count    : {self.restarts}\n"
            f"paused               : {self.timeline.paused}"
        )
        self.subtitle_var.set("字幕：" + (sub or "-"))

    def _active_subtitle(self, media_ms: float) -> str:
        selection = self.selection
        if not selection:
            return ""
        for line in selection.subtitles:
            if line.start_ms <= media_ms <= line.end_ms:
                return f"{line.start_ms:.0f}-{line.end_ms:.0f}ms  {line.content}"
        return ""

    def toggle_pause(self) -> None:
        self.timeline.set_paused(not self.timeline.paused)

    def stop_playback(self) -> None:
        self.stop_event.set()
        if self.decoder:
            self.decoder.close()
            self.decoder = None
        if self.audio_proc and self.audio_proc.poll() is None:
            self.audio_proc.terminate()
        self.audio_proc = None
        self._clear_queue()

    def destroy(self) -> None:  # type: ignore[override]
        self.stop_playback()
        super().destroy()

    def _clear_queue(self) -> None:
        while True:
            try:
                self.frame_queue.get_nowait()
            except queue.Empty:
                break

    def log(self, message: str) -> None:
        self.log_queue.put(message)

    def _drain_logs(self) -> None:
        while True:
            try:
                msg = self.log_queue.get_nowait()
            except queue.Empty:
                break
            self.log_text.configure(state=tk.NORMAL)
            self.log_text.insert(tk.END, msg + "\n")
            self.log_text.see(tk.END)
            self.log_text.configure(state=tk.DISABLED)

    @staticmethod
    def _parse_fps(raw: str) -> float:
        try:
            if "/" in raw:
                a, b = raw.split("/", 1)
                return float(a) / max(1.0, float(b))
            return float(raw)
        except Exception:
            return 30.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GUI simulation of the Bilibili video/audio timeline used by the mod.")
    parser.add_argument("video", nargs="?", default=DEFAULT_VIDEO)
    parser.add_argument("--page", "-p", type=int, default=2)
    parser.add_argument("--quality", "-q", type=int, default=116)
    parser.add_argument("--codec-id", type=int, default=7)
    parser.add_argument("--start", type=float, default=5317.2, help="start media time in seconds")
    parser.add_argument("--sessdata", default="")
    parser.add_argument("--ffmpeg", default="")
    parser.add_argument("--ffplay", default="")
    parser.add_argument("--no-audio", action="store_true")
    parser.add_argument("--auto-start", action="store_true")
    parser.add_argument("--queue-capacity", type=int, default=8)
    parser.add_argument("--early-ms", type=float, default=12.0)
    parser.add_argument("--render-fps", type=int, default=60)
    parser.add_argument("--audio-latency-ms", type=float, default=0.0,
                        help="diagnostic-only GUI audio output latency model; default 0 because the current bug is audio lagging video")
    parser.add_argument("--audio-lead-ms", type=float, default=0.0,
                        help="advance audio media time by this many milliseconds to compensate output/startup lag; use about 1200 if audio is 1.2s behind")
    parser.add_argument("--video-delay-ms", type=float, default=0.0,
                        help="delay video scheduling by this many milliseconds; negative values advance video")
    parser.add_argument("--subtitle-delay-ms", type=float, default=0.0,
                        help="delay subtitle lookup by this many milliseconds; negative values advance subtitles")
    parser.add_argument("--simulate-restart", action="store_true",
                        help="simulate the Java session restart policy when display and queue both lag")
    parser.add_argument("--no-sidx-seek", action="store_true",
                        help="disable SegmentBase/sidx fragment-start seek simulation")
    parser.add_argument("--chase-window-ms", type=float, default=2500.0)
    parser.add_argument("--hard-resync-ms", type=float, default=5000.0)
    parser.add_argument("--restart-cooldown-ms", type=float, default=8000.0)
    return parser.parse_args()


def fmt_optional(value: float | None) -> str:
    return "n/a" if value is None or math.isnan(value) else f"{value:.0f}"


def fmt_optional_delta(a: float | None, b: float | None) -> str:
    if a is None or b is None or math.isnan(a) or math.isnan(b):
        return "n/a"
    return f"{a - b:+.0f}"


def main() -> int:
    args = parse_args()
    if not shutil.which("ffmpeg") and not args.ffmpeg:
        print("ffmpeg not found; install FFmpeg or pass --ffmpeg", file=sys.stderr)
        return 2
    app = TimelineGui(args)
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())