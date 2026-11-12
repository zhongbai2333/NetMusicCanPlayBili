package com.zhongbai233.net_music_can_play_bili.server;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual legacy JSON load/normalization and atomic schema upgrade in an isolated temporary world. */
class WhitelistLegacyJsonProductionTest {
    @TempDir Path world;BiliWhitelistPersistenceProductionTest f;
    @BeforeEach void setup(){f=new BiliWhitelistPersistenceProductionTest();f.world=world;f.setup();}
    @AfterEach void restore(){f.restore();}
    void write(String json)throws Exception{Files.createDirectories(f.file().getParent());Files.writeString(f.file(),json);f.reload();}
    @Test void legacyNullCollectionsAndFieldsNormalizeBeforeNewMutationWritesSchemaTwo()throws Exception{
        write("{\"schemaVersion\":1,\"entries\":{\"url:https://legacy.test/a\":{\"id\":\"url:https://legacy.test/a\",\"type\":null,\"addedAt\":null,\"comments\":null}},\"removalRecords\":null}");
        var entry=BiliWhitelistManager.entries(f.server).getFirst();assertEquals("",entry.type);assertEquals("",entry.addedAt);assertTrue(entry.comments.isEmpty());
        assertEquals(0,BiliWhitelistManager.removalRecordCount(f.server));
        BiliWhitelistManager.addComment(f.server,"https://legacy.test/a",f.actor,"new","");
        String json=Files.readString(f.file());assertTrue(json.contains("\"schemaVersion\": 2"));f.reload();assertEquals("new",BiliWhitelistManager.entries(f.server).getFirst().comments.getFirst().text);
    }
    @Test void missingLegacyRemovalRecordIdIsStableAcrossReloadAndSnapshotCopies()throws Exception{
        write("{\"entries\":{},\"removalRecords\":[{\"entry\":{\"id\":\"bili:BV1xx411c7mD\",\"comments\":null},\"removedAt\":\"2020-01-01\",\"removedByName\":\"Admin\",\"note\":\"old\"}]}");
        var record=BiliWhitelistManager.removalRecords(f.server).getFirst();assertFalse(record.recordId.isBlank());String first=record.recordId;
        record.recordId="changed";f.reload();assertEquals(first,BiliWhitelistManager.removalRecords(f.server).getFirst().recordId);
    }
    @Test void legacyHistoryIsSortedNewestFirstAndNullRecordsAreDropped()throws Exception{
        write("{\"entries\":{},\"removalRecords\":[null,{\"removedAt\":\"2020-01-01\",\"note\":\"old\"},{\"removedAt\":\"2022-01-01\",\"note\":\"new\"}]}");
        assertEquals(2,BiliWhitelistManager.removalRecordCount(f.server));assertEquals("new",BiliWhitelistManager.removalRecordPage(f.server,0,1).getFirst().note);
        assertEquals("old",BiliWhitelistManager.removalRecordPage(f.server,1,1).getFirst().note);
    }
    @Test void completelyNullLegacyCollectionsLoadAsEmptyAndAcceptNewEntry()throws Exception{
        write("{\"schemaVersion\":1,\"entries\":null,\"removalRecords\":null}");assertEquals(0,BiliWhitelistManager.entryCount(f.server));
        assertEquals(BiliWhitelistManager.AddResult.Status.ADDED,BiliWhitelistManager.add(f.server,"https://new.test/a",f.actor).status());
        f.reload();assertEquals(1,BiliWhitelistManager.entryCount(f.server));
    }
}
