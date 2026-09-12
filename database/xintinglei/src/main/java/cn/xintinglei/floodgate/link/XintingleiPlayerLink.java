package cn.xintinglei.floodgate.link;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.link.CommonPlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;

/**
 * Read-only Floodgate PlayerLink backed by the Xintinglei identity service.
 *
 * <p>The service only accepts the XUID produced by Floodgate during the Bedrock handshake. A
 * separate Velocity companion plugin creates a one-time link code for unlinked players; the
 * Blessing Skin website consumes that code and creates the binding.</p>
 */
public final class XintingleiPlayerLink extends CommonPlayerLink {
    private static final int MAX_RESPONSE_LENGTH = 65_536;

    private final Map<UUID, CacheEntry> bedrockCache = new ConcurrentHashMap<>();
    private final Map<UUID, CacheEntry> javaCache = new ConcurrentHashMap<>();
    private XintingleiLinkConfig config;
    private HttpClient client;

    @Override
    public void load() {
        config = getConfig(XintingleiLinkConfig.class);
        if (config == null) {
            throw new IllegalStateException("Failed to load xintinglei.yml");
        }

        validateConfig(config);
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        getLogger().info("Loaded read-only Xintinglei Blessing Skin PlayerLink");
    }

    @Override
    public @NonNull CompletableFuture<LinkedPlayer> getLinkedPlayer(@NonNull UUID bedrockId) {
        CacheEntry cached = bedrockCache.get(bedrockId);
        if (cached != null && !cached.expired()) {
            return CompletableFuture.completedFuture(cached.player());
        }

        return CompletableFuture.supplyAsync(() -> lookup(bedrockId), getExecutorService());
    }

    private LinkedPlayer lookup(UUID bedrockId) {
        if (bedrockId.getMostSignificantBits() != 0) {
            throw new CompletionException(new IllegalArgumentException(
                    "Floodgate Bedrock UUID does not contain an XUID: " + bedrockId));
        }

        String xuid = Long.toUnsignedString(bedrockId.getLeastSignificantBits());
        URI uri = URI.create(config.bindingEndpoint().replace("{xuid}", xuid));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config.timeoutMillis()))
                .header("Accept", "application/json")
                .GET();
        if (config.bearerToken() != null && !config.bearerToken().isBlank()) {
            request.header("Authorization", "Bearer " + config.bearerToken());
        }

        final HttpResponse<String> response;
        try {
            response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new CompletionException("Xintinglei identity binding lookup failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Xintinglei identity binding lookup was interrupted", exception);
        }

        if (response.statusCode() == 404) {
            cache(bedrockId, null, config.negativeCacheTtlSeconds());
            return null;
        }
        if (response.statusCode() != 200) {
            throw new CompletionException(new IOException(
                    "Xintinglei identity binding lookup returned HTTP " + response.statusCode()));
        }
        if (response.body().length() > MAX_RESPONSE_LENGTH) {
            throw new CompletionException(new IOException("Xintinglei identity binding response is too large"));
        }

        LinkedPlayer linkedPlayer = parseResponse(response.body(), bedrockId);
        long ttl = linkedPlayer == null ? config.negativeCacheTtlSeconds() : config.cacheTtlSeconds();
        cache(bedrockId, linkedPlayer, ttl);
        return linkedPlayer;
    }

    private LinkedPlayer parseResponse(String responseBody, UUID bedrockId) {
        final JsonObject response;
        try {
            response = new JsonParser().parse(responseBody).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new CompletionException("Xintinglei identity binding response is not valid JSON", exception);
        }

        if (response.has("linked") && !response.get("linked").getAsBoolean()) {
            return null;
        }
        if (!response.has("java_uuid") || !response.has("java_username")) {
            throw new CompletionException(new IOException(
                    "Xintinglei identity binding response is missing java_uuid or java_username"));
        }

        UUID javaId = UUID.fromString(response.get("java_uuid").getAsString());
        String username = response.get("java_username").getAsString();
        if (username.isBlank() || username.length() > 16) {
            throw new CompletionException(new IOException(
                    "Xintinglei identity binding response has an invalid Java username"));
        }
        return LinkedPlayer.of(username, javaId, bedrockId);
    }

    private void cache(UUID bedrockId, LinkedPlayer player, long ttlSeconds) {
        CacheEntry entry = new CacheEntry(player, Instant.now().plusSeconds(ttlSeconds));
        bedrockCache.put(bedrockId, entry);
        if (player != null) {
            javaCache.put(player.getJavaUniqueId(), entry);
        }
    }

    @Override
    public @NonNull CompletableFuture<Boolean> isLinkedPlayer(@NonNull UUID playerId) {
        if (playerId.getMostSignificantBits() == 0) {
            return getLinkedPlayer(playerId).thenApply(player -> player != null);
        }

        CacheEntry cached = javaCache.get(playerId);
        return CompletableFuture.completedFuture(cached != null && !cached.expired());
    }

    @Override
    public @NonNull CompletableFuture<Void> linkPlayer(
            @NonNull UUID bedrockId,
            @NonNull UUID javaId,
            @NonNull String username) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Bindings are managed by the Xintinglei identity service"));
    }

    @Override
    public @NonNull CompletableFuture<Void> unlinkPlayer(@NonNull UUID javaId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Bindings are managed by the Xintinglei identity service"));
    }

    @Override
    public @NonNull CompletableFuture<?> createLinkRequest(
            @NonNull UUID javaId,
            @NonNull String javaUsername,
            @NonNull String bedrockUsername) {
        return CompletableFuture.completedFuture(LinkRequestResult.UNKNOWN_ERROR);
    }

    @Override
    public @NonNull CompletableFuture<LinkRequestResult> verifyLinkRequest(
            @NonNull UUID bedrockId,
            @NonNull String javaUsername,
            @NonNull String bedrockUsername,
            @NonNull String code) {
        return CompletableFuture.completedFuture(LinkRequestResult.UNKNOWN_ERROR);
    }

    @Override
    public boolean isAllowLinking() {
        return false;
    }

    @Override
    public void stop() {
        bedrockCache.clear();
        javaCache.clear();
        super.stop();
    }

    private static void validateConfig(XintingleiLinkConfig config) {
        if (config.bindingEndpoint() == null || !config.bindingEndpoint().contains("{xuid}")) {
            throw new IllegalArgumentException("bindingEndpoint must contain the {xuid} placeholder");
        }
        URI endpoint = URI.create(config.bindingEndpoint().replace("{xuid}", "1"));
        String scheme = endpoint.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
                && !("http".equalsIgnoreCase(scheme) && isLoopback(endpoint.getHost()))) {
            throw new IllegalArgumentException("endpoint must use HTTPS unless it is loopback HTTP");
        }
        if (config.timeoutMillis() < 100 || config.timeoutMillis() > 30_000) {
            throw new IllegalArgumentException("timeoutMillis must be between 100 and 30000");
        }
        if (config.cacheTtlSeconds() < 0 || config.negativeCacheTtlSeconds() < 0) {
            throw new IllegalArgumentException("cache TTL values cannot be negative");
        }
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static final class CacheEntry {
        private final LinkedPlayer player;
        private final Instant expiresAt;

        private CacheEntry(LinkedPlayer player, Instant expiresAt) {
            this.player = player;
            this.expiresAt = expiresAt;
        }

        private LinkedPlayer player() {
            return player;
        }

        boolean expired() {
            return !expiresAt.isAfter(Instant.now());
        }
    }
}
