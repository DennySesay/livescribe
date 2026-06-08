package com.dennysesay.livescribe.provider.chzzk;

import com.dennysesay.livescribe.provider.StreamingClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ChzzkClient implements StreamingClient {
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public ChzzkClient() {
        this.objectMapper = new ObjectMapper();
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public String createUrl(String channelName) {
        return "https://chzzk.naver.com/live/" + channelName;
    }

    @Override
    public boolean isLive(String channelName) throws IOException, InterruptedException {
        URI liveDetailUri = URI.create("https://api.chzzk.naver.com/service/v3/channels/" + channelName + "/live-detail");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(liveDetailUri)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        int statusCode = response.statusCode();
        if (statusCode == 404) {
            return false;
        }

        if (statusCode != 200) {
            throw new IOException("Failed to query Chzzk stream status. HTTP " + statusCode + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode contentNode = json.get("content");

        if (contentNode == null || contentNode.isNull()) {
            return false;
        }

        JsonNode statusNode = contentNode.get("status");
        if (statusNode == null || statusNode.isNull()) {
            return false;
        }

        return "OPEN".equals(statusNode.asText());
    }
}
