package com.dennysesay.livescribe.config;

import com.dennysesay.livescribe.provider.StreamingClient;
import com.dennysesay.livescribe.provider.twitch.TwitchClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AppConfig {
    private final AppConfigReader configReader;
    private final List<StreamerChannel> streamers;
    private final Map<String, StreamingClient> clientsByProvider;

    public AppConfig() {
        this.configReader = new AppConfigReader();
        this.streamers = parseStreamers();
        this.clientsByProvider = initializeClients();
    }

    public int getCheckIntervalSeconds() {
        String configValue = configReader.getOrDefault("check.interval.seconds", "30");
        try {
            return Integer.parseInt(configValue);
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    public int getMaxConcurrentDownloads() {
        String configValue = configReader.get("downloads.max.concurrent");
        if (configValue == null || configValue.isBlank()) {
            return Math.max(1, streamers.size());
        }
        try {
            return Integer.parseInt(configValue);
        } catch (NumberFormatException e) {
            return Math.max(1, streamers.size());
        }
    }

    public boolean isDeleteTsAfterConversionEnabled() {
        String configValue = configReader.get("scribe.delete-ts");

        if (configValue == null || configValue.isBlank()) {
            return true;
        }
        try {
            return Boolean.parseBoolean(configValue);
        } catch (Exception e) {
            return false;
        }
    }

    public List<StreamerChannel> getStreamers() {
        return streamers;
    }

    public StreamingClient getClient(String provider) {
        return clientsByProvider.get(provider);
    }

    private List<StreamerChannel> parseStreamers() {
        String streamersValue = configReader.get("streamers");
        if (streamersValue == null || streamersValue.isBlank()) {
            throw new IllegalStateException("Missing or empty 'streamers' configuration");
        }

        return Arrays.stream(streamersValue.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .map(streamer -> {
                    String[] parts = streamer.split(":", 2);
                    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                        throw new IllegalStateException(
                                "Invalid streamer definition: '" + streamer + "'. Expected format: provider:channel"
                        );
                    }

                    String provider = parts[0].trim();
                    String channel = parts[1].trim();
                    String path = configReader.getOrDefault(
                            "scribe.output.path." + provider,
                            configReader.getOrDefault("scribe.output.path", "~/livescribe")
                    );
                    if (path.startsWith("~")) {
                        String home = System.getProperty("user.home");
                        if (path.equals("~")) {
                            path = home;
                        } else if (path.startsWith("~/") || path.startsWith("~\\")) {
                            path = home + path.substring(1);
                        }
                    }
                    return new StreamerChannel(provider, channel, path);
                })
                .toList();
    }

    private Map<String, StreamingClient> initializeClients() {
        return streamers.stream()
                .map(StreamerChannel::provider)
                .distinct()
                .collect(Collectors.toMap(
                        provider -> provider,
                        provider -> switch (provider) {
                            case "twitch" -> new TwitchClient(
                                    SecretsManager.getTwitchClientId(configReader),
                                    SecretsManager.getTwitchClientSecret(configReader)
                            );
                            default -> throw new IllegalArgumentException("Unexpected value: " + provider);
                        }
                ));
    }
}
