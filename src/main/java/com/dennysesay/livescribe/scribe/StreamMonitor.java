package com.dennysesay.livescribe.scribe;

import com.dennysesay.livescribe.config.AppConfig;
import com.dennysesay.livescribe.config.StreamerChannel;
import com.dennysesay.livescribe.provider.StreamingClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamMonitor {
    private final AppConfig appConfig;
    private ScheduledExecutorService checker;
    private final Map<StreamerChannel, AtomicBoolean> activeRecordings = new ConcurrentHashMap<>();
    private final Map<StreamerChannel, StreamRecorder> activeRecorders = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private ExecutorService downloadExecutor;

    public StreamMonitor(AppConfig appConfig) {
        this.appConfig = appConfig;

        List<StreamerChannel> streamers = appConfig.getStreamers();
        for (StreamerChannel s : streamers) {
            activeRecordings.put(s, new AtomicBoolean(false));
        }
    }

    public void start() {
        stopping.set(false);

        this.checker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-checker");
            t.setDaemon(false);
            return t;
        });

        int maxDownloads = Math.max(1, appConfig.getMaxConcurrentDownloads());
        final AtomicInteger workerCounter = new AtomicInteger(1);
        this.downloadExecutor = Executors.newFixedThreadPool(maxDownloads, r -> {
            Thread t = new Thread(r, "download-worker-" + workerCounter.getAndIncrement());
            t.setDaemon(false);
            return t;
        });

        long checkIntervalSeconds = appConfig.getCheckIntervalSeconds();
        checker.scheduleAtFixedRate(this::checkAllStreamers, 0, checkIntervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            System.out.println("Stop already in progress");
            return;
        }
        System.out.println("Stopping Monitor");

        if (checker != null) {
            checker.shutdownNow();
            try {
                checker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        for (StreamRecorder recorder : activeRecorders.values()) {
            try {
                recorder.cancel();
            } catch (Exception e) {
                System.err.println("Failed to cancel recorder: " + e.getMessage());
            }
        }

        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
            try {
                downloadExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("Monitor Stopped");
    }

    private void checkAllStreamers() {
        if (stopping.get()) {
            return;
        }
        List<StreamerChannel> streamers = appConfig.getStreamers();
        for (StreamerChannel streamerChannel : streamers) {
            try {
                checkStreamer(streamerChannel);
            } catch (Exception e) {
                System.err.println("Error checking " + streamerChannel + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void checkStreamer(StreamerChannel streamerChannel) {
        if (stopping.get()) {
            return;
        }
        StreamingClient client = appConfig.getClient(streamerChannel.provider());
        if (client == null) {
            System.out.println("No client for provider " + streamerChannel.provider() + " (skip " + streamerChannel + ")");
            return;
        }

        boolean live;
        try {
            live = client.isLive(streamerChannel.channelName());
        } catch (Exception e) {
            System.err.println("Live check failed for " + streamerChannel + ": " + e.getMessage());
            return;
        }

        AtomicBoolean guard = activeRecordings.computeIfAbsent(streamerChannel, k -> new AtomicBoolean(false));
        if (live) {
            System.out.println(streamerChannel + " is live");
            if (guard.compareAndSet(false, true)) {
                if (downloadExecutor == null || downloadExecutor.isShutdown()) {
                    System.out.println("Skipping start; monitor is stopping for " + streamerChannel);
                    guard.set(false);
                    return;
                }
                downloadExecutor.submit(() -> {
                    try {
                        startDownload(streamerChannel);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("Download interrupted for " + streamerChannel);
                    } catch (Exception e) {
                        System.err.println("Download error for " + streamerChannel + ": " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        guard.set(false);
                        System.out.println("Download finished for " + streamerChannel);
                    }
                });
            } else {
                System.out.println("Download already in progress for " + streamerChannel);
            }
        } else {
            // Log already handled at the provider level for offline status
        }
    }

    private void startDownload(StreamerChannel streamerChannel) throws Exception {
        StreamingClient client = appConfig.getClient(streamerChannel.provider());
        if (client == null) {
            throw new IllegalStateException("No client available for provider " + streamerChannel.provider());
        }

        StreamRecorder recorder = new StreamRecorder(
                client,
                streamerChannel.channelName(),
                Path.of(streamerChannel.outputPath()),
                appConfig.isDeleteTsAfterConversionEnabled()
        );
        activeRecorders.put(streamerChannel, recorder);
        System.out.println("Starting recorder for " + streamerChannel);
        try {
            recorder.record();
        } finally {
            activeRecorders.remove(streamerChannel);
        }
    }
}
