package com.zhongbai233.net_music_can_play_bili.bili;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Obtains an OS installation identity without using unstable hostnames or network adapters. */
final class BiliMachineIdentity {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_MACHINE_ID_FILE_BYTES = 256;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 8 * 1024;
    private static final Pattern UUID_LIKE = Pattern.compile(
            "(?i)(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final Pattern MAC_PLATFORM_UUID = Pattern.compile(
            "(?i)\\\"IOPlatformUUID\\\"\\s*=\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern WINDOWS_MACHINE_GUID_LINE = Pattern.compile(
            "(?im)^\\s*MachineGuid\\s+REG_[A-Z0-9_]+\\s+(.+?)\\s*$");

    private BiliMachineIdentity() {
    }

    static Optional<String> current() {
        return resolve(System.getProperty("os.name", ""), System.getenv("SystemRoot"),
                BiliMachineIdentity::readSmallTextFile, BiliMachineIdentity::runSmallCommand);
    }

    static Optional<String> resolve(String osName, String windowsRoot, TextFileReader files,
            CommandRunner commands) {
        String normalizedOs = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
            return macOs(commands);
        }
        if (normalizedOs.startsWith("windows")) {
            return windows(windowsRoot, commands);
        }
        if (normalizedOs.contains("linux")) {
            return linux(files);
        }
        return Optional.empty();
    }

    private static Optional<String> linux(TextFileReader files) {
        for (Path path : List.of(Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id"))) {
            Optional<String> content = files.read(path, MAX_MACHINE_ID_FILE_BYTES);
            if (content.isPresent()) {
                Optional<String> normalized = normalize("linux", content.get());
                if (normalized.isPresent()) {
                    return normalized;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> macOs(CommandRunner commands) {
        Optional<String> output = commands.run(
                List.of("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"),
                COMMAND_TIMEOUT, MAX_COMMAND_OUTPUT_BYTES);
        if (output.isEmpty()) {
            return Optional.empty();
        }
        Matcher property = MAC_PLATFORM_UUID.matcher(output.get());
        return property.find() ? normalize("macos", property.group(1)) : Optional.empty();
    }

    private static Optional<String> windows(String windowsRoot, CommandRunner commands) {
        String executable = windowsRoot == null || windowsRoot.isBlank()
                ? "reg.exe"
                : Path.of(windowsRoot, "System32", "reg.exe").toString();
        List<String> base = List.of(executable, "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
        Optional<String> output = commands.run(append(base, "/reg:64"), COMMAND_TIMEOUT,
                MAX_COMMAND_OUTPUT_BYTES);
        Optional<String> identity = parseWindowsOutput(output);
        if (identity.isPresent()) {
            return identity;
        }
        return parseWindowsOutput(commands.run(base, COMMAND_TIMEOUT, MAX_COMMAND_OUTPUT_BYTES));
    }

    private static Optional<String> parseWindowsOutput(Optional<String> output) {
        if (output.isEmpty()) {
            return Optional.empty();
        }
        Matcher line = WINDOWS_MACHINE_GUID_LINE.matcher(output.get());
        if (!line.find()) {
            return Optional.empty();
        }
        return normalize("windows", line.group(1));
    }

    private static Optional<String> normalize(String platform, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String candidate = raw.trim();
        if (candidate.length() >= 2 && candidate.startsWith("{") && candidate.endsWith("}")) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        if (!UUID_LIKE.matcher(candidate).matches()) {
            return Optional.empty();
        }
        String compact = candidate.replace("-", "").toLowerCase(Locale.ROOT);
        if (compact.length() != 32 || compact.chars().allMatch(ch -> ch == '0')) {
            return Optional.empty();
        }
        return Optional.of(platform + ":" + compact);
    }

    private static List<String> append(List<String> base, String value) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(base.size() + 1);
        command.addAll(base);
        command.add(value);
        return List.copyOf(command);
    }

    private static Optional<String> readSmallTextFile(Path path, int maximumBytes) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) <= 0 || Files.size(path) > maximumBytes) {
                return Optional.empty();
            }
            byte[] bytes = Files.readAllBytes(path);
            return bytes.length <= maximumBytes
                    ? Optional.of(new String(bytes, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException | SecurityException error) {
            return Optional.empty();
        }
    }

    private static Optional<String> runSmallCommand(List<String> command, Duration timeout, int maximumBytes) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return Optional.empty();
            }
            byte[] output = process.getInputStream().readNBytes(maximumBytes + 1);
            if (process.exitValue() != 0 || output.length > maximumBytes) {
                return Optional.empty();
            }
            return Optional.of(new String(output, StandardCharsets.UTF_8));
        } catch (IOException | SecurityException error) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @FunctionalInterface
    interface TextFileReader {
        Optional<String> read(Path path, int maximumBytes);
    }

    @FunctionalInterface
    interface CommandRunner {
        Optional<String> run(List<String> command, Duration timeout, int maximumBytes);
    }
}
