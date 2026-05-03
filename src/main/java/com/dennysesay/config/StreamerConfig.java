package com.dennysesay.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class StreamerConfig {
    ConfigReader config = new ConfigReader();

    String clientId = Secrets.twitchClientId(config);
    String clientSecret = Secrets.twitchClientSecret(config);

    String streamers = config.get("streamers");
    List<String> streamerList = Arrays.stream(streamers.split(","))
            .map(String::trim)
            .map(streamer -> Arrays.toString(streamer.split(":")))
            .collect(Collectors.toList());

    public void streamerListConverter() {
        System.out.println(streamerList);
    }
}
