package com.dennysesay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TwitchClient {
    ObjectMapper objectMapper = new ObjectMapper();
    private List<String> getTwitchConfig() {
        String id = System.getenv("LIVESCRIBE_ID");
        String secret = System.getenv("LIVESCRIBE_SECRET");
        return List.of(id, secret);
    };

    public String getAuthToken() throws IOException, InterruptedException {
        URI tokenUri = URI.create("https://id.twitch.tv/oauth2/token");
        String headerName = "Content-Type";
        String headerBody = "application/x-www-form-urlencoded";
        String grantType = "client_credentials";

        List<String> config = getTwitchConfig();
        String requestBody =
                "client_id=" + URLEncoder.encode(config.getFirst(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(config.getLast(), StandardCharsets.UTF_8)
                + "&grant_type=" + URLEncoder.encode(grantType, StandardCharsets.UTF_8);

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(tokenUri)
                    .header(headerName, headerBody)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        JsonNode json = objectMapper.readTree(response.body());
        return json.get("access_token").asString();
    }
}
