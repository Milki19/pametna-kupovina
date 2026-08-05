package rs.pametnakupovina.backend.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "routing.osrm")
public class OsrmRoutingProperties {

    private boolean enabled;
    private String baseUrl = "https://router.project-osrm.org/table/v1";
    private String profile = "driving";
    private long publicPairCacheTtlSeconds = 86_400;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public long getPublicPairCacheTtlSeconds() {
        return publicPairCacheTtlSeconds;
    }

    public void setPublicPairCacheTtlSeconds(
            long publicPairCacheTtlSeconds
    ) {
        this.publicPairCacheTtlSeconds = publicPairCacheTtlSeconds;
    }
}
