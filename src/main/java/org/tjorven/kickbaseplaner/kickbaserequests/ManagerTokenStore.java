package org.tjorven.kickbaseplaner.kickbaserequests;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

@Service
public class ManagerTokenStore {

    private static final String SETTINGS_KEY = "weekly-token-count";
    private static final ZoneId REFRESH_ZONE = ZoneId.of("Europe/Berlin");
    private static final int MAX_WEEKLY_TOKENS = 1000;

    private final Path file;
    private final int defaultWeeklyTokenCount;
    private final Clock clock;

    @Autowired
    public ManagerTokenStore(
            @Value("${kickbase.tokens.file:}") String configuredFile,
            @Value("${kickbase.tokens.weekly-count:4}") int defaultWeeklyTokenCount
    ) {
        this(
                configuredFile.isBlank()
                        ? Path.of(System.getProperty("user.home"), ".kickbase-planer", "tokens.properties")
                        : Path.of(configuredFile),
                defaultWeeklyTokenCount,
                Clock.systemUTC()
        );
    }

    ManagerTokenStore(Path file, int defaultWeeklyTokenCount, Clock clock) {
        if (defaultWeeklyTokenCount < 0 || defaultWeeklyTokenCount > MAX_WEEKLY_TOKENS) {
            throw new IllegalArgumentException("The weekly token count must be between 0 and 1000.");
        }
        this.file = file;
        this.defaultWeeklyTokenCount = defaultWeeklyTokenCount;
        this.clock = clock;
    }

    public synchronized TokenBalance getBalance(String managerId) {
        Properties state = load();
        int weeklyCount = weeklyCount(state);
        String prefix = managerPrefix(managerId);
        int balance = readBalance(state, prefix, weeklyCount);
        Instant refreshedAt = readInstant(state, prefix + "refreshed-at", clock.instant());
        Instant currentRefresh = mostRecentRefresh(clock.instant());
        if (refreshedAt.isBefore(currentRefresh)) {
            balance = weeklyCount;
            refreshedAt = currentRefresh;
            writeBalance(state, prefix, balance, refreshedAt);
            save(state);
        }
        return new TokenBalance(balance, weeklyCount, nextRefresh(clock.instant()));
    }

    public synchronized MarketTokenStatus getMarketStatus(String managerId, String leagueId) {
        TokenBalance balance = getBalance(managerId);
        Properties state = load();
        String unlockedPrefix = managerPrefix(managerId) + "unlocked.";
        String currentRefresh = mostRecentRefresh(clock.instant()).toString();
        String leaguePrefix = leagueId + "\n";
        List<String> unlockedPlayerIds = new ArrayList<>();

        for (String key : state.stringPropertyNames()) {
            if (!key.startsWith(unlockedPrefix) || !currentRefresh.equals(state.getProperty(key))) {
                continue;
            }
            String decodedKey;
            try {
                decodedKey = new String(
                        Base64.getUrlDecoder().decode(key.substring(unlockedPrefix.length())),
                        java.nio.charset.StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException exception) {
                throw new TokenStoreException("The token store contains an invalid unlocked player.", exception);
            }
            if (decodedKey.startsWith(leaguePrefix)) {
                unlockedPlayerIds.add(decodedKey.substring(leaguePrefix.length()));
            }
        }
        unlockedPlayerIds.sort(Comparator.naturalOrder());
        return new MarketTokenStatus(
                balance.remaining(),
                balance.weeklyCount(),
                balance.nextRefresh(),
                List.copyOf(unlockedPlayerIds)
        );
    }

    public synchronized TokenBalance redeem(String managerId, String unlockKey, Supplier<Double> offerLookup) {
        Properties state = load();
        int weeklyCount = weeklyCount(state);
        String prefix = managerPrefix(managerId);
        String unlockedKey = unlockedKey(managerId, unlockKey);
        int balance = readBalance(state, prefix, weeklyCount);
        Instant refreshedAt = readInstant(state, prefix + "refreshed-at", clock.instant());
        Instant now = clock.instant();
        Instant currentRefresh = mostRecentRefresh(now);
        if (refreshedAt.isBefore(currentRefresh)) {
            balance = weeklyCount;
            refreshedAt = currentRefresh;
        }
        boolean alreadyUnlocked = currentRefresh.toString().equals(state.getProperty(unlockedKey));
        if (balance == 0 && !alreadyUnlocked) {
            writeBalance(state, prefix, balance, refreshedAt);
            save(state);
            throw new NoTokensAvailableException();
        }

        Double highestOffer = offerLookup.get();
        if (!alreadyUnlocked) {
            balance--;
            state.setProperty(unlockedKey, currentRefresh.toString());
        }
        writeBalance(state, prefix, balance, refreshedAt);
        save(state);
        return new TokenBalance(balance, weeklyCount, nextRefresh(now), highestOffer);
    }

    public synchronized RevealedOffer refreshUnlockedOffer(
            String managerId,
            String unlockKey,
            Supplier<Double> offerLookup
    ) {
        Properties state = load();
        Instant currentRefresh = mostRecentRefresh(clock.instant());
        String unlockedKey = unlockedKey(managerId, unlockKey);
        boolean unlocked = currentRefresh.toString().equals(state.getProperty(unlockedKey));
        if (!unlocked) {
            return new RevealedOffer(getBalance(managerId), null, false);
        }
        return new RevealedOffer(getBalance(managerId), offerLookup.get(), true);
    }

    public synchronized int getWeeklyTokenCount() {
        return weeklyCount(load());
    }

    public synchronized void setWeeklyTokenCount(int count) {
        if (count < 0 || count > MAX_WEEKLY_TOKENS) {
            throw new IllegalArgumentException("The weekly token count must be between 0 and 1000.");
        }
        Properties state = load();
        state.setProperty(SETTINGS_KEY, Integer.toString(count));
        save(state);
    }

    public synchronized TokenBalance setBalance(String managerId, int balance) {
        if (balance < 0 || balance > MAX_WEEKLY_TOKENS) {
            throw new IllegalArgumentException("The manager token balance must be between 0 and 1000.");
        }
        Properties state = load();
        int weeklyCount = weeklyCount(state);
        String prefix = managerPrefix(managerId);
        Instant refreshedAt = mostRecentRefresh(clock.instant());
        writeBalance(state, prefix, balance, refreshedAt);
        save(state);
        return new TokenBalance(balance, weeklyCount, nextRefresh(clock.instant()));
    }

    private Properties load() {
        Properties state = new Properties();
        if (!Files.exists(file)) {
            state.setProperty(SETTINGS_KEY, Integer.toString(defaultWeeklyTokenCount));
            return state;
        }
        try (var input = Files.newInputStream(file)) {
            state.load(input);
            weeklyCount(state);
            return state;
        } catch (IOException | IllegalArgumentException exception) {
            throw new TokenStoreException("Could not read the manager token store.", exception);
        }
    }

    private void save(Properties state) {
        Path parent = file.toAbsolutePath().getParent();
        Path temporaryFile = null;
        try {
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, "tokens-", ".tmp");
            try (var output = Files.newOutputStream(temporaryFile)) {
                state.store(output, "Kickbase Planer token balances");
            }
            try {
                Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveException) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new TokenStoreException("Could not save the manager token store.", exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    throw new TokenStoreException("Could not clean up the temporary token store.", exception);
                }
            }
        }
    }

    private int weeklyCount(Properties state) {
        int count = readInt(state, SETTINGS_KEY, defaultWeeklyTokenCount);
        if (count < 0 || count > MAX_WEEKLY_TOKENS) {
            throw new TokenStoreException("The saved weekly token count is invalid.");
        }
        return count;
    }

    private int readBalance(Properties state, String prefix, int defaultBalance) {
        int balance = readInt(state, prefix + "balance", defaultBalance);
        if (balance < 0 || balance > MAX_WEEKLY_TOKENS) {
            throw new TokenStoreException("The saved manager token balance is invalid.");
        }
        return balance;
    }

    private int readInt(Properties state, String key, int fallback) {
        String value = state.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new TokenStoreException("The token store contains an invalid number.", exception);
        }
    }

    private Instant readInstant(Properties state, String key, Instant fallback) {
        String value = state.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new TokenStoreException("The token store contains an invalid refresh time.", exception);
        }
    }

    private void writeBalance(Properties state, String prefix, int balance, Instant refreshedAt) {
        state.setProperty(prefix + "balance", Integer.toString(balance));
        state.setProperty(prefix + "refreshed-at", refreshedAt.toString());
    }

    private String unlockedKey(String managerId, String unlockKey) {
        return managerPrefix(managerId) + "unlocked."
                + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(unlockKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String managerPrefix(String managerId) {
        String encodedId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(managerId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "manager." + encodedId + ".";
    }

    private Instant mostRecentRefresh(Instant instant) {
        ZonedDateTime now = instant.atZone(REFRESH_ZONE);
        ZonedDateTime refresh = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
                .withHour(20).withMinute(30).withSecond(0).withNano(0);
        if (now.isBefore(refresh)) {
            refresh = refresh.minusWeeks(1);
        }
        return refresh.toInstant();
    }

    private Instant nextRefresh(Instant instant) {
        return mostRecentRefresh(instant).atZone(REFRESH_ZONE).plusWeeks(1).toInstant();
    }

    public record TokenBalance(int remaining, int weeklyCount, Instant nextRefresh, Double highestOffer) {
        public TokenBalance(int remaining, int weeklyCount, Instant nextRefresh) {
            this(remaining, weeklyCount, nextRefresh, null);
        }
    }

    public record RevealedOffer(TokenBalance balance, Double highestOffer, boolean unlocked) {
    }

    public record MarketTokenStatus(
            int remaining,
            int weeklyCount,
            Instant nextRefresh,
            List<String> unlockedPlayerIds
    ) {
    }

    public static final class NoTokensAvailableException extends RuntimeException {
        public NoTokensAvailableException() {
            super("Du hast aktuell keine Tokens mehr. Sie werden freitags um 20:30 Uhr erneuert.");
        }
    }

    public static final class TokenStoreException extends RuntimeException {
        public TokenStoreException(String message, Throwable cause) {
            super(message, cause);
        }

        public TokenStoreException(String message) {
            super(message);
        }
    }
}
