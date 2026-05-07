package com.dennysesay.provider;

import java.io.IOException;

public interface StreamingClient {
    boolean isLive(String stream) throws IOException, InterruptedException;
    String createUrl(String channel);
}
