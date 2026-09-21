package rs.pametnakupovina.backend.account;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Counting applies where a shopper writes: their lists and their decisions. */
@Configuration
public class AccountWebConfiguration implements WebMvcConfigurer {

    private final WriteRateLimitInterceptor writeRateLimitInterceptor;

    public AccountWebConfiguration(
            WriteRateLimitInterceptor writeRateLimitInterceptor
    ) {
        this.writeRateLimitInterceptor = writeRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(writeRateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/shopping-lists/**",
                        "/api/v1/products/match-decisions/**",
                        "/api/v1/products/reports/**",
                        "/api/v1/receipts/**"
                );
    }
}
