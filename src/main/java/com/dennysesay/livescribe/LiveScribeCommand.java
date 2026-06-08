package com.dennysesay.livescribe;

import com.dennysesay.livescribe.config.AppConfig;
import com.dennysesay.livescribe.scribe.StreamMonitor;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "livescribe", mixinStandardHelpOptions = true, version = "1.0",
        description = "LiveScribe - Monitors and records live streams.",
        subcommands = {
                LiveScribeCommand.MonitorCommand.class,
                LiveScribeCommand.AddCommand.class,
                LiveScribeCommand.PauseCommand.class,
                LiveScribeCommand.ResumeCommand.class,
                LiveScribeCommand.StatusCommand.class,
                LiveScribeCommand.SetCommand.class,
                LiveScribeCommand.GetCommand.class,
                LiveScribeCommand.ForceRecordCommand.class,
                LiveScribeCommand.StopCommand.class,
                LiveScribeCommand.StateCommand.class,
                LiveScribeCommand.QualityCommand.class,
                LiveScribeCommand.ArgsCommand.class
        })
public class LiveScribeCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        // If no subcommand is specified, default to running the monitor
        return new MonitorCommand().call();
    }

    private static String sendCommand(String cmdLine) {
        try (Socket socket = new Socket("localhost", 18080);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            writer.println(cmdLine);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null; // Server not running
        }
    }

    @Command(name = "monitor", description = "Start the stream monitor daemon.")
    public static class MonitorCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
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
            return 0;
        }
    }

    @Command(name = "add", description = "Add a stream to monitor (e.g. twitch:channelName, naver:channelName).")
    public static class AddCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The streamer definition in provider:channel format.")
        private String streamer;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("add " + streamer);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Monitor is not running. Adding streamer to configuration files directly...");
                StreamMonitor.addStreamerToConfig(streamer);
                System.out.println("Successfully added " + streamer + " to configuration.");
            }
            return 0;
        }
    }

    @Command(name = "pause", description = "Pause downloading for a specific channel.")
    public static class PauseCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name to pause.")
        private String channel;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("pause " + channel);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running. Please start it using 'livescribe monitor'.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "resume", description = "Resume downloading for a paused channel.")
    public static class ResumeCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name to resume.")
        private String channel;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("resume " + channel);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running. Please start it using 'livescribe monitor'.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "status", description = "Show current status of all monitored streams.")
    public static class StatusCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            String response = sendCommand("status");
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running. Please start it using 'livescribe monitor'.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "set", description = "Set a configuration property dynamically (e.g. set check.interval.seconds 15).")
    public static class SetCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The property key.")
        private String key;

        @Parameters(index = "1", description = "The property value.")
        private String value;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("set " + key + " " + value);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Monitor is not running. Setting config property directly in file...");
                StreamMonitor.setConfigProperty(key, value);
                System.out.println("Successfully set " + key + "=" + value + " in configuration.");
            }
            return 0;
        }
    }

    @Command(name = "get", description = "Get a configuration property value.")
    public static class GetCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The property key.")
        private String key;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("get " + key);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running. Start it first to get active values.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "force-record", description = "Force start a recording for a channel immediately.")
    public static class ForceRecordCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name.")
        private String channel;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("force-record " + channel);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "stop", description = "Stop/cancel active recording for a channel.")
    public static class StopCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name.")
        private String channel;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("stop " + channel);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "state", description = "Force override the state of a channel manually.")
    public static class StateCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name.")
        private String channel;

        @Parameters(index = "1", description = "The new state (e.g. ERROR, IDLE).")
        private String state;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("state " + channel + " " + state);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "quality", description = "Set a custom recording quality for a channel (e.g. quality dougdoug 720p60).")
    public static class QualityCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name.")
        private String channel;

        @Parameters(index = "1", description = "The stream quality.")
        private String quality;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("quality " + channel + " " + quality);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running. Start it to override quality.");
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "args", description = "Set custom streamlink options for a channel.")
    public static class ArgsCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "The channel name.")
        private String channel;

        @Parameters(index = "1", description = "The custom arguments.")
        private String args;

        @Override
        public Integer call() throws Exception {
            String response = sendCommand("args " + channel + " " + args);
            if (response != null) {
                System.out.println(response);
            } else {
                System.out.println("Error: Livescribe Monitor is not running.");
                return 1;
            }
            return 0;
        }
    }
}
