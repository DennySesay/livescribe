package com.dennysesay.livescribe;

import com.dennysesay.livescribe.config.AppConfig;
import com.dennysesay.livescribe.scribe.StreamMonitor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class LiveScribeApp {
    public static void main(String[] args) throws IOException {
        AppConfig config = new AppConfig();
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
