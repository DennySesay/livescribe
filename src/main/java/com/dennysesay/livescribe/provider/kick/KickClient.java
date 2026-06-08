package com.dennysesay.livescribe.provider.kick;

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

public class KickClient implements StreamingClient {
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private String cachedToken = null;
    private Instant tokenExpiry = null;

    public KickClient(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = new ObjectMapper();
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public String createUrl(String channelName) {
        return "https://kick.com/" + channelName;
    }

    private synchronized String getAuthToken() throws IOException, InterruptedException {
        if (cachedToken != null && tokenExpiry != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        URI tokenUri = URI.create("https://id.kick.com/oauth/token");
        String grantType = "client_credentials";

        String requestBody =
                "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=" + URLEncoder.encode(grantType, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("channel:read", StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to get Kick auth token. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());
        JsonNode accessTokenNode = json.get("access_token");

        if (accessTokenNode == null || accessTokenNode.asText().isBlank()) {
            throw new IOException("Kick token response did not contain a valid access_token: " + response.body());
        }

        cachedToken = accessTokenNode.asText();
        JsonNode expiresInNode = json.get("expires_in");
        long expiresInSeconds = expiresInNode != null ? expiresInNode.asLong() : 3600L;
        // Expire token 1 minute early for safety buffer
        tokenExpiry = Instant.now().plusSeconds(expiresInSeconds - 60);

        return cachedToken;
    }

    @Override
    public boolean isLive(String channelName) throws IOException, InterruptedException {
        URI streamUri = URI.create("https://api.kick.com/public/v1/channels?slug=" +
                URLEncoder.encode(channelName, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(streamUri)
                .header("Authorization", "Bearer " + getAuthToken())
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to query Kick stream status. HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode json = objectMapper.readTree(response.body());

        // Support both wrapped 'data' response or raw response representation
        JsonNode channelNode = null;
        if (json.has("data")) {
            JsonNode dataNode = json.get("data");
            if (dataNode.isArray()) {
                if (!dataNode.isEmpty()) {
                    channelNode = dataNode.get(0);
                }
            } else {
                channelNode = dataNode;
            }
        } else {
            channelNode = json;
        }

        if (channelNode == null || channelNode.isNull()) {
            return false;
        }

        JsonNode isLiveNode = channelNode.get("is_live");
        if (isLiveNode == null || isLiveNode.isNull()) {
            return false;
        }

        return isLiveNode.asBoolean();
    }
}
