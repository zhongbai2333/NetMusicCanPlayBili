package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns native packet submission, frame draining, first-frame probing, and output queues. */
final class Fmp4NativeVideoDecodePump {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CODEC_AV1 = 13;
    private static final Fmp4NativeVideoProperties.Decoder PROPERTIES = Fmp4NativeVideoProperties.decoder();
    private static final Fmp4NativeVideoProperties.Seek SEEK = Fmp4NativeVideoProperties.seek();
    private static final Fmp4NativeVideoProperties.FirstFrameProbe FIRST_FRAME_PROBE =
            Fmp4NativeVideoProperties.firstFrameProbe();
    private static final int MAX_PENDING_FRAMES = PROPERTIES.maxPendingFrames();
    private static final long SAFE_NO_COPY_DROP_GUARD_NANOS = Math.max(0L,
            SEEK.noCopyDropGuardMillis() * 1_000_000L);
    private static final boolean REUSE_OUTPUT_BUFFERS = PROPERTIES.reuseOutputBuffers();
    private static final boolean DIRECT_NV12_BUFFERS = PROPERTIES.directNv12Buffers();
    private static final byte[] DECODE_ONLY_FRAME = new byte[0];

    private final VideoNativeDecoder decoder;
    private final int codecId;
    private final int targetWidth;
    private final int targetHeight;
    private final int maxFrames;
    private final boolean outputFrames;
    private final Fmp4NativeVideoDecoder.OutputFormat outputFormat;
    private final long startOffsetMillis;
    private final int fps;
    private final AtomicBoolean closed;
    private final BlockingQueue<QueuedDecodedFrame> frames = new ArrayBlockingQueue<>(MAX_PENDING_FRAMES);
    private final BlockingQueue<byte[]> reusableBuffers = new ArrayBlockingQueue<>(MAX_PENDING_FRAMES);
    private final NativeNv12BufferPool nativeNv12Buffers = new NativeNv12BufferPool(MAX_PENDING_FRAMES);
    private final ArrayDeque<Long> pendingDecodedPtsNanos = new ArrayDeque<>();

    private byte[] decoderConfig;
    private int nalLengthSize = 4;
    private boolean sentConfig;
    private int totalFrames;
    private int fallbackFramesToDrop;
    private long timelineStartNanos;
    private long dropBeforeMediaPtsNanos;
    private long lastDecodedMediaPtsNanos = -1L;
    private int parsedMoofCount;
    private volatile int sentPacketCount;
    private volatile Av1FirstFrameProbe activeFirstFrameProbe;
    private volatile Thread firstFrameProbeConsumer;
    private boolean decoderStageLogged;
    private int receivedFrameCount;
    private int droppedFrameCount;
    private boolean dropStageLogged;
    private boolean outputStageLogged;

    Fmp4NativeVideoDecodePump(VideoNativeDecoder decoder, int codecId, int targetWidth, int targetHeight,
            int maxFrames, boolean outputFrames, Fmp4NativeVideoDecoder.OutputFormat outputFormat,
            long startOffsetMillis, int fps, AtomicBoolean closed) {
        this.decoder = decoder;
        this.codecId = codecId;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.maxFrames = Math.max(1, maxFrames);
        this.outputFrames = outputFrames;
        this.outputFormat = outputFormat;
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
        this.fps = Math.max(1, fps);
        this.closed = closed;
    }

    synchronized void beginFirstFrameProbe(boolean workerStarted) throws IOException {
        if (codecId != CODEC_AV1) {
            throw new IOException("AV1 首帧预算只能用于 AV1 decoder: codecId=" + codecId);
        }
        Thread currentConsumer = Thread.currentThread();
        if (firstFrameProbeConsumer != null && firstFrameProbeConsumer != currentConsumer) {
            throw new IOException("AV1 首帧探测只允许单消费者");
        }
        firstFrameProbeConsumer = currentConsumer;
        if (activeFirstFrameProbe == null && workerStarted) {
            throw new IOException("AV1 首帧预算必须在 decoder worker 启动前设置");
        }
        if (activeFirstFrameProbe == null) {
            activeFirstFrameProbe = new Av1FirstFrameProbe(System.nanoTime(),
                    FIRST_FRAME_PROBE.timeoutMillis(), FIRST_FRAME_PROBE.maxPackets());
        }
    }

    void requireRegularGetter() throws IOException {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe == null) {
            return;
        }
        Av1FirstFrameProbe.Decision decision = probe.decision();
        if (decision != Av1FirstFrameProbe.Decision.COMMITTED
                && decision != Av1FirstFrameProbe.Decision.CANCELLED) {
            throw new IOException("AV1 首帧探测进行中不允许使用无界 getter");
        }
    }

    void commitFirstFrame(Fmp4NativeVideoDecoder.DecodedFrame frame) throws IOException {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        long ticket = frame != null ? frame.probeTicket() : -1L;
        if (probe == null || !probe.commit(ticket)) {
            throw new IOException("AV1 首帧候选提交已失效: ticket=" + ticket);
        }
    }

    void rejectFirstFrame(Fmp4NativeVideoDecoder.DecodedFrame frame) throws IOException {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        long ticket = frame != null ? frame.probeTicket() : -1L;
        if (probe == null) {
            throw new IOException("AV1 首帧候选拒绝已失效: ticket=" + ticket);
        }
        if (probe.reject(ticket) || probe.decision() == Av1FirstFrameProbe.Decision.CANCELLED) {
            return;
        }
        throw new IOException("AV1 首帧候选拒绝已失效: ticket=" + ticket);
    }

    Fmp4NativeVideoDecoder.DecodedFrame awaitNextFrame(Runnable ensureStarted, BooleanSupplier finished,
            Supplier<IOException> failure, Runnable cancel) throws IOException {
        ensureStarted.run();
        long waitStartNs = System.nanoTime();
        while (true) {
            QueuedDecodedFrame ready = frames.poll();
            if (ready != null) {
                return acceptQueuedFrame(ready, waitStartNs);
            }
            if (finished.getAsBoolean()) {
                IOException decodeFailure = failure.get();
                if (decodeFailure != null) {
                    throw decodeFailure;
                }
                return null;
            }
            if (closed.get()) {
                return null;
            }
            IOException budgetFailure = firstFrameBudgetFailureIfIdle();
            if (budgetFailure != null) {
                cancel.run();
                throw budgetFailure;
            }
            try {
                ready = frames.poll(nextFramePollNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (closed.get() || finished.getAsBoolean()) {
                    return null;
                }
                throw new IOException("等待 native 视频帧时被中断", e);
            }
            if (ready != null) {
                return acceptQueuedFrame(ready, waitStartNs);
            }
        }
    }

    void configure(Fmp4NativeVideoDecoder.DecoderConfig config) {
        decoderConfig = config.packetPrefix();
        nalLengthSize = config.nalLengthSize();
    }

    boolean isConfigured() {
        return decoderConfig != null;
    }

    void setSeekWindow(float residualSeconds, double fragmentSeconds, long requestedOffsetMillis) {
        fallbackFramesToDrop = Math.max(0, Math.round(residualSeconds * fps));
        timelineStartNanos = Math.max(0L, Math.round(fragmentSeconds * 1_000_000_000.0D));
        dropBeforeMediaPtsNanos = Math.max(0L, requestedOffsetMillis * 1_000_000L);
    }

    void setParsedMoofCount(int parsedMoofCount) {
        this.parsedMoofCount = parsedMoofCount;
    }

    void resetAfterRecovery() {
        pendingDecodedPtsNanos.clear();
        fallbackFramesToDrop = 0;
        timelineStartNanos = 0L;
        dropBeforeMediaPtsNanos = 0L;
        sentConfig = false;
        decoder.flush();
    }

    long estimateCurrentOffsetMillis(long totalMillis) {
        if (lastDecodedMediaPtsNanos >= 0L) {
            long offset = lastDecodedMediaPtsNanos / 1_000_000L;
            return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
        }
        long decodedMillis = Math.round(totalFrames * 1000.0D / fps);
        long offset = Math.max(0L, startOffsetMillis + decodedMillis);
        return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
    }

    int totalFrames() {
        return totalFrames;
    }

    boolean hasActiveUncommittedProbe() {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe == null) {
            return false;
        }
        Av1FirstFrameProbe.Decision decision = probe.decision();
        return decision == Av1FirstFrameProbe.Decision.CONTINUE
                || decision == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT
                || decision == Av1FirstFrameProbe.Decision.FRAME_PENDING;
    }

    void decodeSample(byte[] mp4Sample, long samplePtsNanos) throws IOException {
        if (mp4Sample.length == 0 || totalFrames >= maxFrames) {
            return;
        }
        byte[] packet = packetizeSample(mp4Sample);
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        Av1FirstFrameProbe.PacketPermit packetPermit = null;
        if (probe != null) {
            Av1FirstFrameProbe.PacketAdmission admission = probe.beginPacketNow();
            if (admission.admitted()) {
                packetPermit = admission.permit();
            } else if (!admission.bypassedAfterCommit()) {
                Av1FirstFrameProbe.Decision decision = admission.decision();
                if (decision == Av1FirstFrameProbe.Decision.CANCELLED || closed.get()) {
                    return;
                }
                if (isProbeFailure(decision)) {
                    throw firstFrameProbeFailure(decision, probe,
                            Math.max(0L, System.nanoTime() - probe.startedNanos()));
                }
                throw new IOException("AV1 首帧探测拒绝新媒体包: decision=" + decision);
            }
        }
        boolean sent;
        try {
            sent = decoder.sendPacket(packet, samplePtsNanos);
        } catch (RuntimeException | Error error) {
            if (probe != null && packetPermit != null) {
                probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
            }
            throw error;
        }
        if (!sent) {
            if (probe != null && packetPermit != null) {
                probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.SEND_REJECTED);
            }
            LOGGER.error(
                    "视频 native sendPacket 失败: codecId={} sampleBytes={} packetBytes={} ptsNanos={} sentPackets={} parsedMoofs={}",
                    codecId, mp4Sample.length, packet.length, samplePtsNanos, sentPacketCount, parsedMoofCount);
            throw new IOException("VideoNativeDecoder.sendPacket failed codecId=" + codecId + ", packet=" + packet.length);
        }
        sentPacketCount++;
        if (probe != null && packetPermit != null) {
            Av1FirstFrameProbe.PacketTransition transition = probe.markPacketSent(packetPermit);
            if (!transition.applied()) {
                probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
                throw new IOException("AV1 首帧探测 packet permit 已失效: ordinal="
                        + packetPermit.ordinal() + ", decision=" + transition.decision());
            }
            if (transition.decision() == Av1FirstFrameProbe.Decision.CANCELLED || closed.get()) {
                probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
                return;
            }
        }
        if (sentPacketCount <= 3) {
            LOGGER.debug("视频 native packet 已发送: index={} sampleBytes={} packetBytes={} ptsNanos={}",
                    sentPacketCount, mp4Sample.length, packet.length, samplePtsNanos);
        }
        pendingDecodedPtsNanos.addLast(samplePtsNanos);
        try {
            drainFrames(packetPermit);
        } finally {
            if (probe != null && packetPermit != null) {
                probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
            }
        }
    }

    void drainNaturalEndOfStream() throws IOException {
        if (closed.get() || totalFrames >= maxFrames) {
            return;
        }
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        Av1FirstFrameProbe.PacketPermit eofPermit = null;
        if (probe != null && probe.decision() != Av1FirstFrameProbe.Decision.COMMITTED) {
            Av1FirstFrameProbe.PacketAdmission admission = probe.beginEndOfStreamDrainNow();
            if (!admission.admitted()) {
                if (isProbeFailure(admission.decision())) {
                    throw firstFrameProbeFailure(admission.decision(), probe,
                            Math.max(0L, System.nanoTime() - probe.startedNanos()));
                }
                if (admission.decision() == Av1FirstFrameProbe.Decision.CANCELLED) {
                    return;
                }
                throw new IOException("AV1 EOF drain 无法取得首帧探测 lease: decision=" + admission.decision());
            }
            eofPermit = admission.permit();
        }
        if (!decoder.sendEndOfStream()) {
            if (probe != null && eofPermit != null) {
                probe.endPacket(eofPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
            }
            LOGGER.debug("当前 native bundle 不支持视频 EOF drain；保持 v38 兼容行为");
            return;
        }
        try {
            drainFrames(eofPermit);
        } finally {
            if (probe != null && eofPermit != null) {
                probe.endPacket(eofPermit, Av1FirstFrameProbe.PacketEnd.DRAINED);
            }
        }
    }

    void cancelProbe() {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe != null) {
            probe.cancel();
        }
    }

    void releaseResources() {
        QueuedDecodedFrame queued;
        while ((queued = frames.poll()) != null) {
            queued.frame().close();
        }
        reusableBuffers.clear();
        nativeNv12Buffers.retire();
    }

    private Fmp4NativeVideoDecoder.DecodedFrame acceptQueuedFrame(QueuedDecodedFrame queued, long waitStartNanos) {
        return queued.frame().withProbeTicket(queued.probeTicket())
                .withQueueWaitNanos(System.nanoTime() - waitStartNanos);
    }

    private long nextFramePollNanos() {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        Av1FirstFrameProbe.Decision decision = probe != null ? probe.decision() : null;
        if (probe == null || decision == Av1FirstFrameProbe.Decision.COMMITTED) {
            return TimeUnit.MILLISECONDS.toNanos(250L);
        }
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(probe.timeoutMillis());
        long remainingNanos = timeoutNanos - Math.max(0L, System.nanoTime() - probe.startedNanos());
        if (remainingNanos <= 0L
                && (decision == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT
                        || decision == Av1FirstFrameProbe.Decision.FRAME_PENDING)) {
            return TimeUnit.MILLISECONDS.toNanos(50L);
        }
        return Math.max(1L, Math.min(TimeUnit.MILLISECONDS.toNanos(50L), remainingNanos));
    }

    private IOException firstFrameBudgetFailureIfIdle() {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe == null) {
            return null;
        }
        Av1FirstFrameProbe.Decision decision = probe.evaluateConsumerTimeNow();
        long elapsedNanos = Math.max(0L, System.nanoTime() - probe.startedNanos());
        return isProbeFailure(decision) ? firstFrameProbeFailure(decision, probe, elapsedNanos) : null;
    }

    private IOException firstFrameProbeFailure(Av1FirstFrameProbe.Decision decision,
            Av1FirstFrameProbe probe, long elapsedNanos) {
        String exhausted = decision == Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED ? "packet" : "time";
        return new IOException("AV1 首帧探测预算耗尽: exhausted=" + exhausted
                + ", elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
                + ", sentPackets=" + probe.successfulPackets()
                + ", timeoutMs=" + probe.timeoutMillis()
                + ", maxPackets=" + probe.maxPackets());
    }

    private static boolean isProbeFailure(Av1FirstFrameProbe.Decision decision) {
        return decision == Av1FirstFrameProbe.Decision.TIME_EXHAUSTED
                || decision == Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED;
    }

    private byte[] packetizeSample(byte[] mp4Sample) throws IOException {
        if (codecId == CODEC_AV1) {
            if (sentConfig || decoderConfig.length == 0) {
                sentConfig = true;
                return mp4Sample;
            }
            ByteArrayOutputStream obu = new ByteArrayOutputStream(decoderConfig.length + mp4Sample.length);
            obu.write(decoderConfig);
            obu.write(mp4Sample);
            sentConfig = true;
            return obu.toByteArray();
        }
        ByteArrayOutputStream annexB = new ByteArrayOutputStream(mp4Sample.length + decoderConfig.length + 32);
        if (!sentConfig || Fmp4VideoDecoderConfigParser.isH264KeyframeSample(mp4Sample, nalLengthSize)) {
            annexB.write(decoderConfig);
            sentConfig = true;
        }
        Fmp4VideoDecoderConfigParser.writeLengthPrefixedSampleAsAnnexB(mp4Sample, nalLengthSize, annexB);
        return annexB.toByteArray();
    }

    private void drainFrames(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
        if (!outputFrames) {
            drainFramesNoOutput(packetPermit);
            return;
        }
        while (!closed.get() && (packetPermit != null || totalFrames < maxFrames)) {
            if (drainDropFrameNoOutput(packetPermit)) {
                continue;
            }
            long nativeStartNs = System.nanoTime();
            Fmp4NativeVideoDecoder.DecodedFrame frame = getNextOutputFrame();
            long frameReadyNanos = System.nanoTime();
            long nativeGetNs = System.nanoTime() - nativeStartNs;
            if (frame == null) {
                if (!decoderStageLogged && sentPacketCount <= 3) {
                    decoderStageLogged = true;
                    LOGGER.debug("视频 native 暂无输出帧: sentPackets={} parsedMoofs={} pendingPts={} nativeGet={}us",
                            sentPacketCount, parsedMoofCount, pendingDecodedPtsNanos.size(), nativeGetNs / 1_000L);
                }
                finishProbePacketDrain(packetPermit);
                return;
            }
            if (packetPermit != null && totalFrames >= maxFrames) {
                if (!pendingDecodedPtsNanos.isEmpty()) {
                    pendingDecodedPtsNanos.removeFirst();
                }
                frame.close();
                continue;
            }
            boolean enqueued = false;
            boolean discardFrame = false;
            try {
                frame = frame.withNativeGetNanos(nativeGetNs);
                Long fallbackPtsNanos = pendingDecodedPtsNanos.isEmpty() ? null : pendingDecodedPtsNanos.removeFirst();
                long samplePtsNanos = decoder.lastFramePtsNanos();
                if (samplePtsNanos < 0L && fallbackPtsNanos != null) {
                    samplePtsNanos = fallbackPtsNanos;
                }
                long mediaPtsNanos = samplePtsNanos >= 0L ? samplePtsNanos
                        : timelineStartNanos + Math.round((totalFrames + 1) * 1_000_000_000.0D / fps);
                lastDecodedMediaPtsNanos = mediaPtsNanos;
                if (shouldDropDecodedFrame(mediaPtsNanos, samplePtsNanos >= 0L)) {
                    droppedFrameCount++;
                    if (!dropStageLogged && droppedFrameCount <= 3) {
                        dropStageLogged = true;
                        LOGGER.debug(
                                "视频 native 输出帧被目标 PTS 丢弃: dropIndex={} samplePts={}ms target={}ms realPts={} pendingPts={}",
                                droppedFrameCount, samplePtsNanos / 1_000_000L,
                                dropBeforeMediaPtsNanos / 1_000_000L, samplePtsNanos >= 0L,
                                pendingDecodedPtsNanos.size());
                    }
                    continue;
                }
                frame = frame.withPtsNanos(Math.max(0L, mediaPtsNanos - startOffsetMillis * 1_000_000L));
                FrameOffer frameOffer = FrameOffer.notOffered();
                while (!closed.get()) {
                    try {
                        frameOffer = offerDecodedFrame(frame, packetPermit, frameReadyNanos,
                                250L, TimeUnit.MILLISECONDS);
                        if (frameOffer.discarded()) {
                            discardFrame = true;
                            break;
                        }
                        if (frameOffer.offered()) {
                            enqueued = true;
                            break;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (closed.get()) {
                            return;
                        }
                        throw new IOException("等待 AV1 首帧内部队列时被中断", e);
                    }
                }
                if (closed.get()) {
                    return;
                }
                if (discardFrame) {
                    continue;
                }
                boolean provisionalProbeFrame = frameOffer.probeTicket() > 0L;
                if (!provisionalProbeFrame) {
                    totalFrames++;
                }
                if (!awaitProbeFrameDecision(frameOffer.probeTicket())) {
                    return;
                }
                if (provisionalProbeFrame
                        && activeFirstFrameProbe.decision() == Av1FirstFrameProbe.Decision.COMMITTED) {
                    totalFrames++;
                }
            } finally {
                if (!enqueued) {
                    frame.close();
                }
            }
        }
    }

    private boolean drainDropFrameNoOutput(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
        boolean hasPendingRealPts = !pendingDecodedPtsNanos.isEmpty()
                && pendingDecodedPtsNanos.peekFirst() != null && pendingDecodedPtsNanos.peekFirst() >= 0L;
        boolean shouldDropByPts = dropBeforeMediaPtsNanos > 0L && hasPendingRealPts
                && pendingDecodedPtsNanos.peekFirst() + SAFE_NO_COPY_DROP_GUARD_NANOS + 1_000_000L
                        < dropBeforeMediaPtsNanos;
        boolean shouldDropByFallback = fallbackFramesToDrop > 0 && !hasPendingRealPts;
        if (!shouldDropByPts && !shouldDropByFallback) {
            return false;
        }
        if (!decoder.receiveFrameNoCopy()) {
            return false;
        }
        long frameReadyNanos = System.nanoTime();
        receivedFrameCount++;
        Long fallbackPtsNanos = pendingDecodedPtsNanos.isEmpty() ? null : pendingDecodedPtsNanos.removeFirst();
        long samplePtsNanos = decoder.lastFramePtsNanos();
        if (samplePtsNanos < 0L && fallbackPtsNanos != null) {
            samplePtsNanos = fallbackPtsNanos;
        }
        if (samplePtsNanos >= 0L) {
            lastDecodedMediaPtsNanos = samplePtsNanos;
            if (samplePtsNanos + 1_000_000L >= dropBeforeMediaPtsNanos) {
                dropBeforeMediaPtsNanos = 0L;
            }
        }
        if (shouldDropByFallback && fallbackFramesToDrop > 0) {
            fallbackFramesToDrop--;
        }
        acknowledgeDroppedProbeFrame(packetPermit, frameReadyNanos);
        droppedFrameCount++;
        if (!dropStageLogged && droppedFrameCount <= 3) {
            dropStageLogged = true;
            LOGGER.debug(
                    "视频 native 无拷贝输出帧被目标 PTS 丢弃: dropIndex={} samplePts={}ms target={}ms realPts={} pendingPts={}",
                    droppedFrameCount, samplePtsNanos / 1_000_000L,
                    dropBeforeMediaPtsNanos / 1_000_000L, samplePtsNanos >= 0L, pendingDecodedPtsNanos.size());
        }
        return true;
    }

    private void acknowledgeDroppedProbeFrame(Av1FirstFrameProbe.PacketPermit packetPermit,
            long frameReadyNanos) throws IOException {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe == null || packetPermit == null) {
            return;
        }
        Av1FirstFrameProbe.FramePreparation preparation = probe.prepareFrame(packetPermit, frameReadyNanos);
        if (preparation.hasTicket()) {
            probe.reject(preparation.ticket());
        } else if (isProbeFailure(preparation.decision())) {
            throw firstFrameProbeFailure(preparation.decision(), probe,
                    Math.max(0L, System.nanoTime() - probe.startedNanos()));
        }
    }

    private boolean shouldDropDecodedFrame(long mediaPtsNanos, boolean hasRealPts) {
        if (hasRealPts) {
            return dropBeforeMediaPtsNanos > 0L && mediaPtsNanos + 1_000_000L < dropBeforeMediaPtsNanos;
        }
        if (fallbackFramesToDrop > 0) {
            fallbackFramesToDrop--;
            return true;
        }
        return false;
    }

    private Fmp4NativeVideoDecoder.DecodedFrame getNextRgbaFrame() {
        if (!REUSE_OUTPUT_BUFFERS) {
            return Fmp4NativeVideoDecoder.DecodedFrame.wrap(decoder.getVideoFrame(),
                    Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA);
        }
        byte[] buffer = reusableBuffers.poll();
        byte[] output = buffer != null ? buffer : new byte[Math.max(1, targetWidth) * Math.max(1, targetHeight) * 4];
        if (!decoder.getVideoFrameInto(output)) {
            reusableBuffers.offer(output);
            return null;
        }
        return new Fmp4NativeVideoDecoder.DecodedFrame(output,
                Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, () -> reusableBuffers.offer(output));
    }

    private Fmp4NativeVideoDecoder.DecodedFrame getNextYuv420Frame() {
        return Fmp4NativeVideoDecoder.DecodedFrame.wrap(decoder.getVideoFrameYuv420(),
                Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P);
    }

    private Fmp4NativeVideoDecoder.DecodedFrame getNextNv12Frame() {
        if (DIRECT_NV12_BUFFERS) {
            int byteCount = Math.max(1, targetWidth) * Math.max(1, targetHeight) * 3 / 2;
            NativeNv12BufferPool.NativeNv12Buffer slot = nativeNv12Buffers.acquire(byteCount);
            if (slot == null) {
                return null;
            }
            if (!decoder.getVideoFrameNv12Into(slot.buffer())) {
                nativeNv12Buffers.release(slot);
                return null;
            }
            return new Fmp4NativeVideoDecoder.DecodedFrame(slot.buffer(), byteCount,
                    Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12, () -> nativeNv12Buffers.release(slot));
        }
        return Fmp4NativeVideoDecoder.DecodedFrame.wrap(decoder.getVideoFrameNv12(),
                Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12);
    }

    private Fmp4NativeVideoDecoder.DecodedFrame getNextOutputFrame() {
        long startedNs = System.nanoTime();
        if (!outputStageLogged) {
            LOGGER.debug("视频 native 开始取输出帧: format={} target={}x{} received={} dropped={} sent={}",
                    outputFormat, targetWidth, targetHeight, receivedFrameCount, droppedFrameCount, sentPacketCount);
        }
        Fmp4NativeVideoDecoder.DecodedFrame frame = switch (outputFormat) {
            case NV12 -> getNextNv12Frame();
            case YUV420P -> getNextYuv420Frame();
            case RGBA -> getNextRgbaFrame();
        };
        if (!outputStageLogged) {
            outputStageLogged = true;
            LOGGER.debug("视频 native 完成取输出帧: frame={} elapsed={}ms received={} dropped={} sent={}",
                    frame != null, (System.nanoTime() - startedNs) / 1_000_000L,
                    receivedFrameCount, droppedFrameCount, sentPacketCount);
        }
        return frame;
    }

    private void drainFramesNoOutput(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
        while (!closed.get() && (packetPermit != null || totalFrames < maxFrames)) {
            if (!decoder.receiveFrameNoCopy()) {
                finishProbePacketDrain(packetPermit);
                return;
            }
            long frameReadyNanos = System.nanoTime();
            if (packetPermit != null && totalFrames >= maxFrames) {
                if (!pendingDecodedPtsNanos.isEmpty()) {
                    pendingDecodedPtsNanos.removeFirst();
                }
                continue;
            }
            FrameOffer frameOffer = FrameOffer.notOffered();
            boolean discardFrame = false;
            while (!closed.get()) {
                try {
                    frameOffer = offerDecodedFrame(
                            Fmp4NativeVideoDecoder.DecodedFrame.wrap(DECODE_ONLY_FRAME,
                                    Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA),
                            packetPermit, frameReadyNanos, 250L, TimeUnit.MILLISECONDS);
                    if (frameOffer.discarded()) {
                        discardFrame = true;
                        break;
                    }
                    if (frameOffer.offered()) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (closed.get()) {
                        return;
                    }
                    throw new IOException("等待 AV1 首帧内部队列时被中断", e);
                }
            }
            if (closed.get()) {
                return;
            }
            if (discardFrame) {
                continue;
            }
            boolean provisionalProbeFrame = frameOffer.probeTicket() > 0L;
            if (!provisionalProbeFrame) {
                totalFrames++;
            }
            if (!awaitProbeFrameDecision(frameOffer.probeTicket())) {
                return;
            }
            if (provisionalProbeFrame
                    && activeFirstFrameProbe.decision() == Av1FirstFrameProbe.Decision.COMMITTED) {
                totalFrames++;
            }
        }
    }

    private FrameOffer offerDecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame frame,
            Av1FirstFrameProbe.PacketPermit packetPermit, long frameReadyNanos,
            long timeout, TimeUnit unit) throws InterruptedException, IOException {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        long ticket = -1L;
        long probeElapsedNanos = -1L;
        if (probe != null) {
            Av1FirstFrameProbe.FramePreparation preparation = probe.prepareFrame(packetPermit, frameReadyNanos);
            probeElapsedNanos = probe.pendingFrameElapsedNanos();
            if (isProbeFailure(preparation.decision())) {
                throw firstFrameProbeFailure(preparation.decision(), probe,
                        Math.max(0L, System.nanoTime() - probe.startedNanos()));
            }
            if (preparation.decision() == Av1FirstFrameProbe.Decision.CANCELLED) {
                return FrameOffer.notOffered();
            }
            if (preparation.decision() == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT) {
                return FrameOffer.discardedFrame();
            }
            if (preparation.hasTicket()) {
                ticket = preparation.ticket();
            } else {
                probeElapsedNanos = -1L;
            }
        }
        boolean offered = false;
        try {
            offered = frames.offer(new QueuedDecodedFrame(frame, probeElapsedNanos, ticket), timeout, unit);
            return new FrameOffer(offered, false, offered ? ticket : -1L);
        } finally {
            if (!offered && probe != null && ticket > 0L) {
                probe.cancelPreparedFrame(ticket);
            }
        }
    }

    private boolean awaitProbeFrameDecision(long ticket) throws IOException {
        if (ticket <= 0L) {
            return !closed.get();
        }
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe == null) {
            throw new IOException("AV1 首帧探测 ticket 已丢失: ticket=" + ticket);
        }
        Av1FirstFrameProbe.Decision decision;
        try {
            decision = probe.awaitFrameDecision(ticket, closed);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (closed.get()) {
                return false;
            }
            throw new IOException("等待 AV1 首帧候选确认时被中断", error);
        }
        if (decision == Av1FirstFrameProbe.Decision.COMMITTED
                || decision == Av1FirstFrameProbe.Decision.CONTINUE
                || decision == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT) {
            return !closed.get();
        }
        if (decision == Av1FirstFrameProbe.Decision.CANCELLED || closed.get()) {
            return false;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - probe.startedNanos());
        if (isProbeFailure(decision)) {
            throw firstFrameProbeFailure(decision, probe, elapsedNanos);
        }
        throw new IOException("AV1 首帧探测未能完成 ticket 决策: ticket=" + ticket + ", decision=" + decision);
    }

    private void finishProbePacketDrain(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
        Av1FirstFrameProbe probe = activeFirstFrameProbe;
        if (probe == null || packetPermit == null) {
            return;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - probe.startedNanos());
        Av1FirstFrameProbe.PacketTransition transition = probe.endPacket(
                packetPermit, Av1FirstFrameProbe.PacketEnd.DRAINED);
        if (!transition.applied()) {
            throw new IOException("AV1 首帧探测未能完成 packet drain: ordinal="
                    + packetPermit.ordinal() + ", decision=" + transition.decision());
        }
        Av1FirstFrameProbe.Decision decision = transition.decision();
        if (isProbeFailure(decision)) {
            throw firstFrameProbeFailure(decision, probe, elapsedNanos);
        }
        if (decision == Av1FirstFrameProbe.Decision.FRAME_PENDING) {
            throw new IOException("AV1 首帧探测在 packet drain 边界仍有未确认帧");
        }
    }

    private record QueuedDecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame frame,
            long probeElapsedNanos, long probeTicket) {
    }

    private record FrameOffer(boolean offered, boolean discarded, long probeTicket) {
        private static FrameOffer notOffered() {
            return new FrameOffer(false, false, -1L);
        }

        private static FrameOffer discardedFrame() {
            return new FrameOffer(false, true, -1L);
        }
    }
}
