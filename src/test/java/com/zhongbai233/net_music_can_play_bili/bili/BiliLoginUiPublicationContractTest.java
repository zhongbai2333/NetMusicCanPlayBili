package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliLoginUiPublicationContractTest {
    private static final Path OVERLAY = Path.of(
            "src/main/java/com/zhongbai233/net_music_can_play_bili/client/MP4BiliLoginOverlay.java");
    private static final Path SCREEN = Path.of(
            "src/main/java/com/zhongbai233/net_music_can_play_bili/gui/BiliQrLoginScreen.java");

    @Test
    void asynchronousSuccessPublishesCloseDeadlineBeforeDoneFlag() throws IOException {
        for (Path sourceFile : new Path[] { OVERLAY, SCREEN }) {
            String source = Files.readString(sourceFile);
            assertTrue(source.contains("volatile boolean done"),
                    () -> sourceFile + " must keep done as the cross-thread publication barrier");
            assertAssignmentOrder(sourceFile, source, "case SUCCESS -> {", "case SUCCESS_SESSION_ONLY ->",
                    "closeTick = 40;", "done = true;");
            assertAssignmentOrder(sourceFile, source, "case SUCCESS_SESSION_ONLY ->", "case EXPIRED ->",
                    "closeTick = 160;", "done = true;");
        }
    }

    private static void assertAssignmentOrder(Path sourceFile, String source, String blockStart, String blockEnd,
            String stateWrite, String publicationWrite) {
        int start = source.indexOf(blockStart);
        int end = source.indexOf(blockEnd, start + blockStart.length());
        assertTrue(start >= 0 && end > start,
                () -> sourceFile + " is missing the expected login-state block " + blockStart);
        String block = source.substring(start, end);
        int stateIndex = block.indexOf(stateWrite);
        int publicationIndex = block.indexOf(publicationWrite);
        assertTrue(stateIndex >= 0 && publicationIndex > stateIndex,
                () -> sourceFile + " must write " + stateWrite + " before publishing " + publicationWrite);
    }
}
