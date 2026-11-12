package com.zhongbai233.net_music_can_play_bili.media.stream;
import org.junit.jupiter.api.Test;import java.nio.*;import java.util.*;import java.io.*;import com.zhongbai233.net_music_can_play_bili.media.audio.*;import static org.junit.jupiter.api.Assertions.*;
class TerminalReadBoundaryTest {
    @Test void realNativeTailMustDrainBeforeCarrierEof() throws Exception {
        byte[] b=new byte[16];Arrays.fill(b,(byte)9);assertEquals(8,TerminalSilence.read(b,4,8,false,null,true,AudioDrainStatus.DRAINING));assertEquals(9,b[0]);assertEquals(0,b[4]);assertEquals(-1,TerminalSilence.read(b,0,16,false,null,true,AudioDrainStatus.DRAINED));
    }
    @Test void failureAndCancellationAreDifferentFromNaturalEndAndZeroReadsStayZero() throws Exception {
        byte[] b=new byte[4];assertThrows(IOException.class,()->TerminalSilence.read(b,0,4,false,new EOFException("truncated"),true,AudioDrainStatus.DRAINED));
        assertThrows(PlaybackStreamCancelledException.class,()->TerminalSilence.read(b,0,4,true,null,true,AudioDrainStatus.DRAINED));
        assertEquals(0,TerminalSilence.read(b,0,0,true,new IOException(),true,AudioDrainStatus.FAILED));assertThrows(IndexOutOfBoundsException.class,()->TerminalSilence.read(b,4,1,false,null,false,AudioDrainStatus.RUNNING));
    }
    @Test void upstreamUnderrunIsSilenceAndPartialLastBufferIsKept() {
        Queue<ByteBuffer>q=new java.util.concurrent.ConcurrentLinkedQueue<>();assertEquals(8,BufferedAudioReadBoundary.read(q,8,2,false).remaining());
        q.add(ByteBuffer.wrap(new byte[]{1,2,3,4}));ByteBuffer tail=BufferedAudioReadBoundary.read(q,8,2,true);assertEquals(4,tail.remaining());assertEquals(1,tail.get());assertNull(BufferedAudioReadBoundary.read(q,8,2,true));
    }
    @Test void producerFinishingCannotEraseAlreadyBufferedFrames() {
        Queue<ByteBuffer>q=new ArrayDeque<>();q.add(ByteBuffer.wrap(new byte[]{1,2,3,4}));q.add(ByteBuffer.wrap(new byte[]{5,6}));assertEquals(6,BufferedAudioReadBoundary.read(q,8,2,true).remaining());assertNull(BufferedAudioReadBoundary.read(q,8,2,true));
    }
    @Test void eofNeverSchedulesAnotherUpstreamPumpAndWaitsForExistingPumpToPublishTail() {
        Queue<ByteBuffer> q=new ArrayDeque<>();var loading=new java.util.concurrent.atomic.AtomicBoolean(true);int[]loads={0};
        assertNotNull(BufferedAudioReadBoundary.readUpstream(q,8,2,()->true,loading::get,()->{loads[0]++;loading.set(true);}));
        q.add(ByteBuffer.wrap(new byte[]{1,2}));loading.set(false);
        assertEquals(2,BufferedAudioReadBoundary.readUpstream(q,8,2,()->true,loading::get,()->{loads[0]++;loading.set(true);}).remaining());
        assertNull(BufferedAudioReadBoundary.readUpstream(q,8,2,()->true,loading::get,()->{loads[0]++;loading.set(true);}));assertEquals(0,loads[0]);
    }
}
