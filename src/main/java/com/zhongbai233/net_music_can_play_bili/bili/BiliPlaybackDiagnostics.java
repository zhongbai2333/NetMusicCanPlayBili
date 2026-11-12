package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import javax.sound.sampled.AudioFormat;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 最近一次 NetMusic/B 站播放的轻量级客户端快照。
 * 供命令查询使用，避免常规播放持续刷日志。
 */
public final class BiliPlaybackDiagnostics {
    private static volatile Snapshot current = Snapshot.empty();
    private static long nextOccurrence;
    private static Occurrence ownership;

    private BiliPlaybackDiagnostics() {
    }

    public static synchronized void beginPlayback(String songName, String sourceUrl, String resolvedUrl) {
        String cleanSongName = clean(songName, "未知歌曲");
        ownership = new Occurrence(++nextOccurrence, tokenOf(resolvedUrl), PlaybackSync.strip(clean(resolvedUrl,"unknown")));
        current = new Snapshot(
                cleanSongName,
                clean(sourceUrl, "unknown"),
                ownership.resolvedUrl,
                "resolving",
                "unknown",
                null,
                "",
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                false,
                "");
    }

    public static void updateFormat(URL url, String container, String codec, AudioFormat format, String detail) {
        synchronized (BiliPlaybackDiagnostics.class) {
            Worker worker = callbackWorker(url);
            if (worker != null) updateFormat(worker, url, container, codec, format, detail);
        }
    }

    static synchronized void updateFormat(Worker worker, URL url, String container, String codec, AudioFormat format, String detail) {
        if (!owns(worker)) return;
        Snapshot old = current;
        String cleanContainer = clean(container, "unknown");
        String cleanCodec = clean(codec, "unknown");
        String cleanDetail = detail != null ? detail : "";
        current = new Snapshot(
                old.songName(),
                old.sourceUrl(),
                url != null ? url.toString() : old.resolvedUrl(),
                cleanContainer,
                cleanCodec,
                format,
                cleanDetail,
                old.startedAtMillis(),
                System.currentTimeMillis(),
                false,
                old.lastError());
    }

    public static void markClosed(URL url) {
        synchronized (BiliPlaybackDiagnostics.class) {
            Worker worker = callbackWorker(url);
            if (worker != null) markClosed(worker);
        }
    }

    static synchronized void markClosed(Worker worker) {
        if (!currentPhase(worker) || worker.occurrence.terminal) return;
        worker.occurrence.terminal = true;
        if (ownership != worker.occurrence) return;
        Snapshot old = current;
        String resolvedUrl = old.resolvedUrl();
        current = new Snapshot(
                old.songName(),
                old.sourceUrl(),
                resolvedUrl,
                old.container(),
                old.codec(),
                old.format(),
                old.detail(),
                old.startedAtMillis(),
                System.currentTimeMillis(),
                true,
                old.lastError());
    }

    public static void markFailed(URL url, Throwable error) {
        synchronized (BiliPlaybackDiagnostics.class) {
            Worker worker = callbackWorker(url);
            if (worker != null) markFailed(worker, error);
        }
    }

    static synchronized void markFailed(Worker worker, Throwable error) {
        if (!currentPhase(worker) || worker.occurrence.terminal) return;
        worker.occurrence.terminal = true;
        worker.occurrence.failure = error != null ? error : new IllegalStateException("unknown playback failure");
        if (ownership != worker.occurrence) return;
        Snapshot old = current;
        String resolvedUrl = old.resolvedUrl();
        String message = error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage();
        current = new Snapshot(
                old.songName(),
                old.sourceUrl(),
                resolvedUrl,
                old.container(),
                old.codec(),
                old.format(),
                old.detail(),
                old.startedAtMillis(),
                System.currentTimeMillis(),
                true,
                clean(message, "unknown"));
    }

    /** Capture identity before the one-shot request URL is stripped; never adopt a clean URL as a token owner. */
    static synchronized Worker beginHttpWorker(URL original, String session, String source, String owner, String minecart) {
        String value = original.toString();
        Occurrence occurrence = matches(original) ? ownership
                : new Occurrence(++nextOccurrence, tokenOf(value), PlaybackSync.strip(value));
        if (!occurrence.terminal) occurrence.phase++;
        return new Worker(occurrence, occurrence.phase,
                new Context(occurrence.token, clean(session,""),clean(source,""),clean(owner,""),clean(minecart,"")));
    }

    /** A fallback owns a new phase even when it belongs to the same physical request. */
    static synchronized Worker beginFallback(Worker probe) {
        if (!currentPhase(probe) || probe.occurrence.terminal) return probe;
        return new Worker(probe.occurrence, ++probe.occurrence.phase, probe.context);
    }

    static synchronized boolean terminalOrRetired(Worker worker) {
        return !currentPhase(worker) || worker.occurrence.terminal;
    }

    static synchronized Throwable failure(Worker worker) {
        return currentPhase(worker) ? worker.occurrence.failure : null;
    }

    static synchronized void cancelRequest(MediaRequestToken token) {
        if (token != null && ownership != null && token.value().equals(ownership.token)) {
            markClosed(new Worker(ownership,ownership.phase,Context.empty(ownership.token)));
        }
    }

    static synchronized void closePendingRequest(java.util.function.Predicate<MediaRequestToken> ownsPending) {
        if (ownership != null && MediaRequestToken.parse(ownership.token).filter(ownsPending).isPresent()) {
            markClosed(new Worker(ownership,ownership.phase,Context.empty(ownership.token)));
        }
    }

    private static boolean currentPhase(Worker worker) {
        return worker != null && worker.phase == worker.occurrence.phase;
    }

    private static boolean owns(Worker worker) {
        return currentPhase(worker) && ownership == worker.occurrence && !worker.occurrence.terminal;
    }

    private static Worker callbackWorker(URL url) {
        return matches(url) ? new Worker(ownership,ownership.phase,Context.empty(ownership.token)) : null;
    }

    private static boolean matches(URL url) {
        if (ownership == null || url == null) return false;
        String token = tokenOf(url.toString());
        if (!ownership.token.isEmpty() || !token.isEmpty()) return !token.isEmpty() && token.equals(ownership.token);
        return PlaybackSync.strip(url.toString()).equals(ownership.resolvedUrl);
    }

    private static String tokenOf(String url) {
        return PlaybackSync.parseMediaRequestToken(url).map(MediaRequestToken::value).orElse("");
    }

    static final class Worker {
        final Occurrence occurrence;
        final long phase;
        final Context context;
        private Worker(Occurrence occurrence,long phase,Context context) {
            this.occurrence=occurrence;this.phase=phase;this.context=context;
        }
    }

    private static final class Occurrence {
        final long id;
        final String token;
        final String resolvedUrl;
        long phase;
        boolean terminal;
        Throwable failure;
        Occurrence(long id,String token,String resolvedUrl) {this.id=id;this.token=token;this.resolvedUrl=resolvedUrl;}
    }

    record Context(String requestToken,String session,String source,String owner,String minecart) {
        static Context empty(String token) {return new Context(token,"","","","");}
    }

    public static List<String> describeCurrentPlayback() {
        Snapshot s = current;
        List<String> lines = new ArrayList<>();
        if (s.startedAtMillis() == 0L) {
            lines.add("当前没有播放诊断信息");
            return lines;
        }

        lines.add("歌曲: " + s.songName());
        lines.add("状态: " + (s.closed() ? "已关闭/最近播放" : "播放中"));
        lines.add("容器/编码: " + s.container() + " / " + s.codec());
        if (s.format() != null) {
            AudioFormat f = s.format();
            lines.add(String.format("音频格式: %s, %.0f Hz, %d ch, %d bit",
                    f.getEncoding(), f.getSampleRate(), f.getChannels(), f.getSampleSizeInBits()));
        } else {
            lines.add("音频格式: 尚未识别");
        }
        if (!s.detail().isBlank()) {
            lines.add("细节: " + s.detail());
        }
        lines.add("直链主机: " + hostOf(s.resolvedUrl()));
        if (!s.lastError().isBlank()) {
            lines.add("最近错误: " + s.lastError());
        }
        return lines;
    }

    public static String currentCodecSummary() {
        Snapshot s = current;
        return s.container() + " / " + s.codec();
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String hostOf(String value) {
        if (value == null || value.isBlank() || "unknown".equals(value)) {
            return "unknown";
        }
        try {
            String host = URI.create(value).getHost();
            return host != null ? host : value;
        } catch (Exception ignored) {
            return value;
        }
    }

    private record Snapshot(
            String songName,
            String sourceUrl,
            String resolvedUrl,
            String container,
            String codec,
            AudioFormat format,
            String detail,
            long startedAtMillis,
            long updatedAtMillis,
            boolean closed,
            String lastError) {
        static Snapshot empty() {
            return new Snapshot("", "", "", "unknown", "unknown", null, "", 0L, 0L, true, "");
        }
    }
}
