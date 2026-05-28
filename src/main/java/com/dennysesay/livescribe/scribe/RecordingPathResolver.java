package com.dennysesay.livescribe.scribe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class RecordingPathResolver {

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneOffset.UTC);

    private RecordingPathResolver() {}

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        String cleaned = s.trim().replaceAll("\\s+", "_");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned;
    }

    public static Path resolveRecordingPath(Path outputDir,
                                           String channelName,
                                           Instant timestamp) throws IOException {
        Path targetBase = (outputDir != null) ? outputDir : defaultBaseDir();
        String name = generateFileName(channelName, timestamp);
        Files.createDirectories(targetBase);
        return targetBase.resolve(name); // no extension
    }

    public static Path resolvePartPath(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName().toString() + ".ts.part");
    }

    public static Path resolveTsPath(Path basePath) {
        return Path.of(basePath.toString() + ".ts");
    }

    public static Path resolveMp4Path(Path basePath) {
        return Path.of(basePath.toString() + ".mp4");
    }

    public static String generateFileName(String channelName, Instant timestamp) {
        String s = sanitize(channelName);
        Instant tsInstant = (timestamp != null) ? timestamp : Instant.now();
        return s + "-" + FILE_TIMESTAMP_FORMATTER.format(tsInstant);
    }

    public static String generateFileName(String channelName) {
        return generateFileName(channelName, Instant.now());
    }

    public static Path defaultBaseDir() {
        String home = System.getProperty("user.home");
        return Path.of(home, "livescribe");
    }

    public static Path resolveRecordingPath(String channelName, Instant timestamp) throws IOException {
        Path base = defaultBaseDir();
        String name = generateFileName(channelName, timestamp);
        Files.createDirectories(base);
        return base.resolve(name); // no extension
    }
}
