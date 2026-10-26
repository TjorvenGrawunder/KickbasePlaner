package org.tjorven.kickbaseplaner.kickbaserequests;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/kickbase")
public class KickbaseController {

    private static final String TOKEN_ATTRIBUTE = "kickbaseToken";
    private static final String MANAGER_ID_ATTRIBUTE = "kickbaseManagerId";
    private static final String LEAGUE_IDS_ATTRIBUTE = "kickbaseLeagueIds";

    private final KickbaseRequestService kickbaseRequestService;

    public KickbaseController(KickbaseRequestService kickbaseRequestService) {
        this.kickbaseRequestService = kickbaseRequestService;
    }

    @PostMapping("/login")
    public List<KickbaseLeague> login(
            @Valid @RequestBody KickbaseLoginRequest request,
            HttpServletRequest requestContext
    ) {
        KickbaseLoginResult result = kickbaseRequestService.login(request.email(), request.password());
        HttpSession session = requestContext.getSession(true);
        requestContext.changeSessionId();
        session.setAttribute(TOKEN_ATTRIBUTE, result.token());
        session.setAttribute(MANAGER_ID_ATTRIBUTE, result.managerId());
        session.setAttribute(
                LEAGUE_IDS_ATTRIBUTE,
                result.leagues().stream().map(KickbaseLeague::id).collect(Collectors.toUnmodifiableSet())
        );
        return result.leagues();
    }

    @PostMapping("/squad")
    public JsonNode getCurrentSquad(
            @Valid @RequestBody KickbaseSquadRequest request,
            HttpSession session
    ) {
        String token = (String) session.getAttribute(TOKEN_ATTRIBUTE);
        String managerId = (String) session.getAttribute(MANAGER_ID_ATTRIBUTE);
        Object leagueIdsValue = session.getAttribute(LEAGUE_IDS_ATTRIBUTE);
        if (token == null || managerId == null || !(leagueIdsValue instanceof Set<?> leagueIds)) {
            throw new KickbaseSessionException();
        }
        if (!leagueIds.contains(request.leagueId())) {
            throw new IllegalArgumentException("The selected league is not available in this session.");
        }

        return kickbaseRequestService.getCurrentSquad(token, managerId, request.leagueId());
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleKickbaseError(RestClientResponseException exception) {
        if (exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiError("Die Kickbase-Anmeldung ist fehlgeschlagen. Bitte prüfe deine Zugangsdaten."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("Kickbase ist momentan nicht erreichbar. Bitte versuche es später erneut."));
    }

    @ExceptionHandler(KickbaseSessionException.class)
    public ResponseEntity<ApiError> handleMissingSession() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("Bitte melde dich erneut bei Kickbase an."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidLeague() {
        return ResponseEntity.badRequest()
                .body(new ApiError("Die ausgewählte Liga ist für diese Sitzung nicht verfügbar."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleInvalidKickbaseResponse() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("Kickbase hat eine unerwartete Antwort zurückgegeben."));
    }

    public record ApiError(String message) {
    }

    private static final class KickbaseSessionException extends RuntimeException {
    }
}
