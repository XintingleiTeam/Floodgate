package cn.xintinglei.floodgate.bindgate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.handshake.HandshakeHandlers;
import org.geysermc.floodgate.util.BedrockData;
import org.slf4j.Logger;

/**
 * Displays a website one-time binding code to unlinked Floodgate players.
 *
 * <p>This class deliberately uses Floodgate's deprecated handshake API. It is isolated in this
 * plugin because Floodgate 3 removes that API; no account binding logic belongs in Floodgate
 * itself.</p>
 */
@SuppressWarnings("deprecation")
public final class XintingleiBindGate {
    private static final int MAX_RESPONSE_LENGTH = 16_384;

    private final Logger logger;
    private final Path dataDirectory;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private Config config;
    private int handlerId = -1;

    @Inject
    public XintingleiBindGate(Logger logger, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            config = loadConfig();
            HandshakeHandlers handlers = InstanceHolder.getHandshakeHandlers();
            if (handlers == null) {
                throw new IllegalStateException("Floodgate handshake handlers are not available");
            }
            handlerId = handlers.addHandshakeHandler(this::handleHandshake);
            if (handlerId < 0) {
                throw new IllegalStateException("Floodgate rejected the binding-code handshake handler");
            }
            logger.info("Xintinglei Bind Gate is ready; unlinked Bedrock players receive a one-time code");
        } catch (Exception exception) {
            logger.error("Xintinglei Bind Gate could not start; unlinked players will keep Floodgate's default message", exception);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (handlerId >= 0) {
            HandshakeHandlers handlers = InstanceHolder.getHandshakeHandlers();
            if (handlers != null) {
                handlers.removeHandshakeHandler(handlerId);
            }
            handlerId = -1;
        }
    }

    private void handleHandshake(HandshakeData data) {
        if (config == null || !data.isFloodgatePlayer() || data.getLinkedPlayer() != null) {
            return;
        }
        BedrockData bedrock = data.getBedrockData();
        if (bedrock == null || bedrock.getXuid() == null || bedrock.getXuid().isBlank()) {
            return;
        }

        try {
            CodeResult result = requestCode(bedrock.getXuid(), bedrock.getUsername());
            data.setDisconnectReason("尚未绑定新亭泪游戏角色。\n"
                    + "绑定码：" + result.code + "（5 分钟内有效）\n"
                    + "请登录并绑定角色：" + result.url);
        } catch (Exception exception) {
            logger.warn("Could not issue a Bedrock binding code for XUID {}: {}",
                    bedrock.getXuid(), exception.getMessage());
            data.setDisconnectReason("尚未绑定新亭泪游戏角色。\n"
                    + "绑定码服务暂时不可用，请稍后重试。\n"
                    + "绑定页面：" + config.linkUrl);
        }
    }

    private CodeResult requestCode(String xuid, String gamertag) throws IOException, InterruptedException {
        String requestBody = "{\"xuid\":" + jsonString(xuid)
                + ",\"gamertag\":" + jsonString(gamertag == null ? "" : gamertag) + "}";
        HttpRequest.Builder request = HttpRequest.newBuilder(config.codeEndpoint)
                .timeout(Duration.ofMillis(config.timeoutMillis))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (!config.bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + config.bearerToken);
        }

        HttpResponse<String> response = httpClient.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("identity service returned HTTP " + response.statusCode());
        }
        if (response.body().length() > MAX_RESPONSE_LENGTH) {
            throw new IOException("identity service response is too large");
        }

        try {
            JsonObject body = new JsonParser().parse(response.body()).getAsJsonObject();
            String code = body.get("code").getAsString();
            String url = body.has("url") ? body.get("url").getAsString() : config.linkUrl;
            if (!code.matches("[A-Z0-9-]{4,32}")) {
                throw new IOException("identity service returned an invalid code");
            }
            validateDisplayUrl(url);
            return new CodeResult(code, url);
        } catch (RuntimeException exception) {
            throw new IOException("identity service response is not a valid binding-code response", exception);
        }
    }

    private Config loadConfig() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.properties");
        if (Files.notExists(configPath)) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties")) {
                if (input == null) {
                    throw new IOException("Bundled config.properties is missing");
                }
                Files.copy(input, configPath);
            }
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        }
        URI endpoint = URI.create(properties.getProperty("codeEndpoint", "").trim());
        String url = properties.getProperty("linkUrl", "").trim();
        int timeout = Integer.parseInt(properties.getProperty("timeoutMillis", "3000").trim());
        validateServiceUrl(endpoint);
        validateDisplayUrl(url);
        if (timeout < 100 || timeout > 30_000) {
            throw new IllegalArgumentException("timeoutMillis must be between 100 and 30000");
        }
        return new Config(endpoint, properties.getProperty("bearerToken", "").trim(), timeout, url);
    }

    private static void validateServiceUrl(URI uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
                && !("http".equalsIgnoreCase(scheme) && isLoopback(uri.getHost()))) {
            throw new IllegalArgumentException("codeEndpoint must use HTTPS unless it is loopback HTTP");
        }
    }

    private static void validateDisplayUrl(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("linkUrl must use HTTPS");
        }
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\': escaped.append("\\\\"); break;
                case '\"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.append('\"').toString();
    }

    private static final class Config {
        private final URI codeEndpoint;
        private final String bearerToken;
        private final int timeoutMillis;
        private final String linkUrl;

        private Config(URI codeEndpoint, String bearerToken, int timeoutMillis, String linkUrl) {
            this.codeEndpoint = codeEndpoint;
            this.bearerToken = bearerToken;
            this.timeoutMillis = timeoutMillis;
            this.linkUrl = linkUrl;
        }
    }

    private static final class CodeResult {
        private final String code;
        private final String url;

        private CodeResult(String code, String url) {
            this.code = code;
            this.url = url;
        }
    }
}
