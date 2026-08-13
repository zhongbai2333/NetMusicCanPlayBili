package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Atomic transaction for one AV1 hardware candidate's first accepted frame.
 * Packet admission, native send, packet drain, and playback-queue acceptance
 * remain one transaction until the exact packet reaches EAGAIN.
 */
final class Av1FirstFrameProbe {
    enum Decision {
        CONTINUE,
        DRAIN_IN_FLIGHT,
        FRAME_PENDING,
        COMMITTED,
        CANCELLED,
        TIME_EXHAUSTED,
        PACKET_EXHAUSTED
    }

    enum PacketEnd {
        DRAINED,
        SEND_REJECTED,
        ABORTED
    }

    record PacketPermit(long token, int ordinal) {
        PacketPermit {
            if (token <= 0L || ordinal <= 0) {
                throw new IllegalArgumentException("invalid packet permit");
            }
        }
    }

    record PacketAdmission(PacketPermit permit, Decision decision) {
        boolean admitted() {
            return permit != null && decision == Decision.CONTINUE;
        }

        boolean bypassedAfterCommit() {
            return permit == null && decision == Decision.COMMITTED;
        }
    }

    record PacketTransition(boolean applied, Decision decision) {
    }

    record FramePreparation(long ticket, Decision decision) {
        boolean hasTicket() {
            return ticket > 0L && decision == Decision.FRAME_PENDING;
        }
    }

    private enum Outcome {
        ACTIVE,
        COMMITTED,
        CANCELLED,
        EXHAUSTED
    }

    private enum PacketPhase {
        NONE,
        RESERVED,
        DRAINING
    }

    private final long startedNanos;
    private final long timeoutMillis;
    private final long timeoutNanos;
    private final int maxPackets;
    private final LongSupplier nanoClock;

    private Outcome outcome = Outcome.ACTIVE;
    private Decision exhaustedDecision;
    private PacketPhase packetPhase = PacketPhase.NONE;
    private long nextPacketToken;
    private long activePacketToken;
    private int activePacketOrdinal;
    private int successfulPackets;
    private boolean deadlineLatched;
    private long nextFrameTicket;
    private long pendingFrameTicket;
    private long pendingFramePacketToken;
    private long pendingFrameElapsedNanos = -1L;

    Av1FirstFrameProbe(long startedNanos, long timeoutMillis, int maxPackets) {
        this(startedNanos, timeoutMillis, maxPackets, System::nanoTime);
    }

    Av1FirstFrameProbe(long startedNanos, long timeoutMillis, int maxPackets, LongSupplier nanoClock) {
        this.startedNanos = startedNanos;
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = timeoutMillis > 0L
                ? TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                : 0L;
        this.maxPackets = maxPackets;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    long startedNanos() {
        return startedNanos;
    }

    long timeoutMillis() {
        return timeoutMillis;
    }

    int maxPackets() {
        return maxPackets;
    }

    synchronized int successfulPackets() {
        return successfulPackets;
    }

    /** Atomically samples the clock, checks both budgets, and reserves one send. */
    synchronized PacketAdmission beginPacketNow() {
        Decision terminal = terminalDecision();
        if (terminal != null) {
            return new PacketAdmission(null, terminal);
        }
        if (packetPhase != PacketPhase.NONE || pendingFrameTicket > 0L) {
            return new PacketAdmission(null, currentActiveDecision());
        }
        long elapsedNanos = elapsedNowLocked();
        if (timeExpired(elapsedNanos)) {
            exhaust(Decision.TIME_EXHAUSTED);
            return new PacketAdmission(null, Decision.TIME_EXHAUSTED);
        }
        if (packetExhausted()) {
            exhaust(Decision.PACKET_EXHAUSTED);
            return new PacketAdmission(null, Decision.PACKET_EXHAUSTED);
        }
        PacketPermit permit = new PacketPermit(++nextPacketToken, successfulPackets + 1);
        activePacketToken = permit.token();
        activePacketOrdinal = permit.ordinal();
        packetPhase = PacketPhase.RESERVED;
        deadlineLatched = false;
        return new PacketAdmission(permit, Decision.CONTINUE);
    }

    /**
     * Reserves the decoder EOF drain without consuming another media-packet
     * budget. This lets a delayed first frame keep the exact ticket/commit
     * transaction after the final packet has already reached EAGAIN.
     */
    synchronized PacketAdmission beginEndOfStreamDrainNow() {
        Decision terminal = terminalDecision();
        if (terminal != null) {
            return new PacketAdmission(null, terminal);
        }
        if (packetPhase != PacketPhase.NONE || pendingFrameTicket > 0L) {
            return new PacketAdmission(null, currentActiveDecision());
        }
        long elapsedNanos = elapsedNowLocked();
        if (timeExpired(elapsedNanos)) {
            exhaust(Decision.TIME_EXHAUSTED);
            return new PacketAdmission(null, Decision.TIME_EXHAUSTED);
        }
        PacketPermit permit = new PacketPermit(++nextPacketToken, successfulPackets + 1);
        activePacketToken = permit.token();
        activePacketOrdinal = permit.ordinal();
        packetPhase = PacketPhase.DRAINING;
        deadlineLatched = false;
        return new PacketAdmission(permit, Decision.CONTINUE);
    }

    /** Records a successful native send without releasing its drain lease. */
    synchronized PacketTransition markPacketSent(PacketPermit permit) {
        if (!matchesActivePermit(permit) || packetPhase != PacketPhase.RESERVED) {
            return new PacketTransition(false, decisionForState());
        }
        successfulPackets++;
        packetPhase = PacketPhase.DRAINING;
        notifyAll();
        return new PacketTransition(true, decisionForState());
    }

    /**
     * Releases the exact packet lease. Only DRAINED may publish packet
     * exhaustion, so packet #max always reaches EAGAIN first.
     */
    synchronized PacketTransition endPacket(PacketPermit permit, PacketEnd end) {
        Objects.requireNonNull(end, "end");
        if (!matchesActivePermit(permit)) {
            return new PacketTransition(false, decisionForState());
        }
        if (end == PacketEnd.DRAINED && packetPhase != PacketPhase.DRAINING) {
            return new PacketTransition(false, decisionForState());
        }
        if (end == PacketEnd.SEND_REJECTED && packetPhase != PacketPhase.RESERVED) {
            return new PacketTransition(false, decisionForState());
        }
        if (end == PacketEnd.DRAINED && pendingFrameTicket > 0L) {
            return new PacketTransition(false, Decision.FRAME_PENDING);
        }

        clearPacketLease();
        if (end == PacketEnd.ABORTED && outcome == Outcome.ACTIVE) {
            outcome = Outcome.CANCELLED;
            clearPendingFrame();
        } else if (outcome == Outcome.ACTIVE) {
            long elapsedNanos = elapsedNowLocked();
            if (deadlineLatched || timeExpired(elapsedNanos)) {
                exhaust(Decision.TIME_EXHAUSTED);
            } else if (end == PacketEnd.DRAINED && packetExhausted()) {
                exhaust(Decision.PACKET_EXHAUSTED);
            }
        }
        deadlineLatched = false;
        notifyAll();
        return new PacketTransition(true, decisionForState());
    }

    /**
     * Prepares a provisional frame produced by the exact draining packet.
     * readyNanos is captured when native receive reports the frame, not when it
     * later enters the Java queue.
     */
    synchronized FramePreparation prepareFrame(PacketPermit permit, long readyNanos) {
        Decision terminal = terminalDecision();
        if (terminal != null) {
            return new FramePreparation(-1L, terminal);
        }
        if (!matchesActivePermit(permit) || packetPhase != PacketPhase.DRAINING) {
            return new FramePreparation(-1L, currentActiveDecision());
        }
        if (pendingFrameTicket > 0L) {
            return new FramePreparation(-1L, Decision.FRAME_PENDING);
        }

        long readyElapsedNanos = elapsedAt(readyNanos);
        if (timeExpired(readyElapsedNanos)) {
            deadlineLatched = true;
            return new FramePreparation(-1L, Decision.DRAIN_IN_FLIGHT);
        }
        pendingFrameTicket = ++nextFrameTicket;
        pendingFramePacketToken = permit.token();
        pendingFrameElapsedNanos = readyElapsedNanos;
        notifyAll();
        return new FramePreparation(pendingFrameTicket, Decision.FRAME_PENDING);
    }

    synchronized boolean commit(long ticket) {
        if (outcome != Outcome.ACTIVE || ticket <= 0L || ticket != pendingFrameTicket
                || pendingFramePacketToken != activePacketToken
                || packetPhase != PacketPhase.DRAINING) {
            return false;
        }
        outcome = Outcome.COMMITTED;
        clearPendingFrame();
        notifyAll();
        return true;
    }

    synchronized boolean reject(long ticket) {
        if (outcome != Outcome.ACTIVE || ticket <= 0L || ticket != pendingFrameTicket
                || pendingFramePacketToken != activePacketToken
                || packetPhase != PacketPhase.DRAINING) {
            return false;
        }
        clearPendingFrame();
        notifyAll();
        return true;
    }

    synchronized void cancelPreparedFrame(long ticket) {
        reject(ticket);
    }

    synchronized Decision awaitFrameDecision(long ticket, AtomicBoolean closed) throws InterruptedException {
        while (outcome == Outcome.ACTIVE && pendingFrameTicket == ticket && ticket > 0L
                && !closed.get()) {
            wait(50L);
        }
        return decisionForState();
    }

    /** The consumer enforces wall time only; packet exhaustion is worker-owned. */
    synchronized Decision evaluateConsumerTimeNow() {
        Decision terminal = terminalDecision();
        if (terminal != null) {
            return terminal;
        }
        long elapsedNanos = elapsedNowLocked();
        if (!timeExpired(elapsedNanos)) {
            return currentActiveDecision();
        }
        if (packetPhase != PacketPhase.NONE) {
            deadlineLatched = true;
            return pendingFrameTicket > 0L ? Decision.FRAME_PENDING : Decision.DRAIN_IN_FLIGHT;
        }
        exhaust(Decision.TIME_EXHAUSTED);
        return Decision.TIME_EXHAUSTED;
    }

    synchronized Decision decision() {
        return decisionForState();
    }

    synchronized long pendingFrameElapsedNanos() {
        return pendingFrameElapsedNanos;
    }

    synchronized void cancel() {
        if (outcome == Outcome.COMMITTED || outcome == Outcome.EXHAUSTED
                || outcome == Outcome.CANCELLED) {
            return;
        }
        outcome = Outcome.CANCELLED;
        clearPendingFrame();
        notifyAll();
    }

    private Decision currentActiveDecision() {
        if (pendingFrameTicket > 0L) {
            return Decision.FRAME_PENDING;
        }
        if (packetPhase != PacketPhase.NONE) {
            return Decision.DRAIN_IN_FLIGHT;
        }
        return Decision.CONTINUE;
    }

    private Decision terminalDecision() {
        return switch (outcome) {
            case ACTIVE -> null;
            case COMMITTED -> Decision.COMMITTED;
            case CANCELLED -> Decision.CANCELLED;
            case EXHAUSTED -> exhaustedDecision;
        };
    }

    private Decision decisionForState() {
        Decision terminal = terminalDecision();
        return terminal != null ? terminal : currentActiveDecision();
    }

    private boolean matchesActivePermit(PacketPermit permit) {
        return permit != null && packetPhase != PacketPhase.NONE
                && permit.token() == activePacketToken
                && permit.ordinal() == activePacketOrdinal;
    }

    private boolean packetExhausted() {
        return maxPackets > 0 && successfulPackets >= maxPackets;
    }

    private boolean timeExpired(long elapsedNanos) {
        return timeoutNanos > 0L && elapsedNanos >= timeoutNanos;
    }

    private long elapsedNowLocked() {
        return elapsedAt(nanoClock.getAsLong());
    }

    private long elapsedAt(long nowNanos) {
        return Math.max(0L, nowNanos - startedNanos);
    }

    private void exhaust(Decision decision) {
        outcome = Outcome.EXHAUSTED;
        exhaustedDecision = decision;
        clearPendingFrame();
        notifyAll();
    }

    private void clearPacketLease() {
        packetPhase = PacketPhase.NONE;
        activePacketToken = 0L;
        activePacketOrdinal = 0;
    }

    private void clearPendingFrame() {
        pendingFrameTicket = 0L;
        pendingFramePacketToken = 0L;
        pendingFrameElapsedNanos = -1L;
    }
}
