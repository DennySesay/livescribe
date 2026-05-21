package com.dennysesay.config;

import com.dennysesay.provider.StreamingClient;
import com.dennysesay.provider.twitch.TwitchClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StreamerConfig {
    private final ConfigReader configReader;
    private final List<StreamerDefinition> streamers;
    private final Map<String, StreamingClient> clientsByProvider;

    public StreamerConfig() {
        this.configReader = new ConfigReader();
        this.streamers = parseStreamers();
        this.clientsByProvider = initializeClients();
    }

    public int getCheckIntervalSeconds() {
        String reader = configReader.getOrDefault("check.interval.seconds", "30");
        try {
            return Integer.parseInt(reader);
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    public int getMaxConcurrentDownloads() {
        String reader = configReader.get("downloads.max.concurrent");
        if (reader == null || reader.isBlank()) {
            return Math.max(1, streamers.size());
        }
        try {
            return Integer.parseInt(reader);
        } catch (NumberFormatException e) {
            return Math.max(1, streamers.size());
        }
    }

    public List<StreamerDefinition> getStreamers() {
        return streamers;
    }

    public StreamingClient getClient(String provider) {
        return clientsByProvider.get(provider);
    }

    public List<StreamerDefinition> parseStreamers() {
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
                            configReader.getOrDefault("scribe.output.path", "./scribe")
                    );
                    return new StreamerDefinition(provider, channel, path);
                })
                .toList();
    }

    private Map<String, StreamingClient> initializeClients() {
        return streamers.stream()
                .map(StreamerDefinition::provider)
                .distinct()
                .collect(Collectors.toMap(
                        provider -> provider,
                        provider -> switch (provider) {
                            case "twitch" -> new TwitchClient(
                                    Secrets.twitchClientId(configReader),
                                    Secrets.twitchClientSecret(configReader)
                            );
                            default -> throw new IllegalArgumentException("Unexpected value: " + provider);
                        }
                ));
    }
}
