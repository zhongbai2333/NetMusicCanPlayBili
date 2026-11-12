package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real manager and JSON filesystem persistence, isolated to a temporary mocked world root. */
class BiliWhitelistPersistenceProductionTest {
    private static final String URL="https://example.test/song.mp4";
    @TempDir Path world;
    MinecraftServer server; ServerPlayer actor; boolean previousEnabled;
    @BeforeEach void setup() {
        PlatformBootstrap.initialize(); server=mock(MinecraftServer.class); actor=mock(ServerPlayer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        when(actor.getUUID()).thenReturn(new UUID(11,12));
        when(actor.getDisplayName()).thenReturn(Component.literal("=Admin,\"Name\""));
        previousEnabled=Config.enableLinkWhitelist; Config.enableLinkWhitelist=true;
    }
    @AfterEach void restore() { Config.enableLinkWhitelist=previousEnabled; }
    Path file() { return world.resolve("net_music_can_play_bili/net_music_can_play_bili_link_whitelist.json"); }
    BiliWhitelistManager.Entry add() throws Exception { return BiliWhitelistManager.add(server,URL,actor).entry(); }
    void reload() throws Exception {
        var field=BiliWhitelistManager.class.getDeclaredField("loadedPath"); field.setAccessible(true); field.set(null,null);
    }
    @Test void addWritesActualJsonAndReloadPreservesAuthorAndCanonicalIdentity() throws Exception {
        var entry=add(); assertEquals("url:"+URL,entry.id); assertTrue(Files.isRegularFile(file()));
        assertFalse(Files.exists(file().resolveSibling(file().getFileName()+".tmp")));
        reload(); var loaded=BiliWhitelistManager.entries(server).getFirst();
        assertEquals(entry.addedAt,loaded.addedAt); assertEquals(actor.getUUID().toString(),loaded.addedByUuid);
        assertTrue(BiliWhitelistManager.isAllowed(server,URL));
    }
    @Test void duplicateNormalizedUrlDoesNotRewriteJsonOrAddSecondEntry() throws Exception {
        add(); String before=Files.readString(file());
        assertEquals(BiliWhitelistManager.AddResult.Status.DUPLICATE,
                BiliWhitelistManager.add(server,"HTTPS://EXAMPLE.TEST/song.mp4#fragment",actor).status());
        assertEquals(before,Files.readString(file())); assertEquals(1,BiliWhitelistManager.entryCount(server));
    }
    @Test void emptyInputIsInvalidAndDoesNotCreateFile() throws Exception {
        assertEquals(BiliWhitelistManager.AddResult.Status.INVALID,BiliWhitelistManager.add(server," ",actor).status());
        assertFalse(Files.exists(file()));
    }
    @Test void commentAndRemovalReloadAsHistoricalSnapshot() throws Exception {
        var e=add(); assertEquals(BiliWhitelistManager.CommentResult.Status.ADDED,
                BiliWhitelistManager.addComment(server,URL,actor," review\naccepted ",e.addedAt).status());
        assertEquals(BiliWhitelistManager.RemoveResult.Status.REMOVED,
                BiliWhitelistManager.remove(server,URL,actor," removed\rby admin ",e.addedAt).status());
        reload(); assertEquals(0,BiliWhitelistManager.entryCount(server));
        var history=BiliWhitelistManager.removalRecords(server).getFirst();
        assertEquals("review accepted",history.entry.comments.getFirst().text);
        assertEquals("removed by admin",history.note); assertFalse(history.recordId.isBlank());
        assertEquals(actor.getUUID().toString(),history.removedByUuid);
    }
    @Test void staleCommentCannotMutateExistingEntryOrDisk() throws Exception {
        add(); String before=Files.readString(file());
        assertEquals(BiliWhitelistManager.CommentResult.Status.STALE,
                BiliWhitelistManager.addComment(server,URL,actor,"comment","wrong-version").status());
        assertEquals(before,Files.readString(file())); assertTrue(BiliWhitelistManager.entries(server).getFirst().comments.isEmpty());
    }
    @Test void staleRemovalCannotRemoveExistingEntryOrDisk() throws Exception {
        add(); String before=Files.readString(file());
        assertEquals(BiliWhitelistManager.RemoveResult.Status.STALE,
                BiliWhitelistManager.remove(server,URL,actor,"reason","wrong-version").status());
        assertEquals(before,Files.readString(file())); assertEquals(1,BiliWhitelistManager.entryCount(server));
    }
    @Test void blankCommentAndRemovalReasonAreRequiredBeforeMutation() throws Exception {
        var e=add(); String before=Files.readString(file());
        assertEquals(BiliWhitelistManager.CommentResult.Status.COMMENT_REQUIRED,
                BiliWhitelistManager.addComment(server,URL,actor," \r\n ",e.addedAt).status());
        assertEquals(BiliWhitelistManager.RemoveResult.Status.NOTE_REQUIRED,
                BiliWhitelistManager.remove(server,URL,actor," ",e.addedAt).status());
        assertEquals(before,Files.readString(file()));
    }
    @Test void absentTargetProducesMissingWithoutCreatingHistory() throws Exception {
        assertEquals(BiliWhitelistManager.RemoveResult.Status.MISSING,BiliWhitelistManager.remove(server,URL,actor,"reason").status());
        assertEquals(BiliWhitelistManager.CommentResult.Status.MISSING,BiliWhitelistManager.addComment(server,URL,actor,"text").status());
        assertEquals(0,BiliWhitelistManager.removalRecordCount(server)); assertFalse(Files.exists(file()));
    }
    @Test void mutableReturnedSnapshotsCannotCorruptManagerOrRemovalHistory() throws Exception {
        var e=add(); BiliWhitelistManager.addComment(server,URL,actor,"original",e.addedAt);
        var copy=BiliWhitelistManager.entries(server).getFirst(); copy.id="corrupt"; copy.comments.getFirst().text="corrupt";
        assertEquals("original",BiliWhitelistManager.entries(server).getFirst().comments.getFirst().text);
        BiliWhitelistManager.remove(server,URL,actor,"reason",e.addedAt);
        var history=BiliWhitelistManager.removalRecords(server).getFirst(); history.entry.comments.clear(); history.note="bad";
        assertEquals("reason",BiliWhitelistManager.removalRecords(server).getFirst().note);
        assertEquals(1,BiliWhitelistManager.removalRecords(server).getFirst().entry.comments.size());
    }
    @Test void csvExportIncludesActiveAndRemovedWithFormulaEscaping() throws Exception {
        var e=add(); BiliWhitelistManager.addComment(server,URL,actor,"=formula",e.addedAt);
        BiliWhitelistManager.remove(server,URL,actor,"+remove",e.addedAt);
        BiliWhitelistManager.add(server,"https://example.test/second.mp3",actor);
        String csv=BiliWhitelistManager.exportCsv(server);
        assertTrue(csv.contains("\"ACTIVE\"")); assertTrue(csv.contains("\"REMOVED\""));
        assertTrue(csv.contains("\"'=Admin,\"\"Name\"\"\""));
        assertTrue(csv.contains("\"'+remove\"")); assertTrue(csv.endsWith("\r\n"));
    }
    @Test void corruptJsonFailsClosedAndPreservesOriginalFile() throws Exception {
        Files.createDirectories(file().getParent()); Files.writeString(file(),"{invalid json");
        assertFalse(BiliWhitelistManager.isAllowed(server,URL));
        assertThrows(java.io.IOException.class,()->BiliWhitelistManager.add(server,URL,actor));
        assertEquals("{invalid json",Files.readString(file())); assertEquals(0,BiliWhitelistManager.entryCount(server));
    }
    @Test void writeFailureLeavesInMemoryStateUnchangedAndCleansTemporaryFile() throws Exception {
        var e=add(); Files.delete(file()); Files.createDirectory(file());
        assertThrows(java.io.IOException.class,()->BiliWhitelistManager.addComment(server,URL,actor,"new",e.addedAt));
        assertTrue(BiliWhitelistManager.entries(server).getFirst().comments.isEmpty());
        assertFalse(Files.exists(file().resolveSibling(file().getFileName()+".tmp")));
    }
    @Test void reviewSnapshotClampsPagesAndPreservesCounts() throws Exception {
        for(int i=0;i<5;i++) BiliWhitelistManager.add(server,"https://example.test/"+i,actor);
        var snapshot=BiliWhitelistManager.reviewSnapshot(server,999,2,999,2);
        assertEquals(5,snapshot.totalEntries()); assertEquals(4,snapshot.entryOffset());
        assertEquals(1,snapshot.entries().size()); assertEquals(0,snapshot.removalOffset());
        assertEquals(0,snapshot.totalRemovalRecords());
    }
    @Test void overlongAuditTextIsBoundedBeforePersisting() throws Exception {
        var e=add(); BiliWhitelistManager.addComment(server,URL,actor,"x".repeat(300),e.addedAt);
        reload(); assertEquals(256,BiliWhitelistManager.entries(server).getFirst().comments.getFirst().text.length());
    }
    @Test void disabledWhitelistAllowsUnlistedUrlWithoutReadingStorage() {
        Config.enableLinkWhitelist=false;
        assertTrue(BiliWhitelistManager.isAllowed(server,URL)); assertFalse(Files.exists(file()));
        verify(server,never()).getWorldPath(any());
    }
}
