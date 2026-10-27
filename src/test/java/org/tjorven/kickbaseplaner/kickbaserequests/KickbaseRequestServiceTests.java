package org.tjorven.kickbaseplaner.kickbaserequests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KickbaseRequestServiceTests {

    private MockRestServiceServer mockServer;
    private KickbaseRequestService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service = new KickbaseRequestService(builder);
    }

    @Test
    void loginReturnsAllAvailableLeaguesWithoutExposingCredentialsInLeagueData() {
        mockServer.expect(requestTo("https://api.kickbase.com/v4/user/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"em":"manager@example.com","pass":"secret"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "tkn":"session-token",
                          "u":{"id":"manager-123"},
                          "srvl":[
                            {"id":"league-1","name":"Freunde-Liga"},
                            {"id":"league-2","name":"Büro-Liga"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        KickbaseLoginResult result = service.login("manager@example.com", "secret");

        assertThat(result.managerId()).isEqualTo("manager-123");
        assertThat(result.token()).isEqualTo("session-token");
        assertThat(result.leagues()).containsExactly(
                new KickbaseLeague("league-1", "Freunde-Liga"),
                new KickbaseLeague("league-2", "Büro-Liga")
        );
        mockServer.verify();
    }

    @Test
    void getsTheCurrentSquadUsingTheSessionToken() {
        mockServer.expect(requestTo(
                        "https://api.kickbase.com/v4/leagues/league-456/managers/manager-123/squad"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer session-token"))
                .andRespond(withSuccess("""
                        {"u":"manager-123","unm":"Manager","it":[{"pi":"player-1","pn":"Spieler"}]}
                        """, MediaType.APPLICATION_JSON));

        var squad = service.getCurrentSquad("session-token", "manager-123", "league-456");

        assertThat(squad.path("u").asText()).isEqualTo("manager-123");
        assertThat(squad.path("it").get(0).path("pn").asText()).isEqualTo("Spieler");
        mockServer.verify();
    }

    @Test
    void getsTheCurrentMarketUsingTheSessionToken() {
        mockServer.expect(requestTo("https://api.kickbase.com/v4/leagues/league-456/market"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"it":[{"pi":"player-2","pn":"Marktspieler","mv":1200000}]}
                        """, MediaType.APPLICATION_JSON));

        var market = service.getCurrentMarket("session-token", "league-456");

        assertThat(market.path("it").get(0).path("pn").asText()).isEqualTo("Marktspieler");
        mockServer.verify();
    }

    @Test
    void getsOffersForEveryManagerUsingTheirSessionTokens() {
        mockServer.expect(requestTo("https://api.kickbase.com/v4/leagues/league-456/market"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"it":[{"pi":"player-2","ofs":[{"price":1300000}]}]}
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.kickbase.com/v4/leagues/league-456/market"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"it":[{"pi":"player-2","ofs":[{"price":1500000}]}]}
                        """, MediaType.APPLICATION_JSON));

        var offers = service.getAllOffers("league-456", List.of(
                new KickbaseLoginResult("token-1", "manager-123", List.of()),
                new KickbaseLoginResult("token-2", "manager-456", List.of())
        ));

        assertThat(offers).hasSize(2);
        assertThat(offers.get(0).managerId()).isEqualTo("manager-123");
        assertThat(offers.get(0).market().path("it").get(0).path("ofs").get(0).path("price").asInt())
                .isEqualTo(1300000);
        assertThat(offers.get(1).managerId()).isEqualTo("manager-456");
        assertThat(offers.get(1).market().path("it").get(0).path("ofs").get(0).path("price").asInt())
                .isEqualTo(1500000);
        mockServer.verify();
    }
}
