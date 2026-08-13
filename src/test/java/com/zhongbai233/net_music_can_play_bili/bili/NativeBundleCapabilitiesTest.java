package com.zhongbai233.net_music_can_play_bili.bili;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class NativeBundleCapabilitiesTest {
    private static final Pattern RELEASE = Pattern.compile("(?m)^- Release: `(media-min-v48)`$");

    @Test
    void generatedCapabilityMatchesPackagedNativeRelease() throws IOException {
        String readme;
        try (InputStream input = NativeBundleCapabilitiesTest.class.getResourceAsStream("/native/README.md")) {
            if (input == null) {
                throw new AssertionError("missing packaged native/README.md");
            }
            readme = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = RELEASE.matcher(readme);
        if (!matcher.find()) {
            throw new AssertionError("native/README.md has no supported release identity");
        }
        String release = matcher.group(1);
        assertEquals(release, NativeBundleCapabilities.RELEASE);
        assertEquals(false, NativeBundleCapabilities.SOFTWARE_AV1_AVAILABLE);
    }
}
