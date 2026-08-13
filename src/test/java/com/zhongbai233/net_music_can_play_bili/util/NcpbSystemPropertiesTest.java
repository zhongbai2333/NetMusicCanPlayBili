package com.zhongbai233.net_music_can_play_bili.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NcpbSystemPropertiesTest {
    private static final String KEY = "ncpb.test.value";
    private static final String LEGACY_KEY = "bili.test.value";

    @AfterEach
    void clearProperties() {
        System.clearProperty(KEY);
        System.clearProperty(LEGACY_KEY);
    }

    @Test
    void prefersValidNewKey() {
        System.setProperty(KEY, "42");
        System.setProperty(LEGACY_KEY, "21");

        assertEquals(42, NcpbSystemProperties.intValue(KEY, LEGACY_KEY, 7));
    }

    @Test
    void trimsStringsAndFallsBackPastBlankValues() {
        System.setProperty(KEY, "   ");
        System.setProperty(LEGACY_KEY, " legacy-value ");
        assertEquals("legacy-value", NcpbSystemProperties.stringValue(KEY, LEGACY_KEY, "fallback"));

        System.clearProperty(LEGACY_KEY);
        assertEquals("fallback", NcpbSystemProperties.stringValue(KEY, LEGACY_KEY, "fallback"));
    }

    @Test
    void fallsBackToLegacyKeyWhenNewValueIsInvalid() {
        System.setProperty(KEY, "not-a-number");
        System.setProperty(LEGACY_KEY, "21");

        assertEquals(21L, NcpbSystemProperties.longValue(KEY, LEGACY_KEY, 7L));
    }

    @Test
    void usesDefaultWhenNoValidCandidateExists() {
        System.setProperty(KEY, "NaN");
        System.setProperty(LEGACY_KEY, "Infinity");

        assertEquals(0.72D, NcpbSystemProperties.doubleValue(KEY, LEGACY_KEY, 0.72D));
    }

    @Test
    void parsesFiniteFloatsAndFallsBackPastNonFiniteValues() {
        System.setProperty(KEY, "NaN");
        System.setProperty(LEGACY_KEY, "1.25");
        assertEquals(1.25F, NcpbSystemProperties.floatValue(KEY, LEGACY_KEY, 0.5F));

        System.setProperty(LEGACY_KEY, "Infinity");
        assertEquals(0.5F, NcpbSystemProperties.floatValue(KEY, LEGACY_KEY, 0.5F));
    }

    @Test
    void parsesBooleansStrictlyAndCaseInsensitively() {
        System.setProperty(KEY, "TRUE");
        assertTrue(NcpbSystemProperties.booleanValue(KEY, LEGACY_KEY, false));

        System.setProperty(KEY, "yes");
        System.setProperty(LEGACY_KEY, "false");
        assertFalse(NcpbSystemProperties.booleanValue(KEY, LEGACY_KEY, true));
    }
}
