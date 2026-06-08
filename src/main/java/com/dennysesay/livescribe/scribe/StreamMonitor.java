package com.dennysesay.livescribe.scribe;

import com.dennysesay.livescribe.config.AppConfig;
import com.dennysesay.livescribe.config.StreamerChannel;
import com.dennysesay.livescribe.provider.StreamingClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamMonitor {
    private final AppConfig appConfig;
    private ScheduledExecutorService checkerScheduler;
    private ExecutorService checkerExecutor;
    private ScheduledExecutorService uiScheduler;
    private final Map<StreamerChannel, StreamerStatus> statuses = new ConcurrentHashMap<>();
    private final Map<StreamerChannel, StreamRecorder> activeRecorders = new ConcurrentHashMap<>();
    private final Map<StreamerChannel, AtomicBoolean> activeRecordings = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean renderPaused = new AtomicBoolean(false);
    private ExecutorService downloadExecutor;
    private ControlServer controlServer;
    private Thread controlServerThread;
    private ScheduledFuture<?> checkerTask;
    private final Map<String, String> channelQualities = new ConcurrentHashMap<>();
    private final Map<String, String> channelCustomArgs = new ConcurrentHashMap<>();

    public StreamMonitor(AppConfig appConfig) {
        this.appConfig = appConfig;

        List<StreamerChannel> streamers = appConfig.getStreamers();
        for (StreamerChannel s : streamers) {
            StreamerStatus status = new StreamerStatus();
            status.setState(ChannelState.IDLE);
            statuses.put(s, status);
            activeRecordings.put(s, new AtomicBoolean(false));
        }
    }

    public void start() {
        stopping.set(false);

        // Clear terminal screen and move cursor to top-left on startup
        clearConsole();

        this.checkerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-checker-scheduler");
            t.setDaemon(true);
            return t;
        });

        List<StreamerChannel> streamers = appConfig.getStreamers();
        int poolSize = Math.max(1, streamers.size());
        this.checkerExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "live-checker-worker");
            t.setDaemon(true);
            return t;
        });

        int maxDownloads = Math.max(1, appConfig.getMaxConcurrentDownloads());
        final AtomicInteger workerCounter = new AtomicInteger(1);
        this.downloadExecutor = Executors.newFixedThreadPool(maxDownloads, r -> {
            Thread t = new Thread(r, "download-worker-" + workerCounter.getAndIncrement());
            t.setDaemon(false);
            return t;
        });

        this.uiScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ui-renderer");
            t.setDaemon(true);
            return t;
        });

        long checkIntervalSeconds = appConfig.getCheckIntervalSeconds();
        this.checkerTask = checkerScheduler.scheduleAtFixedRate(this::checkAllStreamers, 0, checkIntervalSeconds, TimeUnit.SECONDS);
        uiScheduler.scheduleAtFixedRate(this::render, 100, 1000, TimeUnit.MILLISECONDS);

        try {
            this.controlServer = new ControlServer(18080);
            this.controlServerThread = new Thread(controlServer, "control-server");
            this.controlServerThread.setDaemon(true);
            this.controlServerThread.start();
        } catch (IOException e) {
            // Log to log file or syserr. Since console is cleared regularly, this won't stay visible long.
            System.err.println("Warning: Failed to start control server on port 18080: " + e.getMessage());
        }
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        // Clear screen and print final stop messages
        clearConsole();
        System.out.println("Stopping Monitor...");

        if (controlServer != null) {
            controlServer.stop();
        }

        if (uiScheduler != null) {
            uiScheduler.shutdownNow();
            try {
                uiScheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (checkerScheduler != null) {
            checkerScheduler.shutdownNow();
            try {
                checkerScheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (checkerExecutor != null) {
            checkerExecutor.shutdownNow();
            try {
                checkerExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        for (StreamRecorder recorder : activeRecorders.values()) {
            try {
                recorder.cancel();
            } catch (Exception ignored) {}
        }

        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
            try {
                downloadExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("Monitor Stopped.");
    }

    private void checkAllStreamers() {
        if (stopping.get()) {
            return;
        }

        List<CompletableFuture<Void>> futures = statuses.keySet().stream()
                .map(streamerChannel -> CompletableFuture.runAsync(() -> {
                    try {
                        checkStreamer(streamerChannel);
                    } catch (Exception e) {
                        StreamerStatus status = statuses.get(streamerChannel);
                        if (status != null) {
                            status.setState(ChannelState.ERROR);
                        }
                    }
                }, checkerExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void checkStreamer(StreamerChannel streamerChannel) {
        if (stopping.get()) {
            return;
        }

        StreamerStatus status = statuses.get(streamerChannel);
        if (status == null) {
            return;
        }

        if (status.getState() == ChannelState.RECORDING || status.getState() == ChannelState.CONVERTING) {
            return;
        }

        status.setState(ChannelState.CHECKING);

        StreamingClient client = appConfig.getClient(streamerChannel.provider());
        if (client == null) {
            status.setState(ChannelState.ERROR);
            return;
        }

        boolean live;
        try {
            live = client.isLive(streamerChannel.channelName());
        } catch (Exception e) {
            status.setState(ChannelState.ERROR);
            return;
        }

        AtomicBoolean guard = activeRecordings.computeIfAbsent(streamerChannel, k -> new AtomicBoolean(false));
        if (live) {
            status.setState(ChannelState.LIVE);
            if (guard.compareAndSet(false, true)) {
                if (downloadExecutor == null || downloadExecutor.isShutdown()) {
                    guard.set(false);
                    status.setState(ChannelState.IDLE);
                    return;
                }
                downloadExecutor.submit(() -> {
                    try {
                        startDownload(streamerChannel, status);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        status.setState(ChannelState.ERROR);
                    } catch (Exception e) {
                        status.setState(ChannelState.ERROR);
                    } finally {
                        guard.set(false);
                    }
                });
            }
        } else {
            status.setState(ChannelState.OFFLINE);
        }
    }

    private void startDownload(StreamerChannel streamerChannel, StreamerStatus status) throws Exception {
        StreamingClient client = appConfig.getClient(streamerChannel.provider());
        if (client == null) {
            throw new IllegalStateException("No client available for provider " + streamerChannel.provider());
        }

        String quality = channelQualities.getOrDefault(streamerChannel.channelName().toLowerCase(), "best");
        String customArgs = channelCustomArgs.getOrDefault(streamerChannel.channelName().toLowerCase(), "");

        StreamRecorder recorder = new StreamRecorder(
                client,
                streamerChannel.channelName(),
                Path.of(streamerChannel.outputPath()),
                appConfig.isDeleteTsAfterConversionEnabled(),
                status,
                quality,
                customArgs
        );
        activeRecorders.put(streamerChannel, recorder);
        try {
            recorder.record();
        } finally {
            activeRecorders.remove(streamerChannel);
        }
    }

    private void render() {
        if (stopping.get() || renderPaused.get()) {
            return;
        }

        final String RESET = "\u001B[0m";
        final String BOLD = "\u001B[1m";
        final String GRAY = "\u001B[90m";
        final String GREEN = "\u001B[32m";
        final String YELLOW = "\u001B[33m";
        final String CYAN = "\u001B[36m";
        final String RED = "\u001B[31m";
        final String CLEAR_LINE = "\u001B[K";

        // Move cursor to top-left of the terminal (flicker-free)
        System.out.print("\u001B[H");

        System.out.println(BOLD + "=== Livescribe Stream Monitor ===" + RESET);
        System.out.println("Press Ctrl+C to stop.\n");

        List<StreamerChannel> streamers = statuses.keySet().stream()
                .sorted((a, b) -> a.channelName().compareToIgnoreCase(b.channelName()))
                .toList();

        for (StreamerChannel channel : streamers) {
            StreamerStatus status = statuses.get(channel);
            if (status == null) {
                continue;
            }
            ChannelState state = status.getState();

            // 1. Determine Color
            String color = switch (state) {
                case OFFLINE, IDLE -> GRAY;
                case CHECKING, LIVE -> YELLOW;
                case RECORDING -> GREEN;
                case PAUSED -> YELLOW;
                case CONVERTING, FINISHED -> CYAN;
                case ERROR -> RED;
            };

            // 2. Format Duration
            String durationStr = "-";
            if ((state == ChannelState.RECORDING || state == ChannelState.PAUSED) && status.getRecordStartTime() != null) {
                long seconds = Duration.between(status.getRecordStartTime(), Instant.now()).toSeconds();
                durationStr = String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
            }

            // 3. Format File Size
            String sizeStr = "-";
            if ((state == ChannelState.RECORDING || state == ChannelState.PAUSED || state == ChannelState.CONVERTING) && status.getActiveFilePath() != null) {
                try {
                    long bytes = Files.size(status.getActiveFilePath());
                    sizeStr = String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                } catch (Exception ignored) {
                    sizeStr = "0.00 MB";
                }
            }

            // Clear current line and print updated status
            System.out.print(CLEAR_LINE);
            System.out.printf("[%-20s] Status: %s%-15s%s | Duration: %-10s | Size: %-12s%n",
                    channel.channelName(),
                    color, state.getDisplayName(), RESET,
                    durationStr,
                    sizeStr
            );
        }
        System.out.flush();
    }

    private void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\u001B[2J\u001B[H");
                System.out.flush();
            }
        } catch (Exception e) {
            System.out.print("\u001B[2J\u001B[H");
            System.out.flush();
        }
    }

    private String handleAddCommand(String streamerDefinition) {
        String[] parts = streamerDefinition.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return "Error: Invalid streamer definition. Expected format: provider:channelName";
        }
        String provider = parts[0].trim().toLowerCase();
        String channelName = parts[1].trim();

        // Check if already monitored
        for (StreamerChannel channel : statuses.keySet()) {
            if (channel.provider().equalsIgnoreCase(provider) && channel.channelName().equalsIgnoreCase(channelName)) {
                return "Streamer " + streamerDefinition + " is already monitored.";
            }
        }

        // Dynamically add to appConfig properties
        String path = appConfig.getPropertyOrDefault("scribe.output.path." + provider,
                appConfig.getPropertyOrDefault("scribe.output.path", "~/livescribe")
        );
        if (path.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (path.equals("~")) {
                path = home;
            } else if (path.startsWith("~/") || path.startsWith("~\\")) {
                path = home + path.substring(1);
            }
        }

        StreamerChannel newChannel = new StreamerChannel(provider, channelName, path);
        StreamerStatus newStatus = new StreamerStatus();
        newStatus.setState(ChannelState.IDLE);
        statuses.put(newChannel, newStatus);
        activeRecordings.put(newChannel, new AtomicBoolean(false));

        // Persist to config files
        addStreamerToConfig(streamerDefinition);

        return "Successfully added and started monitoring " + streamerDefinition + ".";
    }

    private String handlePauseCommand(String inputChannel) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        for (Map.Entry<StreamerChannel, StreamRecorder> entry : activeRecorders.entrySet()) {
            if (entry.getKey().channelName().equalsIgnoreCase(target)) {
                StreamRecorder recorder = entry.getValue();
                if (recorder.isPaused()) {
                    return "Channel " + target + " is already paused.";
                }
                recorder.pause();
                return "Successfully paused " + target + ".";
            }
        }
        for (StreamerChannel channel : statuses.keySet()) {
            if (channel.channelName().equalsIgnoreCase(target)) {
                return "Channel " + target + " is not currently downloading.";
            }
        }
        return "Channel " + target + " is not monitored.";
    }

    private String handleResumeCommand(String inputChannel) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        for (Map.Entry<StreamerChannel, StreamRecorder> entry : activeRecorders.entrySet()) {
            if (entry.getKey().channelName().equalsIgnoreCase(target)) {
                StreamRecorder recorder = entry.getValue();
                if (!recorder.isPaused()) {
                    return "Channel " + target + " is not paused.";
                }
                recorder.resume();
                return "Successfully resumed " + target + ".";
            }
        }
        for (StreamerChannel channel : statuses.keySet()) {
            if (channel.channelName().equalsIgnoreCase(target)) {
                return "Channel " + target + " is not currently downloading.";
            }
        }
        return "Channel " + target + " is not monitored.";
    }

    private String handleStatusCommand() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Livescribe Status ===\n");
        List<StreamerChannel> sortedChannels = statuses.keySet().stream()
                .sorted((a, b) -> a.channelName().compareToIgnoreCase(b.channelName()))
                .toList();
        for (StreamerChannel channel : sortedChannels) {
            StreamerStatus status = statuses.get(channel);
            sb.append(String.format("[%s:%s] State: %s\n", channel.provider(), channel.channelName(), status.getState().getDisplayName()));
        }
        return sb.toString();
    }

    private String handleForceRecord(String inputChannel) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        for (StreamerChannel channel : statuses.keySet()) {
            if (channel.channelName().equalsIgnoreCase(target)) {
                StreamerStatus status = statuses.get(channel);
                if (status.getState() == ChannelState.RECORDING || status.getState() == ChannelState.CONVERTING) {
                    return "Channel " + target + " is already recording/converting.";
                }
                status.setState(ChannelState.LIVE);
                AtomicBoolean guard = activeRecordings.computeIfAbsent(channel, k -> new AtomicBoolean(false));
                if (guard.compareAndSet(false, true)) {
                    downloadExecutor.submit(() -> {
                        try {
                            startDownload(channel, status);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            status.setState(ChannelState.ERROR);
                        } catch (Exception e) {
                            status.setState(ChannelState.ERROR);
                        } finally {
                            guard.set(false);
                        }
                    });
                    return "Successfully force-started recording for " + target + ".";
                } else {
                    return "Record lock already acquired for " + target + ".";
                }
            }
        }
        return "Channel " + target + " is not monitored.";
    }

    private String handleStop(String inputChannel) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        for (Map.Entry<StreamerChannel, StreamRecorder> entry : activeRecorders.entrySet()) {
            if (entry.getKey().channelName().equalsIgnoreCase(target)) {
                StreamRecorder recorder = entry.getValue();
                recorder.cancel();
                return "Successfully cancelled/stopped recording for " + target + ".";
            }
        }
        return "Channel " + target + " is not currently downloading/recording.";
    }

    private String handleStateChange(String inputChannel, String stateName) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        try {
            ChannelState newState = ChannelState.valueOf(stateName.toUpperCase());
            for (StreamerChannel channel : statuses.keySet()) {
                if (channel.channelName().equalsIgnoreCase(target)) {
                    StreamerStatus status = statuses.get(channel);
                    status.setState(newState);
                    return "Successfully changed state of " + target + " to " + newState + ".";
                }
            }
            return "Channel " + target + " is not monitored.";
        } catch (IllegalArgumentException e) {
            return "Error: Invalid state '" + stateName + "'. Valid states: " + Arrays.toString(ChannelState.values());
        }
    }

    private String handleSet(String key, String value) {
        appConfig.setRuntimeOverride(key, value);
        setConfigProperty(key, value);
        if (key.equalsIgnoreCase("check.interval.seconds")) {
            try {
                int newInterval = Integer.parseInt(value);
                synchronized (this) {
                    if (checkerTask != null) {
                        checkerTask.cancel(false);
                    }
                    this.checkerTask = checkerScheduler.scheduleAtFixedRate(this::checkAllStreamers, newInterval, newInterval, TimeUnit.SECONDS);
                }
                return "Successfully set check.interval.seconds to " + newInterval + " and rescheduled monitor.";
            } catch (NumberFormatException e) {
                return "Error: check.interval.seconds must be an integer.";
            }
        }
        return "Successfully set " + key + " to " + value + " in memory and configuration.";
    }

    private String handleGet(String key) {
        String val = appConfig.getProperty(key);
        if (val == null) {
            return "Key '" + key + "' is not set.";
        }
        return key + "=" + val;
    }

    private String handleQuality(String inputChannel, String quality) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        channelQualities.put(target.toLowerCase(), quality);
        return "Quality for " + target + " set to " + quality + ".";
    }

    private String handleArgs(String inputChannel, String args) {
        String target = inputChannel.contains(":") ? inputChannel.split(":", 2)[1].trim() : inputChannel.trim();
        channelCustomArgs.put(target.toLowerCase(), args);
        return "Custom args for " + target + " set to: " + args;
    }

    public static void setConfigProperty(String key, String value) {
        File[] configFiles = {
                new File("config.properties"),
                new File("src/main/resources/config.properties")
        };
        for (File configFile : configFiles) {
            if (configFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(configFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                    boolean updated = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        if (line.startsWith(key + "=")) {
                            lines.set(i, key + "=" + value);
                            updated = true;
                            break;
                        }
                    }
                    if (!updated) {
                        lines.add(key + "=" + value);
                    }
                    Files.write(configFile.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    System.err.println("Failed to update config property " + key + " in " + configFile.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
    }

    public static void addStreamerToConfig(String streamer) {
        File[] configFiles = {
                new File("config.properties"),
                new File("src/main/resources/config.properties")
        };

        for (File configFile : configFiles) {
            // If it's src/main/resources/config.properties and it doesn't exist, we don't need to force create it.
            // If config.properties doesn't exist in the current working dir, we can copy config.example.properties
            if (configFile.getName().equals("config.properties") && !configFile.exists()) {
                File exampleFile = new File("config.example.properties");
                if (exampleFile.exists()) {
                    try {
                        Files.copy(exampleFile.toPath(), configFile.toPath());
                    } catch (IOException ignored) {}
                }
            }

            if (configFile.exists()) {
                try {
                    List<String> lines = Files.readAllLines(configFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                    boolean updated = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i).trim();
                        if (line.startsWith("streamers=")) {
                            String existingStreamers = line.substring("streamers=".length()).trim();
                            if (existingStreamers.isEmpty()) {
                                lines.set(i, "streamers=" + streamer);
                            } else {
                                boolean alreadyExists = Arrays.stream(existingStreamers.split(","))
                                        .map(String::trim)
                                        .anyMatch(s -> s.equalsIgnoreCase(streamer));
                                if (!alreadyExists) {
                                    lines.set(i, "streamers=" + existingStreamers + ", " + streamer);
                                }
                            }
                            updated = true;
                            break;
                        }
                    }
                    if (!updated) {
                        lines.add("streamers=" + streamer);
                    }
                    Files.write(configFile.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    System.err.println("Failed to update " + configFile.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
    }

    public void pauseUIRendering() {
        renderPaused.set(true);
        clearConsole();
    }

    public void resumeUIRendering() {
        renderPaused.set(false);
        clearConsole();
    }

    public String executeCommand(String line) {
        if (line == null || line.isBlank()) {
            return "Error: Empty command";
        }

        String[] parts = line.split(" ", 2);
        String command = parts[0].trim().toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";

        return switch (command) {
            case "add" -> {
                if (argument.isEmpty()) {
                    yield "Error: streamer definition required (e.g. twitch:channelName, chzzk:channelName)";
                } else {
                    yield handleAddCommand(argument);
                }
            }
            case "pause" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name required";
                } else {
                    yield handlePauseCommand(argument);
                }
            }
            case "resume" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name required";
                } else {
                    yield handleResumeCommand(argument);
                }
            }
            case "status" -> handleStatusCommand();
            case "set" -> {
                if (argument.isEmpty()) {
                    yield "Error: key and value required (e.g. set check.interval.seconds 15)";
                } else {
                    String[] parts2 = argument.split(" ", 2);
                    if (parts2.length < 2) {
                        yield "Error: value required";
                    } else {
                        yield handleSet(parts2[0].trim(), parts2[1].trim());
                    }
                }
            }
            case "get" -> {
                if (argument.isEmpty()) {
                    yield "Error: key required (e.g. get check.interval.seconds)";
                } else {
                    yield handleGet(argument);
                }
            }
            case "force-record" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name required";
                } else {
                    yield handleForceRecord(argument);
                }
            }
            case "stop" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name required";
                } else {
                    yield handleStop(argument);
                }
            }
            case "state" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name and state required (e.g. state dougdoug ERROR)";
                } else {
                    String[] parts2 = argument.split(" ", 2);
                    if (parts2.length < 2) {
                        yield "Error: state required";
                    } else {
                        yield handleStateChange(parts2[0].trim(), parts2[1].trim());
                    }
                }
            }
            case "quality" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name and quality required (e.g. quality dougdoug 720p60)";
                } else {
                    String[] parts2 = argument.split(" ", 2);
                    if (parts2.length < 2) {
                        yield "Error: quality required";
                    } else {
                        yield handleQuality(parts2[0].trim(), parts2[1].trim());
                    }
                }
            }
            case "args" -> {
                if (argument.isEmpty()) {
                    yield "Error: channel name and custom arguments required (e.g. args dougdoug --twitch-low-latency)";
                } else {
                    String[] parts2 = argument.split(" ", 2);
                    if (parts2.length < 2) {
                        yield "Error: custom arguments required";
                    } else {
                        yield handleArgs(parts2[0].trim(), parts2[1].trim());
                    }
                }
            }
            default -> "Unknown command: " + command;
        };
    }

    private class ControlServer implements Runnable {
        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);

        public ControlServer(int port) throws IOException {
            this.serverSocket = new ServerSocket(port);
        }

        public void stop() {
            running.set(false);
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        @Override
        public void run() {
            while (running.get()) {
                try (Socket socket = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8), true)) {

                    String line = reader.readLine();
                    if (line == null) continue;

                    String result = executeCommand(line.trim());
                    writer.println(result);
                } catch (IOException e) {
                    if (!running.get()) {
                        break;
                    }
                }
            }
        }
    }
}
