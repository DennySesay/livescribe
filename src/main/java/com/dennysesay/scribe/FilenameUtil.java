package com.dennysesay.scribe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class FilenameUtil {

    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneOffset.UTC);

    private FilenameUtil() {}

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        String cleaned = s.trim().replaceAll("\\s+", "_");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned;
    }

    public static Path buildBasePath(Path baseDir,
                                     String provider,
                                     String streamer,
                                     Instant timestampUtc,
                                     String quality,
                                     boolean dateSharded,
                                     boolean addShortUuid) throws IOException {

        Path targetBase = (baseDir != null) ? baseDir : defaultBaseDir();

        String name = buildFileName(streamer, timestampUtc);

        Files.createDirectories(targetBase);
        return targetBase.resolve(name); // no extension
    }

    public static Path tempDownloadPath(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName().toString() + ".ts.part");
    }

    public static Path tsPath(Path basePath) {
        return Path.of(basePath.toString() + ".ts");
    }

    public static Path mp4Path(Path basePath) {
        return Path.of(basePath.toString() + ".mp4");
    }

    public static String buildFileName(String streamer, Instant timestampUtc) {
        String s = sanitize(streamer);
        Instant tsInstant = (timestampUtc != null) ? timestampUtc : Instant.now();
        return s + "-" + HUMAN_TS.format(tsInstant);
    }

    public static String buildFileName(String streamer) {
        return buildFileName(streamer, Instant.now());
    }

    public static Path defaultBaseDir() {
        String home = System.getProperty("user.home");
        return Path.of(home, "livescribe");
    }

    public static Path buildBasePath(String streamer, Instant timestampUtc) throws IOException {
        Path base = defaultBaseDir();
        String name = buildFileName(streamer, timestampUtc);
        Files.createDirectories(base);
        return base.resolve(name); // no extension
    }
}

