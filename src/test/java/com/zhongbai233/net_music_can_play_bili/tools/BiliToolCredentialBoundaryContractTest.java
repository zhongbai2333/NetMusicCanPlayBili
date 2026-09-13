package com.zhongbai233.net_music_can_play_bili.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void diagnosticsOnlyAcceptExplicitArgumentOrEnvironmentCredentials() throws Exception {
        Map<String, String> sources = Map.of(
                "probe", read("tools/bili_timeline_probe.py"),
                "gui", read("tools/bili_timeline_gui.py"),
                "dolby", read("tools/bili_download_dolby.py"));

        sources.forEach((name, source) -> FORBIDDEN_AUTOMATIC_CREDENTIAL_READS.forEach(forbidden ->
                assertFalse(source.contains(forbidden),
                        () -> name + " must not automatically read legacy config or encrypted vault: " + forbidden)));

        String probe = sources.get("probe");
        assertTrue(probe.contains("def resolve_sessdata(explicit: str = \"\") -> str:"));
        assertTrue(probe.contains("explicit.strip() or os.environ.get(\"BILI_SESSDATA\", \"\").strip()"));
        assertTrue(probe.contains("sessdata = resolve_sessdata(args.sessdata)"));

        String gui = sources.get("gui");
        assertTrue(gui.contains("probe.resolve_sessdata(self.args.sessdata)"));

        String dolby = sources.get("dolby");
        assertTrue(dolby.contains("def resolve_sessdata(explicit=\"\") -> str:"));
        assertTrue(dolby.contains("os.environ.get(\"BILI_SESSDATA\", \"\").strip()"));
        assertTrue(dolby.contains("sessdata = resolve_sessdata(args.sessdata)"));
    }

    @Test
    void generatedIntroductionDescribesTheMachineBoundVault() throws Exception {
        String generator = read("tools/generate_mcmod_intro_docx.py");

        assertTrue(generator.contains("游戏目录的机器绑定加密凭据库"));
        assertTrue(generator.contains("不再写入 config"));
        assertFalse(generator.contains("登录信息保存在本地配置中"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
