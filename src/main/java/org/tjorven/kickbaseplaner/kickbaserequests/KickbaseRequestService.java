package org.tjorven.kickbaseplaner.kickbaserequests;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class KickbaseRequestService {

    private final RestClient restClient;

    public KickbaseRequestService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.kickbase.com")
                .build();
    }

    public KickbaseLoginResult login(String email, String password) {
        JsonNode response = restClient.post()
                .uri("/v4/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("em", email, "pass", password))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Kickbase returned an empty login response.");
        }

        String token = response.path("tkn").asText(null);
        String managerId = response.path("u").path("id").asText(null);
        JsonNode leaguesNode = response.path("srvl");
        if (token == null || token.isBlank() || managerId == null || managerId.isBlank()
                || !leaguesNode.isArray()) {
            throw new IllegalStateException("Kickbase login response is missing required account or league data.");
        }

        List<KickbaseLeague> leagues = new ArrayList<>();
        for (JsonNode league : leaguesNode) {
            String id = league.path("id").asText(null);
            String name = league.path("name").asText(null);
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalStateException("Kickbase returned a league without an ID or name.");
            }
            leagues.add(new KickbaseLeague(id, name));
        }

        return new KickbaseLoginResult(token, managerId, List.copyOf(leagues));
    }

    public JsonNode getCurrentSquad(String token, String managerId, String leagueId) {
        JsonNode response = restClient.get()
                .uri("/v4/leagues/{leagueId}/managers/{managerId}/squad", leagueId, managerId)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Kickbase returned an empty squad response.");
        }

        return response;
    }
}
