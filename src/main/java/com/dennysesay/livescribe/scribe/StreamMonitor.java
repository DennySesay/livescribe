package com.dennysesay.livescribe.scribe;

import com.dennysesay.livescribe.config.AppConfig;
import com.dennysesay.livescribe.config.StreamerChannel;
import com.dennysesay.livescribe.provider.StreamingClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
    private ExecutorService downloadExecutor;

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
        checkerScheduler.scheduleAtFixedRate(this::checkAllStreamers, 0, checkIntervalSeconds, TimeUnit.SECONDS);
        uiScheduler.scheduleAtFixedRate(this::render, 100, 1000, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        // Clear screen and print final stop messages
        clearConsole();
        System.out.println("Stopping Monitor...");

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

        List<StreamerChannel> streamers = appConfig.getStreamers();
        List<CompletableFuture<Void>> futures = streamers.stream()
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

        StreamRecorder recorder = new StreamRecorder(
                client,
                streamerChannel.channelName(),
                Path.of(streamerChannel.outputPath()),
                appConfig.isDeleteTsAfterConversionEnabled(),
                status
        );
        activeRecorders.put(streamerChannel, recorder);
        try {
            recorder.record();
        } finally {
            activeRecorders.remove(streamerChannel);
        }
    }

    private void render() {
        if (stopping.get()) {
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

        List<StreamerChannel> streamers = appConfig.getStreamers();
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
                case CONVERTING, FINISHED -> CYAN;
                case ERROR -> RED;
            };

            // 2. Format Duration
            String durationStr = "-";
            if (state == ChannelState.RECORDING && status.getRecordStartTime() != null) {
                long seconds = Duration.between(status.getRecordStartTime(), Instant.now()).toSeconds();
                durationStr = String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
            }

            // 3. Format File Size
            String sizeStr = "-";
            if ((state == ChannelState.RECORDING || state == ChannelState.CONVERTING) && status.getActiveFilePath() != null) {
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
}
