package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackEndReason;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Playback terminal admission, unknown-duration budget and bounded failure recovery. */
class MP4PlaybackTerminalRecoveryTest {
    @Test void onlyExactOwnedUnknownNaturalEndAdvances() {
        assertEquals(MP4PlaybackEndPolicy.Decision.ADVANCE,MP4PlaybackEndPolicy.decide(true,true,true,true,false,0,PlaybackEndReason.NATURAL_EOF));
        for(int bad=0;bad<5;bad++)assertEquals(MP4PlaybackEndPolicy.Decision.IGNORE,MP4PlaybackEndPolicy.decide(bad!=0,bad!=1,bad!=2,bad!=3,bad==4,0,PlaybackEndReason.NATURAL_EOF));
        assertEquals(MP4PlaybackEndPolicy.Decision.IGNORE,MP4PlaybackEndPolicy.decide(true,true,true,true,false,5,PlaybackEndReason.NATURAL_EOF));
    }
    @Test void failureStopsForKnownAndUnknownButPauseCancelNeverAdvance() {
        for(int d:new int[]{0,200})assertEquals(MP4PlaybackEndPolicy.Decision.STOP,MP4PlaybackEndPolicy.decide(true,true,true,true,false,d,PlaybackEndReason.FAILED));
        for(var reason:new PlaybackEndReason[]{PlaybackEndReason.PAUSED,PlaybackEndReason.CANCELLED})assertEquals(MP4PlaybackEndPolicy.Decision.IGNORE,MP4PlaybackEndPolicy.decide(true,true,true,true,false,0,reason));
    }

    @Test void retainedLogicalRetryDoesNotRenewBudgetAndNewSelectionDoes() {
        var b=new UnknownPlaybackBudget(3);var id=UUID.randomUUID();assertFalse(b.tick(id,"logical"));assertFalse(b.tick(id,"logical"));assertTrue(b.tick(id,"logical"));assertFalse(b.tick(id,"new"));
        b.remove(id);assertFalse(b.tick(id,"new"));b.clear();assertFalse(b.tick(id,"new"));
    }
    @Test void inactiveSourcesAreForgottenWithoutWallClockTicks() {
        var b=new UnknownPlaybackBudget(2);var id=UUID.randomUUID();assertFalse(b.tick(id,"a"));b.retain(Set.of());assertFalse(b.tick(id,"a"));assertTrue(b.tick(id,"a"));
    }

    final UUID id = UUID.randomUUID();
    @Test void manualFailureStopsAndVisibleNoticeDoesNotStartAnotherRow() {
        MP4FailureRecovery r = new MP4FailureRecovery(); List<String> notices = new ArrayList<>();
        int[] stopped = {0}; List<Integer> starts = new ArrayList<>();
        r.recover(id, List.of("a","b"), 0, 2, true, 9, starts::add, () -> stopped[0]++, notices::add);
        assertEquals(1, stopped[0]); assertTrue(starts.isEmpty()); assertTrue(notices.getFirst().contains("所选"));
    }
    @Test void consecutiveFailuresAdvanceThenAllInvalidStopWithFiniteNotices() {
        MP4FailureRecovery r = new MP4FailureRecovery(); List<String> q=List.of("a","b","c"); r.beginAutomatic(id,q);
        List<Integer> starts=new ArrayList<>(); List<String> notices=new ArrayList<>(); int[] stop={0};
        for(int i=0;i<3;i++) r.recover(id,q,i,2,false,0,starts::add,()->stop[0]++,notices::add);
        assertEquals(List.of(1,2),starts); assertEquals(1,stop[0]); assertEquals(3,notices.size()); assertTrue(notices.getLast().contains("上限"));
    }
    @Test void singleRepeatAndSingleOccurrenceNeverRetryForever() {
        for(List<String> q:List.of(List.of("a"),List.of("a","b"))) {
            MP4FailureRecovery r=new MP4FailureRecovery();r.beginAutomatic(id,q);int[]stop={0};
            r.recover(id,q,0,1,false,0,n->fail("single-repeat must stop"),()->stop[0]++,m->assertFalse(m.isBlank()));
            assertEquals(1,stop[0]);
        }
    }
    @Test void randomTraversalVisitsDistinctOccurrencesIncludingDuplicateUrlsAndStops() {
        MP4FailureRecovery r=new MP4FailureRecovery();List<String> q=List.of("same","same","other","last");r.beginAutomatic(id,q);
        List<Integer> starts=new ArrayList<>();int[]stop={0};int index=0;
        for(int i=0;i<4;i++) { int before=starts.size();r.recover(id,q,index,0,true,-123,starts::add,()->stop[0]++,m->{}); if(starts.size()>before)index=starts.getLast(); }
        assertEquals(3,new HashSet<>(starts).size());assertFalse(starts.contains(0));assertEquals(1,stop[0]);
    }
    @Test void largeInvalidQueueStopsAt64AttemptsWithoutScanningForever() {
        List<String> q=new ArrayList<>();for(int i=0;i<200;i++)q.add("url"+i);
        MP4FailureRecovery r=new MP4FailureRecovery();r.beginAutomatic(id,q);int[]stop={0};List<Integer> starts=new ArrayList<>();
        for(int i=0;i<64;i++) r.recover(id,q,i,2,false,0,starts::add,()->stop[0]++,m->{});
        assertEquals(63,starts.size());assertEquals(1,stop[0]);
    }
    @Test void queueEditOrRemovalStopsTraversalAndManualReplacementOwnsOldCompletion() {
        MP4FailureRecovery r=new MP4FailureRecovery();r.beginAutomatic(id,List.of("a","b"));int[]stop={0};
        r.recover(id,List.of("a","edited"),0,2,true,0,n->fail(),()->stop[0]++,m->{});assertEquals(1,stop[0]);
        MP4ResolveIntentRegistry intents=new MP4ResolveIntentRegistry();
        var automatic=intents.beginIfIdle(id,1,"b");r.beginAutomatic(id,List.of("a","b"));r.cancel(id);
        var manual=intents.replace(id,0,"a");assertFalse(intents.isCurrent(id,automatic));assertTrue(intents.isCurrent(id,manual));
        intents.complete(id,automatic);assertTrue(intents.hasPending(id));intents.onQueueRemoval(id,0);assertFalse(intents.isCurrent(id,manual));
    }
    @Test void ordinaryNaturalCompletionBeginsFreshBudget() {
        MP4FailureRecovery r=new MP4FailureRecovery();List<String>q=List.of("a","b");r.beginAutomatic(id,q);
        r.recover(id,q,0,2,false,0,n->assertEquals(1,n),()->fail(),m->{});
        r.beginAutomatic(id,q);r.recover(id,q,0,2,false,0,n->assertEquals(1,n),()->fail(),m->{});
    }
}
