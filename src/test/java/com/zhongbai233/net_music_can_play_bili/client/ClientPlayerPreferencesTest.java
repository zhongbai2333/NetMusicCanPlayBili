package com.zhongbai233.net_music_can_play_bili.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPreferencesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileUsesDefaultsWithoutCreatingIt() {
        Path file = temporaryDirectory.resolve("preferences.json");
        ClientPlayerPreferences preferences = new ClientPlayerPreferences(file);

        assertFalse(preferences.isControlConsoleGuideDismissed(UUID.randomUUID()));
        assertFalse(Files.exists(file));
    }

    @Test
    void dismissedPlayersPersistIndependentlyAndIdempotently() throws Exception {
        Path file = temporaryDirectory.resolve("nested/preferences.json");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ClientPlayerPreferences preferences = new ClientPlayerPreferences(file);

        assertTrue(preferences.dismissControlConsoleGuide(first));
        assertTrue(preferences.dismissControlConsoleGuide(first));

        ClientPlayerPreferences reloaded = new ClientPlayerPreferences(file);
        assertTrue(reloaded.isControlConsoleGuideDismissed(first));
        assertFalse(reloaded.isControlConsoleGuideDismissed(second));
        assertTrue(reloaded.dismissControlConsoleGuide(second));

        ClientPlayerPreferences reloadedAgain = new ClientPlayerPreferences(file);
        assertTrue(reloadedAgain.isControlConsoleGuideDismissed(first));
        assertTrue(reloadedAgain.isControlConsoleGuideDismissed(second));
    }

    @Test
    void corruptJsonFallsBackAndCanBeRepaired() throws Exception {
        Path file = temporaryDirectory.resolve("preferences.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);
        UUID player = UUID.randomUUID();
        ClientPlayerPreferences preferences = new ClientPlayerPreferences(file);

        assertFalse(preferences.isControlConsoleGuideDismissed(player));
        assertTrue(preferences.dismissControlConsoleGuide(player));
        assertTrue(new ClientPlayerPreferences(file).isControlConsoleGuideDismissed(player));
    }

    @Test
    void invalidUuidDoesNotDiscardValidEntries() throws Exception {
        Path file = temporaryDirectory.resolve("preferences.json");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "controlConsoleGuideDismissedPlayers": ["%s", "not-a-uuid"]
                }
                """.formatted(player), StandardCharsets.UTF_8);

        ClientPlayerPreferences preferences = new ClientPlayerPreferences(file);
        assertTrue(preferences.isControlConsoleGuideDismissed(player));
        assertFalse(preferences.isControlConsoleGuideDismissed(UUID.randomUUID()));
    }
}