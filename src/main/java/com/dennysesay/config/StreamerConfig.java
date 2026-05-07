package com.dennysesay.config;

import com.dennysesay.provider.StreamingClient;
import com.dennysesay.provider.twitch.TwitchClient;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StreamerConfig {
    private final ConfigReader configReader;
    private final List<StreamerDefinition> streamers;
    private final Map<String, StreamingClient> clientsByProvider;

    public StreamerConfig() throws IOException {
        this.configReader = new ConfigReader();
        this.streamers = parseStreamers();
        this.clientsByProvider = initializeClients();
    }

    public List<StreamerDefinition> getStreamers() {
        return streamers;
    }

    public StreamingClient getClient(String provider) {
        return clientsByProvider.get(provider);
    }

    public List<StreamerDefinition> parseStreamers() {
        String streamers = configReader.get("streamers");
        List<StreamerDefinition> streamerList = Arrays.stream(streamers.split(","))
                .map(String::trim)
                .map(streamer -> {
                    String[] parts = streamer.split(":");
                    String provider = parts[0];
                    String channel = parts[1];
                    String path = configReader.getOrDefault(
                            "scribe.output.path." + provider,
                            configReader.getOrDefault("scribe.output.path", "./scribe")
                    );
                    return new StreamerDefinition(provider, channel, path);
                })
                .toList();
        return streamerList;
    }

    private Map<String, StreamingClient> initializeClients() {
        return streamers.stream().collect(Collectors.toMap(
                StreamerDefinition::provider,
                streamer -> {
                    String provider = streamer.provider();
                    switch (provider) {
                        case "twitch" -> {
                            return new TwitchClient(
                                    Secrets.twitchClientId(configReader), 
                                    Secrets.twitchClientSecret(configReader)
                            );
                        }
                        default -> throw new IllegalArgumentException("Unexpected value: " + provider);
                    }
                }
        ));
    }

}
