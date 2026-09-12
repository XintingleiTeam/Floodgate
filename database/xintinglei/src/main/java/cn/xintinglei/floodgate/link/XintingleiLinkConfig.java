package cn.xintinglei.floodgate.link;

import org.geysermc.floodgate.database.config.DatabaseConfig;

public final class XintingleiLinkConfig implements DatabaseConfig {
    private String bindingEndpoint;
    private String bearerToken;
    private int timeoutMillis = 3000;
    private long cacheTtlSeconds = 30;
    private long negativeCacheTtlSeconds = 0;

    public String bindingEndpoint() {
        return bindingEndpoint;
    }

    public String bearerToken() {
        return bearerToken;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public long cacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public long negativeCacheTtlSeconds() {
        return negativeCacheTtlSeconds;
    }
}
