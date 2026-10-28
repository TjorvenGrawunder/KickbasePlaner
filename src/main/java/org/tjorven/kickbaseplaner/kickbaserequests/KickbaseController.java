package org.tjorven.kickbaseplaner.kickbaserequests;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/kickbase")
public class KickbaseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(KickbaseController.class);
    private static final String TOKEN_ATTRIBUTE = "kickbaseToken";
    private static final String MANAGER_ID_ATTRIBUTE = "kickbaseManagerId";
    private static final String LEAGUE_IDS_ATTRIBUTE = "kickbaseLeagueIds";
    private static final String ADMIN_ATTRIBUTE = "kickbaseAdmin";

    private final KickbaseRequestService kickbaseRequestService;
    private final SavedKickbaseManagerStore savedManagerStore;
    private final ManagerTokenStore managerTokenStore;
    private final String adminPassword;

    public KickbaseController(
            KickbaseRequestService kickbaseRequestService,
            SavedKickbaseManagerStore savedManagerStore,
            ManagerTokenStore managerTokenStore,
            @Value("${kickbase.admin.password:}") String adminPassword
    ) {
        this.kickbaseRequestService = kickbaseRequestService;
        this.savedManagerStore = savedManagerStore;
        this.managerTokenStore = managerTokenStore;
        this.adminPassword = adminPassword;
    }

    @PostMapping("/login")
    public List<KickbaseLeague> login(
            @Valid @RequestBody KickbaseLoginRequest request,
            HttpServletRequest requestContext
    ) {
        KickbaseLoginResult result = kickbaseRequestService.login(request.email(), request.password());
        savedManagerStore.save(result.managerId(), request.email(), request.password());
        return startSession(result, requestContext);
    }

    @PostMapping("/admin/login")
    public AdminLoginResult loginAdmin(
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest requestContext
    ) {
        if (!"admin".equals(request.email()) || adminPassword.isBlank()
                || !MessageDigest.isEqual(
                        request.password().getBytes(StandardCharsets.UTF_8),
                        adminPassword.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidAdminCredentialsException();
        }
        HttpSession session = requestContext.getSession(true);
        requestContext.changeSessionId();
        session.removeAttribute(TOKEN_ATTRIBUTE);
        session.removeAttribute(MANAGER_ID_ATTRIBUTE);
        session.removeAttribute(LEAGUE_IDS_ATTRIBUTE);
        session.setAttribute(ADMIN_ATTRIBUTE, true);
        return new AdminLoginResult(true);
    }

    @PostMapping("/admin/logout")
    public AdminLoginResult logoutAdmin(HttpSession session) {
        requireAdminSession(session);
        session.invalidate();
        return new AdminLoginResult(false);
    }

    private List<KickbaseLeague> startSession(KickbaseLoginResult result, HttpServletRequest requestContext) {
        HttpSession session = requestContext.getSession(true);
        requestContext.changeSessionId();
        session.removeAttribute(ADMIN_ATTRIBUTE);
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
        requireManagerSession(session, request.leagueId());
        String token = (String) session.getAttribute(TOKEN_ATTRIBUTE);
        String managerId = (String) session.getAttribute(MANAGER_ID_ATTRIBUTE);
        return kickbaseRequestService.getCurrentSquad(token, managerId, request.leagueId());
    }

    @PostMapping("/market")
    public JsonNode getCurrentMarket(
            @Valid @RequestBody KickbaseSquadRequest request,
            HttpSession session
    ) {
        requireManagerSession(session, request.leagueId());
        String token = (String) session.getAttribute(TOKEN_ATTRIBUTE);
        JsonNode market = kickbaseRequestService.getCurrentMarket(token, request.leagueId()).deepCopy();
        JsonNode players = market.path("it");
        if (!players.isArray() && market instanceof ArrayNode) {
            players = market;
        }
        if (players instanceof ArrayNode marketPlayers) {
            for (JsonNode player : marketPlayers) {
                if (player instanceof ObjectNode marketPlayer) {
                    marketPlayer.remove("ofs");
                }
            }
        }
        return market;
    }

    @GetMapping("/market/tokens")
    public ManagerTokenStore.MarketTokenStatus getManagerTokenBalance(
            @RequestParam String leagueId,
            HttpSession session
    ) {
        requireManagerSession(session, leagueId);
        String managerId = (String) session.getAttribute(MANAGER_ID_ATTRIBUTE);
        return managerTokenStore.getMarketStatus(managerId, leagueId);
    }

    @PostMapping("/market/{playerId}/highest-offer")
    public ManagerTokenStore.TokenBalance redeemToken(
            @PathVariable String playerId,
            @Valid @RequestBody KickbaseSquadRequest request,
            HttpSession session
    ) {
        requireManagerSession(session, request.leagueId());
        String managerId = (String) session.getAttribute(MANAGER_ID_ATTRIBUTE);
        String currentToken = (String) session.getAttribute(TOKEN_ATTRIBUTE);
        return managerTokenStore.redeem(
                managerId,
                request.leagueId() + "\n" + playerId,
                () -> findHighestOffer(playerId, request.leagueId(), currentToken)
        );
    }

    @GetMapping("/market/{playerId}/highest-offer")
    public ManagerTokenStore.RevealedOffer refreshRevealedOffer(
            @PathVariable String playerId,
            @RequestParam String leagueId,
            HttpSession session
    ) {
        requireManagerSession(session, leagueId);
        String managerId = (String) session.getAttribute(MANAGER_ID_ATTRIBUTE);
        String currentToken = (String) session.getAttribute(TOKEN_ATTRIBUTE);
        return managerTokenStore.refreshUnlockedOffer(
                managerId,
                leagueId + "\n" + playerId,
                () -> findHighestOffer(playerId, leagueId, currentToken)
        );
    }

    @GetMapping("/admin/settings")
    public AdminSettings getAdminSettings(HttpSession session) {
        requireAdminSession(session);
        List<AdminManagerTokens> managers = savedManagerStore.findAll().stream()
                .map(manager -> new AdminManagerTokens(
                        manager.id(),
                        manager.email(),
                        managerTokenStore.getBalance(manager.id()).remaining()
                ))
                .toList();
        return new AdminSettings(managerTokenStore.getWeeklyTokenCount(), managers);
    }

    @PostMapping("/admin/settings")
    public AdminSettings updateAdminSettings(
            @Valid @RequestBody AdminSettings request,
            HttpSession session
    ) {
        requireAdminSession(session);
        managerTokenStore.setWeeklyTokenCount(request.weeklyTokens());
        return getAdminSettings(session);
    }

    @PostMapping("/admin/managers/{managerId}/tokens")
    public AdminManagerTokens updateManagerTokens(
            @PathVariable String managerId,
            @Valid @RequestBody ManagerTokenAdjustment request,
            HttpSession session
    ) {
        requireAdminSession(session);
        SavedKickbaseManagerStore.SavedManager manager = savedManagerStore.findAll().stream()
                .filter(savedManager -> savedManager.id().equals(managerId))
                .findFirst()
                .orElseThrow(SavedKickbaseManagerStore.ManagerNotFoundException::new);
        int remaining = managerTokenStore.setBalance(managerId, request.remaining()).remaining();
        return new AdminManagerTokens(manager.id(), manager.email(), remaining);
    }

    private Double findHighestOffer(String playerId, String leagueId, String currentToken) {
        JsonNode currentMarketPlayers = kickbaseRequestService.getCurrentMarket(currentToken, leagueId).path("it");
        if (!containsPlayer(currentMarketPlayers, playerId)) {
            throw new MarketPlayerNotFoundException();
        }

        List<KickbaseLoginResult> leagueManagers = savedManagerStore.findAll().stream()
                .map(manager -> {
                    SavedKickbaseManagerStore.Credentials credentials =
                            savedManagerStore.findCredentials(manager.id());
                    return kickbaseRequestService.login(credentials.email(), credentials.password());
                })
                .filter(manager -> manager.leagues().stream()
                        .anyMatch(league -> league.id().equals(leagueId)))
                .toList();

        Double highestOffer = null;
        for (KickbaseLoginResult manager : leagueManagers) {
            JsonNode players = kickbaseRequestService.getCurrentMarket(manager.token(), leagueId).path("it");
            if (!players.isArray()) {
                continue;
            }
            for (JsonNode player : players) {
                if (!playerId.equals(playerId(player))) {
                    continue;
                }
                JsonNode offers = player.path("ofs");
                if (!offers.isArray()) {
                    continue;
                }
                for (JsonNode offer : offers) {
                    JsonNode amountNode = offer.path("uop");
                    if (amountNode.isMissingNode() || amountNode.isNull()) {
                        continue;
                    }
                    double amount = amountNode.asDouble(Double.NaN);
                    if (Double.isFinite(amount) && (highestOffer == null || amount > highestOffer)) {
                        highestOffer = amount;
                    }
                }
            }
        }
        return highestOffer;
    }

    private boolean containsPlayer(JsonNode players, String playerId) {
        if (!players.isArray()) {
            return false;
        }
        for (JsonNode player : players) {
            if (playerId.equals(playerId(player))) {
                return true;
            }
        }
        return false;
    }

    private String playerId(JsonNode player) {
        for (String field : List.of("i", "pi", "id")) {
            JsonNode id = player.path(field);
            if (!id.isMissingNode() && !id.isNull() && !id.asText().isBlank()) {
                return id.asText();
            }
        }
        return null;
    }

    private void requireManagerSession(HttpSession session) {
        if (Boolean.TRUE.equals(session.getAttribute(ADMIN_ATTRIBUTE))
                || session.getAttribute(TOKEN_ATTRIBUTE) == null
                || session.getAttribute(MANAGER_ID_ATTRIBUTE) == null
                || !(session.getAttribute(LEAGUE_IDS_ATTRIBUTE) instanceof Set<?>)) {
            throw new KickbaseSessionException();
        }
    }

    private void requireManagerSession(HttpSession session, String leagueId) {
        requireManagerSession(session);
        Set<?> leagueIds = (Set<?>) session.getAttribute(LEAGUE_IDS_ATTRIBUTE);
        if (!leagueIds.contains(leagueId)) {
            throw new IllegalArgumentException("The selected league is not available in this session.");
        }
    }

    private void requireAdminSession(HttpSession session) {
        if (!Boolean.TRUE.equals(session.getAttribute(ADMIN_ATTRIBUTE))) {
            throw new KickbaseSessionException();
        }
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> handleKickbaseError(RestClientResponseException exception) {
        LOGGER.warn("Kickbase request failed with HTTP status {}", exception.getStatusCode().value());
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

    @ExceptionHandler(InvalidAdminCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidAdminCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("Die Admin-Anmeldung ist fehlgeschlagen."));
    }

    @ExceptionHandler(ManagerTokenStore.NoTokensAvailableException.class)
    public ResponseEntity<ApiError> handleNoTokensAvailable(ManagerTokenStore.NoTokensAvailableException exception) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(ManagerTokenStore.TokenStoreException.class)
    public ResponseEntity<ApiError> handleTokenStoreError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("Die Token-Einstellungen konnten nicht gelesen oder gespeichert werden."));
    }

    @ExceptionHandler(MarketPlayerNotFoundException.class)
    public ResponseEntity<ApiError> handleMarketPlayerNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("Der Spieler steht nicht mehr auf dem Transfermarkt."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidLeague() {
        return ResponseEntity.badRequest()
                .body(new ApiError("Die ausgewählte Liga ist für diese Sitzung nicht verfügbar."));
    }

    @ExceptionHandler(SavedKickbaseManagerStore.ManagerNotFoundException.class)
    public ResponseEntity<ApiError> handleMissingSavedManager(
            SavedKickbaseManagerStore.ManagerNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(SavedKickbaseManagerStore.StoreException.class)
    public ResponseEntity<ApiError> handleSavedManagerStoreError(
            SavedKickbaseManagerStore.StoreException exception
    ) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleInvalidKickbaseResponse() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("Kickbase hat eine unerwartete Antwort zurückgegeben."));
    }

    public record ApiError(String message) {
    }

    public record AdminLoginResult(boolean admin) {
    }

    public record AdminSettings(@Min(0) @Max(1000) int weeklyTokens, List<AdminManagerTokens> managers) {
    }

    public record AdminManagerTokens(String id, String email, int remaining) {
    }

    public record ManagerTokenAdjustment(@Min(0) @Max(1000) int remaining) {
    }

    private static final class InvalidAdminCredentialsException extends RuntimeException {
    }

    private static final class MarketPlayerNotFoundException extends RuntimeException {
    }

    private static final class KickbaseSessionException extends RuntimeException {
    }
}
