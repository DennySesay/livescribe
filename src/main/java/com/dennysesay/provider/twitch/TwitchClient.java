package com.dennysesay.provider.twitch;

import com.dennysesay.provider.StreamingClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class TwitchClient implements StreamingClient {
    String twitchId;
    String twitchSecret;

    final ObjectMapper objectMapper = new ObjectMapper();
    final HttpClient client = HttpClient.newHttpClient();

    public TwitchClient(String twitchId, String twitchSecret) {
        this.twitchId = twitchId;
        this.twitchSecret = twitchSecret;
    }

    @Override
    public String createUrl(String channel) {
        return "https://twitch.tv/";
    }

    private String getAuthToken() throws IOException, InterruptedException {
        URI tokenUri = URI.create("https://id.twitch.tv/oauth2/token");
        String grantType = "client_credentials";

        String requestBody =
                "client_id=" + URLEncoder.encode(twitchId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(twitchSecret, StandardCharsets.UTF_8)
                + "&grant_type=" + URLEncoder.encode(grantType, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get Twitch auth token. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode accessTokenNode = json.get("access_token");

        if (accessTokenNode == null || accessTokenNode.asString().isBlank()) {
            throw new IOException("Twitch token response did not contain a valid access_token: " + response.body());
        }

        return accessTokenNode.asString();
    }

    @Override
    public boolean isLive(String stream) throws IOException, InterruptedException {
        URI streamUri = URI.create("https://api.twitch.tv/helix/streams?user_login=" +
                URLEncoder.encode(stream, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(streamUri)
                .header("Authorization", "Bearer " + getAuthToken())
                .header("Client-Id", twitchId)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to query Twitch stream status. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode dataNode = json.get("data");

        if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
            System.out.println("stream is not live");
            return false;
        }

        return true;
    }
}
