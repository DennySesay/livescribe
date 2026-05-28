package com.dennysesay.livescribe.provider;

import java.io.IOException;

public interface StreamingClient {
    boolean isLive(String channelName) throws IOException, InterruptedException;
    String createUrl(String channelName);
}
