package com.dennysesay.scribe;

import com.dennysesay.config.StreamerConfig;
import com.dennysesay.config.StreamerDefinition;
import com.dennysesay.provider.StreamingClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamMonitor {
    private final StreamerConfig streamerConfig;
    private ScheduledExecutorService checker;
    private final Map<StreamerDefinition, AtomicBoolean> downloadGuards = new ConcurrentHashMap<>();
    private final Map<StreamerDefinition, StreamlinkResolver> activeResolvers = new ConcurrentHashMap<>();
    private ExecutorService downloadExecutor;

    public StreamMonitor(StreamerConfig streamerConfig) {
        this.streamerConfig = streamerConfig;

        List<StreamerDefinition> streamers = streamerConfig.getStreamers();
        for (StreamerDefinition s : streamers) {
            downloadGuards.put(s, new AtomicBoolean(false));
        }
    }

    public void start() {
        this.checker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-checker");
            t.setDaemon(false);
            return t;
        });

        int maxDownloads = Math.max(1, streamerConfig.getMaxConcurrentDownloads());
        this.downloadExecutor = Executors.newFixedThreadPool(maxDownloads, r -> {
            Thread t = new Thread(r, "download-worker");
            t.setDaemon(false);
            return t;
        });

        long checkIntervalSeconds = streamerConfig.getCheckIntervalSeconds();
        checker.scheduleAtFixedRate(this::checkAllStreamers, 0, checkIntervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        System.out.println("Stopping Monitor");
        for (StreamlinkResolver resolver : activeResolvers.values()) {
            try {
                resolver.cancel();
            } catch (Exception e) {
                System.err.println("Failed to cancel resolver: " + e.getMessage());
            }
        }

        if (checker != null) {
            checker.shutdownNow();
            try {
                checker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
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
        List<StreamerDefinition> streamers = streamerConfig.getStreamers();
        for (StreamerDefinition streamerDefinition : streamers) {
            try {
                checkStreamer(streamerDefinition);
            } catch (Exception e) {
                System.err.println("Error checking " + streamerDefinition + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void checkStreamer(StreamerDefinition streamerDefinition) {
        StreamingClient client = streamerConfig.getClient(streamerDefinition.provider());
        if (client == null) {
            System.out.println("No client for provider " + streamerDefinition.provider() + " (skip " + streamerDefinition + ")");
            return;
        }

        boolean live;
        try {
            live = client.isLive(streamerDefinition.streamer());
        } catch (Exception e) {
            System.err.println("Live check failed for " + streamerDefinition + ": " + e.getMessage());
            return;
        }

        AtomicBoolean guard = downloadGuards.computeIfAbsent(streamerDefinition, k -> new AtomicBoolean(false));
        if (live) {
            System.out.println(streamerDefinition + " is live");
            if (guard.compareAndSet(false, true)) {
                downloadExecutor.submit(() -> {
                    try {
                        startDownload(streamerDefinition);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.err.println("Download interrupted for " + streamerDefinition);
                    } catch (Exception e) {
                        System.err.println("Download error for " + streamerDefinition + ": " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        guard.set(false);
                        System.out.println("Download finished for " + streamerDefinition);
                    }
                });
            } else {
                System.out.println("Download already in progress for " + streamerDefinition);
            }
        } else {
            System.out.println(streamerDefinition + " is not live");
        }
    }

    private void startDownload(StreamerDefinition streamerDefinition) throws Exception {
        StreamingClient client = streamerConfig.getClient(streamerDefinition.provider());
        if (client == null) {
            throw new IllegalStateException("No client available for provider " + streamerDefinition.provider());
        }

        String filename = streamerDefinition.streamer();
        StreamlinkResolver resolver = new StreamlinkResolver(
                client,
                streamerDefinition.streamer(),
                filename,
                FilenameUtil.defaultBaseDir()
        );
        activeResolvers.put(streamerDefinition, resolver);
        System.out.println("Starting resolver for " + streamerDefinition);
        try {
            resolver.resolve();
        } finally {
            activeResolvers.remove(streamerDefinition);
        }
    }
}
