package com.dennysesay.livescribe.scribe;

import com.dennysesay.livescribe.provider.StreamingClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamRecorder {
    private final StreamingClient platformClient;
    private final String channelName;
    private final Path outputDir;
    private final boolean deleteTsAfterConversion;
    private final StreamerStatus status;
    private volatile Process activeProcess;
    private Path recordingBasePath;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final String quality;
    private final String customArgs;

    public void pause() {
        if (paused.compareAndSet(false, true)) {
            logMessage("Recording paused for " + channelName);
            status.setState(ChannelState.PAUSED);
        }
    }

    public void resume() {
        if (paused.compareAndSet(true, false)) {
            logMessage("Recording resumed for " + channelName);
            status.setState(ChannelState.RECORDING);
            synchronized (paused) {
                paused.notifyAll();
            }
        }
    }

    public boolean isPaused() {
        return paused.get();
    }

    public StreamRecorder(StreamingClient platformClient, String channelName, Path outputDir, boolean deleteTsAfterConversion, StreamerStatus status) {
        this(platformClient, channelName, outputDir, deleteTsAfterConversion, status, "best", "");
    }

    public StreamRecorder(StreamingClient platformClient, String channelName, Path outputDir, boolean deleteTsAfterConversion, StreamerStatus status, String quality, String customArgs) {
        this.platformClient = Objects.requireNonNull(platformClient, "platformClient must not be null");
        this.channelName = Objects.requireNonNull(channelName, "channelName must not be null");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir must not be null");
        this.deleteTsAfterConversion = deleteTsAfterConversion;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.quality = quality != null ? quality : "best";
        this.customArgs = customArgs != null ? customArgs : "";
    }

    private Path getLogPath() {
        try {
            Path logsDir = Path.of("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }
            return logsDir.resolve(channelName + ".log");
        } catch (IOException e) {
            return Path.of(channelName + ".log");
        }
    }

    private void logMessage(String message) {
        try {
            Path logFile = getLogPath();
            Files.writeString(logFile, "[" + Instant.now() + "] " + message + "\n", 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private int runCommand(List<String> command) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        
        Path logFile = getLogPath();
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        try {
            Files.writeString(logFile, "\n--- Starting command at " + Instant.now() + " ---\nCommand: " + String.join(" ", command) + "\n", 
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}

        try {
            Process process = processBuilder.start();
            activeProcess = process;

            int exitCode = process.waitFor();
            
            try {
                Files.writeString(logFile, "--- Command exited with code: " + exitCode + " ---\n", 
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {}

            return exitCode;
        } catch (InterruptedException e) {
            Process p = activeProcess;
            if (p != null && p.isAlive()) {
                logMessage("Terminating subprocess forcibly: " + String.join(" ", command));
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
            Path tsPath = RecordingPathResolver.resolveTsPath(recordingBasePath);

            status.setState(ChannelState.RECORDING);
            status.setRecordStartTime(Instant.now());
            status.setActiveFilePath(tsPath);

            logMessage("Starting download for " + channelName);

            // We use "-" for streamlink to output to stdout
            List<String> command = new java.util.ArrayList<>();
            command.add("streamlink");
            command.add(platformClient.createUrl(channelName));
            command.add(quality);
            command.add("-o");
            command.add("-");
            if (customArgs != null && !customArgs.isBlank()) {
                String[] parts = customArgs.trim().split("\\s+");
                for (String part : parts) {
                    if (!part.isEmpty()) {
                        command.add(part);
                    }
                }
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            // Redirect stderr to log file so streamlink logs are saved
            Path logFile = getLogPath();
            processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            try {
                Files.writeString(logFile, "\n--- Starting streamlink at " + Instant.now() + " ---\nCommand: " + String.join(" ", command) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {}

            int exitCode = -1;
            Process process = null;
            try {
                // Ensure output directory exists
                Files.createDirectories(tsPath.getParent());

                process = processBuilder.start();
                activeProcess = process;

                // Read from process.getInputStream() and write to tsPath
                try (var inputStream = process.getInputStream();
                     var outputStream = Files.newOutputStream(tsPath)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        // Check if paused
                        while (paused.get()) {
                            synchronized (paused) {
                                paused.wait(500);
                            }
                            // Check if process has exited while we were paused
                            if (!process.isAlive() && inputStream.available() == 0) {
                                break;
                            }
                        }
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }

                exitCode = process.waitFor();

                try {
                    Files.writeString(logFile, "--- Streamlink exited with code: " + exitCode + " ---\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException ignored) {}

            } catch (InterruptedException e) {
                if (process != null && process.isAlive()) {
                    logMessage("Terminating streamlink forcibly: " + channelName);
                    process.destroyForcibly();
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException("Streamlink process was interrupted", e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to download stream: " + channelName, e);
            } finally {
                activeProcess = null;
            }

            if (exitCode == 0) {
                status.setState(ChannelState.CONVERTING);
                Path mp4Path = RecordingPathResolver.resolveMp4Path(recordingBasePath);
                status.setActiveFilePath(mp4Path);

                logMessage("Converting download for " + channelName + " to MP4");
                boolean converted = convertToMp4();

                if (converted) {
                    status.setState(ChannelState.FINISHED);
                    logMessage("Successfully finished and converted recording for " + channelName);
                    if (deleteTsAfterConversion) {
                        deleteTsFile();
                    }
                } else {
                    status.setState(ChannelState.ERROR);
                    logMessage("Failed to convert TS to MP4 for " + channelName);
                }
            } else {
                status.setState(ChannelState.ERROR);
                logMessage("Streamlink exited with code " + exitCode + " for " + channelName);
            }
        } catch (RuntimeException e) {
            status.setState(ChannelState.ERROR);
            if (e.getCause() instanceof InterruptedException) {
                logMessage("Stream download interrupted: " + channelName);
                Thread.currentThread().interrupt();
                return;
            }
            logMessage("Runtime error during recording for " + channelName + ": " + e.getMessage());
            throw e;
        } catch (IOException e) {
            status.setState(ChannelState.ERROR);
            logMessage("IO error preparing output path for " + channelName + ": " + e.getMessage());
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

            logMessage("Conversion exited with code: " + exitCode);
            return exitCode == 0;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedException) {
                logMessage("Stream conversion interrupted: " + channelName);
                Thread.currentThread().interrupt();
                return false;
            }
            throw e;
        }
    }

    public void cancel() {
        Process p = activeProcess;
        if (p != null) {
            logMessage("Cancelling active process for " + channelName);
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
                logMessage("Deleted TS file: " + tsPath);
            } else {
                logMessage("TS file not found for deletion: " + tsPath);
            }
        } catch (IOException e) {
            logMessage("Failed to delete TS file: " + e.getMessage());
        }
    }
}
