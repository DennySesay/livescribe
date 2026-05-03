package com.dennysesay.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class StreamerConfig {
    ConfigReader config = new ConfigReader();

    String clientId = Secrets.twitchClientId(config);
    String clientSecret = Secrets.twitchClientSecret(config);

    String streamers = config.get("streamers");
    List<StreamerDefinition> streamerList = Arrays.stream(streamers.split(","))
            .map(String::trim)
            .map(streamer -> {
                String[] parts = streamer.split(":");
                String provider = parts[0];
                String channel = parts[1];
                String path = config.getOrDefault(
                        "scribe.output.path." + provider,
                        config.getOrDefault("scribe.output.path", "./scribe")
                );
                return new StreamerDefinition(provider, channel, path);
            })
            .collect(Collectors.toList());

    public void streamerListConverter() {
        System.out.println(streamerList);
    }
}
