package com.zhongbai233.net_music_can_play_bili.media.audio;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class AudioDrainPolicyTest {
    @Test void shortNaturalTailStartsBelowNormalPrebufferButEmptyNeverStarts() {assertTrue(AudioDrainPolicy.mayStart(1,96,true));assertFalse(AudioDrainPolicy.mayStart(1,96,false));assertFalse(AudioDrainPolicy.mayStart(0,96,true));}
    @Test void pausedDecodeInFlightJavaPendingAndNativeQueuedCannotClaimDrained() {
        assertEquals(AudioDrainStatus.DRAINED,AudioDrainPolicy.status(false,false,true,false,true,0,0,0,true));
        assertEquals(AudioDrainStatus.DRAINING,AudioDrainPolicy.status(false,false,true,true,true,0,0,0,true));
        assertEquals(AudioDrainStatus.DRAINING,AudioDrainPolicy.status(false,false,true,false,true,1,0,0,true));
        assertEquals(AudioDrainStatus.DRAINING,AudioDrainPolicy.status(false,false,true,false,true,0,1,0,true));
        assertEquals(AudioDrainStatus.DRAINING,AudioDrainPolicy.status(false,false,true,false,true,0,0,1,true));
        assertEquals(AudioDrainStatus.DRAINING,AudioDrainPolicy.status(false,false,true,false,true,0,0,0,false));
    }
    @Test void cancelFailureAndStillProducingAreNotNaturalCompletion() {assertEquals(AudioDrainStatus.CANCELLED,AudioDrainPolicy.status(true,false,true,false,true,0,0,0,true));assertEquals(AudioDrainStatus.FAILED,AudioDrainPolicy.status(false,true,true,false,true,0,0,0,true));assertEquals(AudioDrainStatus.RUNNING,AudioDrainPolicy.status(false,false,false,false,true,0,0,0,true));}
}
