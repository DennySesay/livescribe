package com.dennysesay.livescribe.provider.twitch;

import com.dennysesay.livescribe.provider.StreamingClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class TwitchClient implements StreamingClient {
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private String cachedToken = null;
    private Instant tokenExpiry = null;

    public TwitchClient(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = new ObjectMapper();
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public String createUrl(String channelName) {
        return "https://twitch.tv/" + channelName;
    }

    private synchronized String getAuthToken() throws IOException, InterruptedException {
        if (cachedToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        URI tokenUri = URI.create("https://id.twitch.tv/oauth2/token");
        String grantType = "client_credentials";

        String requestBody =
                "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
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

        cachedToken = accessTokenNode.asString();
        JsonNode expiresInNode = json.get("expires_in");
        long expiresInSeconds = expiresInNode != null ? expiresInNode.asLong() : 3600L;
        // Expire token 1 minute early for safety buffer
        tokenExpiry = Instant.now().plusSeconds(expiresInSeconds - 60);

        return cachedToken;
    }

    @Override
    public boolean isLive(String channelName) throws IOException, InterruptedException {
        URI streamUri = URI.create("https://api.twitch.tv/helix/streams?user_login=" +
                URLEncoder.encode(channelName, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(streamUri)
                .header("Authorization", "Bearer " + getAuthToken())
                .header("Client-Id", clientId)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to query Twitch stream status. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode dataNode = json.get("data");

        if (dataNode == null || !dataNode.isArray() || dataNode.isEmpty()) {
            return false;
        }

        return true;
    }
}
