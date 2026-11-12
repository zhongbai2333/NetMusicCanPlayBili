package com.zhongbai233.net_music_can_play_bili.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BiliToolCredentialBoundaryContractTest {
    private static final List<String> FORBIDDEN_AUTOMATIC_CREDENTIAL_READS = List.of(
            "net_music_can_play_bili.json",
            "net_music_can_play_bili-private",
            "bili-credentials.json.enc",
            "auto_sessdata(",
            "auto_detect_sessdata(",
            ".get(\"sessdata\"",
            ".get('sessdata'");

    @Test
    void diagnosticsNeverReadGameCredentialStoresAutomatically() throws Exception {
        Map<String, String> sources = Map.of(
                "probe", read("tools/bili_timeline_probe.py"),
                "gui", read("tools/bili_timeline_gui.py"),
                "dolby", read("tools/bili_download_dolby.py"));

        sources.forEach((name, source) -> FORBIDDEN_AUTOMATIC_CREDENTIAL_READS.forEach(forbidden ->
                assertFalse(source.contains(forbidden),
                        () -> name + " must not automatically read legacy config or encrypted vault: " + forbidden)));
    }
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
