package com.dennysesay.scribe;

import com.dennysesay.config.StreamerConfig;
import com.dennysesay.config.StreamerDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamMonitor {
    private final StreamerConfig streamerConfig;
    private ScheduledExecutorService checker;
    private final Map<StreamerDefinition, AtomicBoolean> downloadGuards = new ConcurrentHashMap<>();
    private ExecutorService downloadExecutor;
    private final Duration checkInterval = Duration.ofSeconds(30);

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

        this.downloadExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "download-worker");
            t.setDaemon(false);
            return t;
        });

        checker.scheduleAtFixedRate(this::checkAllStreamers, 0, checkInterval.toSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        System.out.println("Stopping Monitor");
        if (checker != null) {
            checker.shutdownNow();
            try {
                checker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }

        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
            try {
                downloadExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
        }
        System.out.println("Monitor Stopped");
    }

    private void checkAllStreamers() {
    }
}
