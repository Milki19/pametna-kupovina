package rs.pametnakupovina.backend.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * How long the server worked on an answer, and an ETag for the answers a
 * phone may keep (search and sales, see ProductController).
 */
@Configuration
public class ApiTimingAndEtagConfiguration {

    /** The paths whose answers a phone keeps for a while. */
    static final String[] KEPT_PATHS = {
            "/api/v1/products/search",
            "/api/v1/products/on-sale"
    };

    /**
     * After the five minutes the phone asks again with the ETag, and an
     * answer that has not changed comes back as 304 without its body.
     */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> keptAnswerEtag() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns(KEPT_PATHS);
        registration.setName("keptAnswerEtag");
        return registration;
    }

    /** First, so the time includes the session check. */
    @Bean
    FilterRegistrationBean<ServerTimingFilter> serverTiming() {
        FilterRegistrationBean<ServerTimingFilter> registration =
                new FilterRegistrationBean<>(new ServerTimingFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("serverTiming");
        return registration;
    }
}
