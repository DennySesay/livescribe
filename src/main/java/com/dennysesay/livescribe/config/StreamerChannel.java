package com.dennysesay.livescribe.config;

public record StreamerChannel(
        String provider,
        String channelName,
        String outputPath
) {
}
