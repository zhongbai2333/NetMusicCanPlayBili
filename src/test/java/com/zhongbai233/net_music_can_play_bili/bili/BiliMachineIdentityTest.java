package com.zhongbai233.net_music_can_play_bili.bili;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliMachineIdentityTest {
    private static final String COMPACT_ID = "0123456789abcdef0123456789abcdef";
    private static final String UUID_ID = "01234567-89AB-CDEF-0123-456789ABCDEF";

    @Test
    void linuxUsesMachineIdWithoutLaunchingACommand() {
        AtomicInteger commandCalls = new AtomicInteger();

        Optional<String> identity = BiliMachineIdentity.resolve("Linux", null,
                fileReader(Map.of(Path.of("/etc/machine-id"), COMPACT_ID + "\n")),
                (command, timeout, limit) -> {
                    commandCalls.incrementAndGet();
                    return Optional.empty();
                });

        assertEquals(Optional.of("linux:" + COMPACT_ID), identity);
        assertEquals(0, commandCalls.get());
    }

    @Test
    void linuxFallsBackToDbusMachineIdWhenPrimaryIsInvalid() {
        Optional<String> identity = BiliMachineIdentity.resolve("Linux", null,
                fileReader(Map.of(
                        Path.of("/etc/machine-id"), "uninitialized\n",
                        Path.of("/var/lib/dbus/machine-id"), COMPACT_ID)),
                noCommands());

        assertEquals(Optional.of("linux:" + COMPACT_ID), identity);
    }

    @Test
    void macOsReadsOnlyThePlatformUuidProperty() {
        Optional<String> identity = BiliMachineIdentity.resolve("Mac OS X", null,
                noFiles(), (command, timeout, limit) -> {
                    assertEquals(List.of("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), command);
                    assertEquals(Duration.ofSeconds(2), timeout);
                    assertEquals(8 * 1024, limit);
                    return Optional.of("    \"IOPlatform/graphql\" = \"ignored\"\n"
                            + "    \"IOPlatformUUID\" = \"" + UUID_ID + "\"\n");
                });

        assertEquals(Optional.of("macos:" + COMPACT_ID), identity);
    }

    @Test
    void darwinIsNotMisclassifiedAsWindows() {
        Optional<String> identity = BiliMachineIdentity.resolve("Darwin", null,
                noFiles(), (command, timeout, limit) -> Optional.of(
                        "\"IOPlatformUUID\" = \"" + UUID_ID + "\""));

        assertEquals(Optional.of("macos:" + COMPACT_ID), identity);
    }

    @Test
    void windowsUsesMachineGuidAndFallsBackWithoutRegistryViewFlag() {
        List<List<String>> invocations = new ArrayList<>();
        Optional<String> identity = BiliMachineIdentity.resolve("Windows 11", "C:\\Windows",
                noFiles(), (command, timeout, limit) -> {
                    invocations.add(command);
                    if (command.contains("/reg:64")) {
                        return Optional.empty();
                    }
                    return Optional.of("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Cryptography\r\n"
                            + "    MachineGuid    REG_SZ    " + UUID_ID + "\r\n");
                });

        assertEquals(Optional.of("windows:" + COMPACT_ID), identity);
        assertEquals(2, invocations.size());
        assertTrue(invocations.getFirst().contains("/reg:64"));
        assertTrue(invocations.getLast().getFirst().endsWith("reg.exe"));
        assertEquals("MachineGuid", invocations.getLast().get(4));
    }

    @Test
    void invalidAndUnsupportedIdentitiesFailClosed() {
        assertEquals(Optional.empty(), BiliMachineIdentity.resolve("Linux", null,
                fileReader(Map.of(Path.of("/etc/machine-id"), "00000000000000000000000000000000")),
                noCommands()));
        assertEquals(Optional.empty(), BiliMachineIdentity.resolve("Linux", null,
                fileReader(Map.of(Path.of("/etc/machine-id"), "prefix-" + COMPACT_ID + "-suffix")),
                noCommands()));
        assertEquals(Optional.empty(), BiliMachineIdentity.resolve("FreeBSD", null,
                noFiles(), noCommands()));
    }

    @Test
    void bracesAreAllowedButTrailingRegistryGarbageIsRejected() {
        Optional<String> braced = BiliMachineIdentity.resolve("Windows 11", null, noFiles(),
                (command, timeout, limit) -> Optional.of(
                        "MachineGuid    REG_SZ    {" + UUID_ID + "}\r\n"));
        assertEquals(Optional.of("windows:" + COMPACT_ID), braced);

        Optional<String> garbage = BiliMachineIdentity.resolve("Windows 11", null, noFiles(),
                (command, timeout, limit) -> Optional.of(
                        "MachineGuid    REG_SZ    " + UUID_ID + " unexpected\r\n"));
        assertEquals(Optional.empty(), garbage);
    }

    private static BiliMachineIdentity.TextFileReader fileReader(Map<Path, String> values) {
        return (path, maximumBytes) -> Optional.ofNullable(values.get(path));
    }

    private static BiliMachineIdentity.TextFileReader noFiles() {
        return (path, maximumBytes) -> Optional.empty();
    }

    private static BiliMachineIdentity.CommandRunner noCommands() {
        return (command, timeout, maximumBytes) -> Optional.empty();
    }
}
