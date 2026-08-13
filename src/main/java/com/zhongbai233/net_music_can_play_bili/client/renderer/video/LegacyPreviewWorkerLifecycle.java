package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/**
 * Legacy singleton 预览解码 worker 的 generation 与资源所有权。
 *
 * <p>worker 和 decoder 只能绑定到当前活动 generation；stop 会原子剥离两者，迟到绑定或
 * completion 不能修改后续 generation。</p>
 */
final class LegacyPreviewWorkerLifecycle<W, D> {
    static final long REJECTED_GENERATION = -1L;

    private volatile boolean started;
    private volatile boolean running;
    private volatile long generation;
    private volatile W worker;
    private volatile D decoder;

    synchronized long tryBegin() {
        if (started) {
            return REJECTED_GENERATION;
        }
        generation++;
        started = true;
        running = true;
        worker = null;
        decoder = null;
        return generation;
    }

    synchronized boolean bindWorker(long candidateGeneration, W candidate) {
        if (!isActive(candidateGeneration) || candidate == null || worker != null) {
            return false;
        }
        worker = candidate;
        return true;
    }

    synchronized boolean bindDecoder(long candidateGeneration, D candidate) {
        if (!isActive(candidateGeneration) || candidate == null || decoder != null) {
            return false;
        }
        decoder = candidate;
        return true;
    }

    synchronized Detached<W, D> stopAndDetach() {
        Detached<W, D> detached = new Detached<>(generation, worker, decoder);
        generation++;
        started = false;
        running = false;
        worker = null;
        decoder = null;
        return detached;
    }

    synchronized void requestStop() {
        running = false;
    }

    synchronized boolean finish(long candidateGeneration, D completedDecoder) {
        if (decoder == completedDecoder) {
            decoder = null;
        }
        if (candidateGeneration != generation) {
            return false;
        }
        started = false;
        running = false;
        worker = null;
        return true;
    }

    boolean isActive(long candidateGeneration) {
        return running && candidateGeneration == generation;
    }

    boolean isCurrent(long candidateGeneration) {
        return candidateGeneration == generation;
    }

    boolean isStarted() {
        return started;
    }

    boolean isRunning() {
        return running;
    }

    Snapshot<W, D> snapshot() {
        return new Snapshot<>(started, running, generation, worker, decoder);
    }

    record Detached<W, D>(long generation, W worker, D decoder) {
    }

    record Snapshot<W, D>(boolean started, boolean running, long generation, W worker, D decoder) {
    }
}
