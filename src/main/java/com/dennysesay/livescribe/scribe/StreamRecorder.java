package com.dennysesay.livescribe.scribe;

import com.dennysesay.livescribe.provider.StreamingClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class StreamRecorder {
    private final StreamingClient platformClient;
    private final String channelName;
    private final Path outputDir;
    private final boolean deleteTsAfterConversion;
    private volatile Process activeProcess;
    private Path recordingBasePath;

    public StreamRecorder(StreamingClient platformClient, String channelName, Path outputDir, boolean deleteTsAfterConversion) {
        this.platformClient = Objects.requireNonNull(platformClient, "platformClient must not be null");
        this.channelName = Objects.requireNonNull(channelName, "channelName must not be null");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir must not be null");
        this.deleteTsAfterConversion = deleteTsAfterConversion;
    }

    private int runCommand(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            activeProcess = process;

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
            Process p = activeProcess;
            if (p != null && p.isAlive()) {
                System.out.println("Terminating subprocess: " + String.join(" ", command));
                p.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new RuntimeException("Process was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process: " + String.join(" ", command), e);
        } finally {
            activeProcess = null;
        }
    }

    public void record() {
        try {
            Instant ts = Instant.now();
            this.recordingBasePath = RecordingPathResolver.resolveRecordingPath(outputDir, channelName, ts);
            String tsOutput = RecordingPathResolver.resolveTsPath(recordingBasePath).toString();

            int exitCode = runCommand(List.of(
                    "streamlink",
                    platformClient.createUrl(channelName),
                    "best",
                    "-o",
                    tsOutput
            ));
            if (exitCode == 0) {
                boolean converted = convertToMp4();
                if (converted && deleteTsAfterConversion) {
                    deleteTsFile();
                }
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                System.out.println("Stream download interrupted: " + channelName);
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
            String tsInput = RecordingPathResolver.resolveTsPath(Objects.requireNonNull(recordingBasePath, "Output path not initialized")).toString();
            String mp4Output = RecordingPathResolver.resolveMp4Path(recordingBasePath).toString();

            int exitCode = runCommand(List.of(
                    "ffmpeg",
                    "-err_detect", "ignore_err",
                    "-i", tsInput,
                    "-c", "copy",
                    mp4Output
            ));

            System.out.println("\nConversion exited with code: " + exitCode);
            return exitCode == 0;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                System.out.println("Stream conversion interrupted: " + channelName);
                Thread.currentThread().interrupt();
                return false;
            }
            throw e;
        }
    }

    public void cancel() {
        Process p = activeProcess;
        if (p != null) {
            System.out.println("Cancelling active process for " + channelName);
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
            Path tsPath = RecordingPathResolver.resolveTsPath(Objects.requireNonNull(recordingBasePath, "Output path not initialized"));
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
