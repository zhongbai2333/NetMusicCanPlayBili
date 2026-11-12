package com.zhongbai233.net_music_can_play_bili.server;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class DeferredRequestGateTest {
    @Test void olderRequestCannotApplyOrClearManualReplacement() {var g=new DeferredRequestGate<String>(2);long old=g.begin("player"),next=g.begin("player");assertFalse(g.current("player",old));g.complete("player",old);assertTrue(g.current("player",next));g.complete("player",next);assertFalse(g.current("player",next));}
    @Test void boundedCapacityAndClearInvalidatePendingRequests() {var g=new DeferredRequestGate<String>(1);long a=g.begin("a"),b=g.begin("b");assertFalse(g.current("a",a));assertTrue(g.current("b",b));g.clear();assertFalse(g.current("b",b));}
}
