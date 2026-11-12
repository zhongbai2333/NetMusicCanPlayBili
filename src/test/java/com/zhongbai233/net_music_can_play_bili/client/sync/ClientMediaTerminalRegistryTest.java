package com.zhongbai233.net_music_can_play_bili.client.sync;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.AfterEach;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class ClientMediaTerminalRegistryTest {
    @AfterEach void clear(){ClientMediaTerminalRegistry.clear();}
    @Test void retainedLogicalSessionRequiresFreshPhysicalTokenAndOwner() {var id=UUID.randomUUID();var owner=UUID.randomUUID();assertFalse(ClientMediaTerminalRegistry.observe(id,owner,"logical","old",0));assertTrue(ClientMediaTerminalRegistry.reporter(id,"logical","old",owner));assertFalse(ClientMediaTerminalRegistry.reporter(id,"logical","old",UUID.randomUUID()));assertTrue(ClientMediaTerminalRegistry.observe(id,owner,"logical","new",0));assertFalse(ClientMediaTerminalRegistry.current(id,"logical","old"));assertTrue(ClientMediaTerminalRegistry.current(id,"logical","new"));ClientMediaTerminalRegistry.remove(id);assertFalse(ClientMediaTerminalRegistry.current(id,"logical","new"));}
    @Test void failuresOfKnownDurationStillHaveOwnerAdmissionButEmptyTokensNeverDo(){var id=UUID.randomUUID();var owner=UUID.randomUUID();ClientMediaTerminalRegistry.observe(id,owner,"known","physical",10);assertTrue(ClientMediaTerminalRegistry.reporter(id,"known","physical",owner));assertFalse(ClientMediaTerminalRegistry.reporter(id,"known","",owner));}
    @Test void confirmedVideoOnlyAdmissionSurvivesOrdinarySyncButNotPhysicalReplacement() {
        var id=UUID.randomUUID();var owner=UUID.randomUUID();ClientMediaTerminalRegistry.observe(id,owner,"same","a",0);
        ClientMediaTerminalRegistry.markVideoOnly(id,"same","a");assertTrue(ClientMediaTerminalRegistry.videoOnly(id,"same"));
        ClientMediaTerminalRegistry.observe(id,owner,"same","a",0);assertTrue(ClientMediaTerminalRegistry.videoOnly(id,"same"));
        ClientMediaTerminalRegistry.observe(id,owner,"same","b",0);assertFalse(ClientMediaTerminalRegistry.videoOnly(id,"same"));
        ClientMediaTerminalRegistry.markVideoOnly(id,"same","a");assertFalse(ClientMediaTerminalRegistry.videoOnly(id,"same"));
    }
}
