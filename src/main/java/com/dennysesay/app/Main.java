package com.dennysesay.app;

import com.dennysesay.config.StreamerConfig;
import com.dennysesay.scribe.StreamMonitor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class Main {
    public static void main(String[] args) throws IOException {

        StreamerConfig config = new StreamerConfig();
        StreamMonitor monitor = new StreamMonitor(config);

        monitor.start();

        final var keepAlive = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown requested, stopping monitor");
            monitor.stop();
            keepAlive.countDown();
        }, "shutdown-hook"));

        try {
            keepAlive.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
