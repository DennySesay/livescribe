package com.dennysesay.config;

public record StreamerDefinition(
        String provider,
        String streamer,
        String outputPath
) {
}
