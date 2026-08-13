package com.zhongbai233.net_music_can_play_bili.media.codec;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Av1FirstFrameProbeTest {
    @Test
    void deadlineAfterPermitDefersFailureUntilPacketDrain() {
        ManualClock clock = new ManualClock(millis(1_999L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketAdmission admission = probe.beginPacketNow();

        assertTrue(admission.admitted());
        clock.setMillis(2_000L);
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT,
                probe.evaluateConsumerTimeNow());
        assertFalse(probe.beginPacketNow().admitted());

        assertTrue(probe.markPacketSent(admission.permit()).applied());
        assertEquals(1, probe.successfulPackets());
        Av1FirstFrameProbe.FramePreparation late = probe.prepareFrame(
                admission.permit(), clock.getAsLong());
        assertFalse(late.hasTicket());
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT, late.decision());
        assertEquals(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED,
                probe.endPacket(admission.permit(), Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
    }

    @Test
    void preDeadlineFrameCanCommitAfterConsumerObservedDeadline() {
        ManualClock clock = new ManualClock(millis(1_999L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
        assertTrue(probe.markPacketSent(permit).applied());
        long readyNanos = clock.getAsLong();

        clock.setMillis(2_000L);
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT,
                probe.evaluateConsumerTimeNow());
        Av1FirstFrameProbe.FramePreparation frame = probe.prepareFrame(permit, readyNanos);
        assertTrue(frame.hasTicket());
        assertEquals(Av1FirstFrameProbe.Decision.FRAME_PENDING,
                probe.evaluateConsumerTimeNow());

        clock.setMillis(2_001L);
        assertTrue(probe.commit(frame.ticket()));
        assertEquals(Av1FirstFrameProbe.Decision.COMMITTED,
                probe.endPacket(permit, Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
    }

    @Test
    void rejectedPreDeadlineFrameThenPostDeadlineFrameFailsAtDrain() {
        ManualClock clock = new ManualClock(millis(100L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
        probe.markPacketSent(permit);

        Av1FirstFrameProbe.FramePreparation first = probe.prepareFrame(permit, millis(1_999L));
        assertTrue(probe.reject(first.ticket()));
        clock.setMillis(2_000L);
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT,
                probe.evaluateConsumerTimeNow());
        Av1FirstFrameProbe.FramePreparation second = probe.prepareFrame(permit, millis(2_000L));
        assertFalse(second.hasTicket());

        assertEquals(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED,
                probe.endPacket(permit, Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
    }

    @Test
    void lastPacketDrainsAllFramesAndSecondFrameMayCommit() {
        ManualClock clock = new ManualClock(millis(100L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        drainPackets(probe, 255);

        Av1FirstFrameProbe.PacketPermit last = admitted(probe);
        assertEquals(256, last.ordinal());
        probe.markPacketSent(last);
        Av1FirstFrameProbe.FramePreparation first = probe.prepareFrame(last, clock.getAsLong());
        assertTrue(probe.reject(first.ticket()));
        Av1FirstFrameProbe.FramePreparation second = probe.prepareFrame(last, clock.getAsLong());
        assertNotEquals(first.ticket(), second.ticket());
        assertTrue(probe.commit(second.ticket()));

        assertEquals(Av1FirstFrameProbe.Decision.COMMITTED,
                probe.endPacket(last, Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
        assertEquals(256, probe.successfulPackets());
        Av1FirstFrameProbe.PacketAdmission afterCommit = probe.beginPacketNow();
        assertFalse(afterCommit.admitted());
        assertTrue(afterCommit.bypassedAfterCommit());
        assertEquals(Av1FirstFrameProbe.Decision.COMMITTED, afterCommit.decision());
    }

    @Test
    void lastPacketWithoutAcceptedFrameExhaustsOnlyAtDrainBoundary() {
        ManualClock clock = new ManualClock(millis(100L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        drainPackets(probe, 255);

        Av1FirstFrameProbe.PacketPermit last = admitted(probe);
        probe.markPacketSent(last);
        Av1FirstFrameProbe.FramePreparation frame = probe.prepareFrame(last, clock.getAsLong());
        assertTrue(probe.reject(frame.ticket()));
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT, probe.decision());

        assertEquals(Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED,
                probe.endPacket(last, Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
        assertFalse(probe.beginPacketNow().admitted());
    }

    @Test
    void endOfStreamDrainCanCommitDelayedFirstFrameWithoutConsumingPacket() {
        ManualClock clock = new ManualClock(millis(100L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit media = admitted(probe);
        probe.markPacketSent(media);
        assertEquals(Av1FirstFrameProbe.Decision.CONTINUE,
                probe.endPacket(media, Av1FirstFrameProbe.PacketEnd.DRAINED).decision());

        Av1FirstFrameProbe.PacketAdmission eof = probe.beginEndOfStreamDrainNow();
        assertTrue(eof.admitted());
        assertEquals(1, probe.successfulPackets());
        Av1FirstFrameProbe.FramePreparation frame = probe.prepareFrame(eof.permit(), clock.getAsLong());
        assertTrue(frame.hasTicket());
        assertTrue(probe.commit(frame.ticket()));

        assertEquals(Av1FirstFrameProbe.Decision.COMMITTED,
                probe.endPacket(eof.permit(), Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
        assertEquals(1, probe.successfulPackets());
    }

    @Test
    void endOfStreamDrainStillObeysWallClockBudget() {
        ManualClock clock = new ManualClock(millis(2_000L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);

        Av1FirstFrameProbe.PacketAdmission eof = probe.beginEndOfStreamDrainNow();

        assertFalse(eof.admitted());
        assertEquals(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED, eof.decision());
        assertEquals(0, probe.successfulPackets());
    }

    @Test
    void explicitSendRejectionRollsBackCountAndRejectsStalePermit() {
        ManualClock clock = new ManualClock(0L);
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit first = admitted(probe);

        Av1FirstFrameProbe.PacketTransition rejected = probe.endPacket(
                first, Av1FirstFrameProbe.PacketEnd.SEND_REJECTED);
        assertTrue(rejected.applied());
        assertEquals(0, probe.successfulPackets());

        Av1FirstFrameProbe.PacketPermit second = admitted(probe);
        assertEquals(1, second.ordinal());
        assertNotEquals(first.token(), second.token());
        assertFalse(probe.markPacketSent(first).applied());
        assertEquals(0, probe.successfulPackets());
        assertTrue(probe.markPacketSent(second).applied());
        assertEquals(1, probe.successfulPackets());
    }

    @Test
    void deadlineDuringRejectedSendPublishesTimeAndUnblocksWaiter() {
        ManualClock clock = new ManualClock(millis(1_999L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);

        clock.setMillis(2_000L);
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT,
                probe.evaluateConsumerTimeNow());
        Av1FirstFrameProbe.PacketTransition result = probe.endPacket(
                permit, Av1FirstFrameProbe.PacketEnd.SEND_REJECTED);

        assertTrue(result.applied());
        assertEquals(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED, result.decision());
        assertEquals(0, probe.successfulPackets());
    }

    @Test
    void cancellationDuringSendCannotBeResurrectedByLateToken() {
        ManualClock clock = new ManualClock(0L);
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);

        probe.cancel();
        assertEquals(Av1FirstFrameProbe.Decision.CANCELLED, probe.decision());
        Av1FirstFrameProbe.PacketTransition sent = probe.markPacketSent(permit);
        assertTrue(sent.applied());
        assertEquals(Av1FirstFrameProbe.Decision.CANCELLED, sent.decision());
        Av1FirstFrameProbe.PacketTransition ended = probe.endPacket(
                permit, Av1FirstFrameProbe.PacketEnd.ABORTED);
        assertTrue(ended.applied());
        assertEquals(Av1FirstFrameProbe.Decision.CANCELLED, ended.decision());

        assertFalse(probe.markPacketSent(permit).applied());
        assertFalse(probe.beginPacketNow().admitted());
        assertEquals(Av1FirstFrameProbe.Decision.CANCELLED, probe.decision());
    }

    @Test
    void ambiguousSendAbortIsTerminalAndDoesNotConsumePacketBudget() {
        ManualClock clock = new ManualClock(0L);
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);

        Av1FirstFrameProbe.PacketTransition result = probe.endPacket(
                permit, Av1FirstFrameProbe.PacketEnd.ABORTED);

        assertTrue(result.applied());
        assertEquals(Av1FirstFrameProbe.Decision.CANCELLED, result.decision());
        assertEquals(0, probe.successfulPackets());
        assertFalse(probe.beginPacketNow().admitted());
    }

    @Test
    void staleFrameTicketCannotAcknowledgeNewFrameFromSamePacket() {
        ManualClock clock = new ManualClock(0L);
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
        probe.markPacketSent(permit);
        Av1FirstFrameProbe.FramePreparation first = probe.prepareFrame(permit, 0L);
        assertTrue(probe.reject(first.ticket()));
        Av1FirstFrameProbe.FramePreparation second = probe.prepareFrame(permit, 1L);

        assertFalse(probe.commit(first.ticket()));
        assertTrue(probe.commit(second.ticket()));
    }

    @Test
    void frameAtDeadlineIsNotTicketed() {
        ManualClock clock = new ManualClock(0L);
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
        probe.markPacketSent(permit);

        Av1FirstFrameProbe.FramePreparation frame = probe.prepareFrame(permit, millis(2_000L));

        assertFalse(frame.hasTicket());
        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT, frame.decision());
    }

    @Test
    void packetAdmissionSamplesClockInsideAtomicTransition() {
        ManualClock clock = new ManualClock(millis(1_999L));
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        clock.setMillis(2_000L);

        Av1FirstFrameProbe.PacketAdmission admission = probe.beginPacketNow();

        assertFalse(admission.admitted());
        assertNull(admission.permit());
        assertEquals(Av1FirstFrameProbe.Decision.TIME_EXHAUSTED, admission.decision());
    }

    @Test
    void concurrentDeadlineAndLegalPacketDrainHaveDeterministicWinner() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            ManualClock clock = new ManualClock(millis(1_999L));
            Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch deadlineObserved = new CountDownLatch(1);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            AtomicReference<Throwable> consumerFailure = new AtomicReference<>();

            Thread worker = new Thread(() -> {
                try {
                    Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
                    long readyNanos = clock.getAsLong();
                    admitted.countDown();
                    assertTrue(deadlineObserved.await(2L, TimeUnit.SECONDS));
                    assertTrue(probe.markPacketSent(permit).applied());
                    Av1FirstFrameProbe.FramePreparation frame = probe.prepareFrame(permit, readyNanos);
                    assertTrue(frame.hasTicket());
                    assertTrue(probe.commit(frame.ticket()));
                    assertEquals(Av1FirstFrameProbe.Decision.COMMITTED,
                            probe.endPacket(permit, Av1FirstFrameProbe.PacketEnd.DRAINED).decision());
                } catch (Throwable error) {
                    workerFailure.set(error);
                }
            }, "probe-worker-test");
            Thread consumer = new Thread(() -> {
                try {
                    assertTrue(admitted.await(2L, TimeUnit.SECONDS));
                    clock.setMillis(2_000L);
                    assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT,
                            probe.evaluateConsumerTimeNow());
                } catch (Throwable error) {
                    consumerFailure.set(error);
                } finally {
                    deadlineObserved.countDown();
                }
            }, "probe-consumer-test");

            worker.start();
            consumer.start();
            worker.join(2_000L);
            consumer.join(2_000L);

            assertFalse(worker.isAlive());
            assertFalse(consumer.isAlive());
            assertNull(workerFailure.get());
            assertNull(consumerFailure.get());
            assertEquals(Av1FirstFrameProbe.Decision.COMMITTED, probe.decision());
        });
    }

    @Test
    void exactAwaitReturnsAfterRejectWithoutConfusingNewTicket() throws Exception {
        ManualClock clock = new ManualClock(0L);
        Av1FirstFrameProbe probe = probe(clock, 2_000L, 256);
        Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
        probe.markPacketSent(permit);
        Av1FirstFrameProbe.FramePreparation frame = probe.prepareFrame(permit, 0L);
        assertTrue(probe.reject(frame.ticket()));

        assertEquals(Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT,
                probe.awaitFrameDecision(frame.ticket(), new AtomicBoolean(false)));
    }

    private static Av1FirstFrameProbe probe(ManualClock clock, long timeoutMillis, int maxPackets) {
        return new Av1FirstFrameProbe(0L, timeoutMillis, maxPackets, clock);
    }

    private static Av1FirstFrameProbe.PacketPermit admitted(Av1FirstFrameProbe probe) {
        Av1FirstFrameProbe.PacketAdmission admission = probe.beginPacketNow();
        assertTrue(admission.admitted(), () -> "packet admission failed: " + admission.decision());
        return admission.permit();
    }

    private static void drainPackets(Av1FirstFrameProbe probe, int count) {
        for (int i = 0; i < count; i++) {
            Av1FirstFrameProbe.PacketPermit permit = admitted(probe);
            assertTrue(probe.markPacketSent(permit).applied());
            Av1FirstFrameProbe.PacketTransition drained = probe.endPacket(
                    permit, Av1FirstFrameProbe.PacketEnd.DRAINED);
            assertTrue(drained.applied());
            assertEquals(Av1FirstFrameProbe.Decision.CONTINUE, drained.decision());
        }
    }

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }

    private static final class ManualClock implements LongSupplier {
        private final AtomicLong nowNanos;

        private ManualClock(long initialNanos) {
            nowNanos = new AtomicLong(initialNanos);
        }

        @Override
        public long getAsLong() {
            return nowNanos.get();
        }

        private void setMillis(long millis) {
            nowNanos.set(Av1FirstFrameProbeTest.millis(millis));
        }
    }
}
