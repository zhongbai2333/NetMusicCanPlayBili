package com.zhongbai233.client_resource_diagnostics;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class DiagnosticsController {
    public static final DiagnosticsController INSTANCE = new DiagnosticsController();
    private static final long MIB = 1024L * 1024L;
    private static final int SAMPLE_CAPACITY = 512;
    private static final int SPIKE_CAPACITY = 64;
    private static final long SAMPLE_NANOS = 100_000_000L;
    private static final long SPIKE_BYTES = 64L * MIB;

    private final BoundedOwnerTable owners = new BoundedOwnerTable();
    private final long[] sampleTimes = new long[SAMPLE_CAPACITY];
    private final long[] sampleCommit = new long[SAMPLE_CAPACITY];
    private final long[] spikeTimes = new long[SPIKE_CAPACITY];
    private final long[] spikeDeltas = new long[SPIKE_CAPACITY];
    private final long[] spikeBufferBytes = new long[SPIKE_CAPACITY];
    private final long[] spikeTextureBytes = new long[SPIKE_CAPACITY];
    private final long[] spikeDirtySections = new long[SPIKE_CAPACITY];
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile int sampleCursor;
    private volatile int spikeCursor;
    private volatile long currentCommit = -1L;
    private volatile long peakCommit = -1L;
    private volatile boolean commitSupported;
    private volatile long bufferProbeHits;
    private volatile long textureProbeHits;
    private volatile long dirtyProbeHits;

    private DiagnosticsController() {
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread sampler = Thread.ofPlatform().daemon().name("ClientDiag-CommitSampler").unstarted(this::sampleLoop);
        sampler.start();
    }

    public ResourceToken registerGpu(long bytes, boolean texture) {
        String owner = OwnerResolver.capture();
        long safeBytes = Math.max(0L, bytes);
        owners.addResource(owner, safeBytes, texture);
        if (texture) {
            textureProbeHits++;
        } else {
            bufferProbeHits++;
        }
        return new ResourceToken(owner, safeBytes, texture);
    }

    public void releaseGpu(ResourceToken token) {
        if (token != null) {
            owners.removeResource(token.owner(), token.bytes(), token.texture());
        }
    }

    public void recordDirtyRange(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        long sections = sectionSpan(minX, maxX) * sectionSpan(minY, maxY) * sectionSpan(minZ, maxZ);
        owners.addDirty(OwnerResolver.capture(), Math.max(1L, sections));
        dirtyProbeHits++;
    }

    public Snapshot snapshot() {
        List<Spike> spikes = new ArrayList<>();
        int cursor = spikeCursor;
        int count = Math.min(cursor, SPIKE_CAPACITY);
        for (int i = 0; i < count; i++) {
            int index = Math.floorMod(cursor - count + i, SPIKE_CAPACITY);
            spikes.add(new Spike(spikeTimes[index], spikeDeltas[index], spikeBufferBytes[index],
                    spikeTextureBytes[index], spikeDirtySections[index]));
        }
        return new Snapshot(commitSupported, currentCommit, peakCommit, bufferProbeHits,
                textureProbeHits, dirtyProbeHits, owners.snapshot(), spikes);
    }

    public void resetActivity() {
        owners.resetActivity();
        peakCommit = currentCommit;
        spikeCursor = 0;
    }

    private void sampleLoop() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof OperatingSystemMXBean os)) {
            return;
        }
        commitSupported = true;
        long previous = -1L;
        while (started.get()) {
            long now = System.nanoTime();
            long value = os.getCommittedVirtualMemorySize();
            currentCommit = value;
            peakCommit = Math.max(peakCommit, value);
            int index = Math.floorMod(sampleCursor++, SAMPLE_CAPACITY);
            sampleTimes[index] = now;
            sampleCommit[index] = value;
            if (previous >= 0L && value - previous >= SPIKE_BYTES) {
                int spike = Math.floorMod(spikeCursor++, SPIKE_CAPACITY);
                BoundedOwnerTable.Totals totals = owners.totals();
                spikeTimes[spike] = now;
                spikeDeltas[spike] = value - previous;
                spikeBufferBytes[spike] = totals.bufferBytes();
                spikeTextureBytes[spike] = totals.textureBytes();
                spikeDirtySections[spike] = totals.dirtySections();
            }
            previous = value;
            LockSupport.parkNanos(SAMPLE_NANOS);
        }
    }

    private static long sectionSpan(int min, int max) {
        long low = Math.floorDiv(Math.min(min, max), 16);
        long high = Math.floorDiv(Math.max(min, max), 16);
        return Math.min(1_000_000L, high - low + 1L);
    }

    public record ResourceToken(String owner, long bytes, boolean texture) {
    }

        public record Spike(long timestampNanos, long deltaBytes, long bufferBytes,
            long textureBytes, long dirtySections) {
    }

        public record Snapshot(boolean commitSupported, long currentCommit, long peakCommit,
            long bufferProbeHits, long textureProbeHits, long dirtyProbeHits,
            List<BoundedOwnerTable.OwnerSnapshot> owners, List<Spike> spikes) {
    }
}