package rs.pametnakupovina.backend.account;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Every request is counted per address; writes also per account. */
@Configuration
public class AccountWebConfiguration implements WebMvcConfigurer {

    private final WriteRateLimitInterceptor writeRateLimitInterceptor;
    private final AddressRateLimitInterceptor addressRateLimitInterceptor;

    public AccountWebConfiguration(
            WriteRateLimitInterceptor writeRateLimitInterceptor,
            AddressRateLimitInterceptor addressRateLimitInterceptor
    ) {
        this.writeRateLimitInterceptor = writeRateLimitInterceptor;
        this.addressRateLimitInterceptor = addressRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(addressRateLimitInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(writeRateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/shopping-lists/**",
                        "/api/v1/products/match-decisions/**",
                        "/api/v1/products/*/reports",
                        "/api/v1/accounts/**",
                        "/api/v1/receipts/**",
                        "/api/v1/loyalty-cards/**"
                );
    }
}
