package com.dennysesay.scribe;

import com.dennysesay.provider.StreamingClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class StreamlinkResolver {
    private final StreamingClient client;
    private final String stream;
    private final String filename;
    private final Path outputBaseDir;
    private final boolean deleteTs;
    private volatile Process currentProcess;
    private Path currentBasePath;

    public StreamlinkResolver(StreamingClient client, String stream, String filename, Path outputBaseDir, boolean deleteTs) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.stream = Objects.requireNonNull(stream, "stream must not be null");
        this.filename = Objects.requireNonNull(filename, "filename must not be null");
        this.outputBaseDir = Objects.requireNonNull(outputBaseDir, "outputBaseDir must not be null");
        this.deleteTs = deleteTs;
    }

    private int runCommand(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            currentProcess = process;

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            System.out.println("\nExited with code: " + exitCode);
            return exitCode;
        } catch (InterruptedException e) {
            Process p = currentProcess;
            if (p != null && p.isAlive()) {
                System.out.println("Terminating subprocess: " + String.join(" ", command));
                p.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new RuntimeException("Process was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process: " + String.join(" ", command), e);
        } finally {
            currentProcess = null;
        }
    }

    public void resolve() {
        try {
            String baseName = (this.filename != null && !this.filename.isBlank()) ? this.filename : this.stream;
            Instant ts = Instant.now();
            this.currentBasePath = FilenameUtil.buildBasePath(outputBaseDir, null, baseName, ts, null, false, false);
            String tsOutput = FilenameUtil.tsPath(currentBasePath).toString();

            int exitCode = runCommand(List.of(
                    "streamlink",
                    client.createUrl(stream),
                    "best",
                    "-o",
                    tsOutput
            ));
            if (exitCode == 0) {
                boolean converted = convertToMp4();
                if (converted && deleteTs) {
                    deleteTsFile();
                }
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                System.out.println("Stream download interrupted: " + stream);
                Thread.currentThread().interrupt();
                return;
            }
            throw e;
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare output path", e);
        }
    }

    public boolean convertToMp4() {
        try {
            String tsInput = FilenameUtil.tsPath(Objects.requireNonNull(currentBasePath, "Output path not initialized")).toString();
            String mp4Output = FilenameUtil.mp4Path(currentBasePath).toString();

            int exitCode = runCommand(List.of(
                    "ffmpeg",
                    "-err_detect", "ignore_err",
                    "-i", tsInput,
                    "-c", "copy",
                    mp4Output
            ));

            System.out.println("\nExited with code: " + exitCode);
            return exitCode == 0;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                System.out.println("Stream conversion interrupted: " + stream);
                Thread.currentThread().interrupt();
                return false;
            }
            throw e;
        }
    }

    public void cancel() {
        Process p = currentProcess;
        if (p != null) {
            System.out.println("Cancelling active process for " + stream);
            try {
                p.getInputStream().close();
            } catch (IOException ignored) {}
            try {
                p.getErrorStream().close();
            } catch (IOException ignored) {}
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {}
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void deleteTsFile() {
        try {
            Path tsPath = FilenameUtil.tsPath(Objects.requireNonNull(currentBasePath, "Output path not initialized"));
            boolean deleted = Files.deleteIfExists(tsPath);
            if (deleted) {
                System.out.println("Deleted TS file: " + tsPath);
            } else {
                System.out.println("TS file not found for deletion: " + tsPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete TS file: " + e.getMessage());
        }
    }
}
