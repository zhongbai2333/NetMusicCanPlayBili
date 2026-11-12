package com.zhongbai233.net_music_can_play_bili.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Trusted-player identifiers typed into the console inspector must map to exactly one player. */
class ConsoleTrustedUuidTest {

    @ParameterizedTest @ValueSource(strings={"1-1-1-1-1","00000000-0000-0000-0000-00000000","00000000-0000-0000-0000-0000000","00000000000000000000000000000000","not-a-uuid"})
    void nonCanonicalOrTruncatedIdentifierCannotBecomeADifferentTrustedPlayer(String value) {
        assertThrows(IllegalArgumentException.class, () -> HolographicConsoleInspectorScreen.parseTrustedPlayerIds(value));
    }

    @Test void canonicalUppercaseDuplicatesAndSeparatorsRemainAccepted() {
        String id = "AABBCCDD-1234-5678-9ABC-DEF012345678";
        assertEquals(Set.of(UUID.fromString(id)),
                HolographicConsoleInspectorScreen.parseTrustedPlayerIds(id + "; " + id.toLowerCase(Locale.ROOT)));
    }
}
