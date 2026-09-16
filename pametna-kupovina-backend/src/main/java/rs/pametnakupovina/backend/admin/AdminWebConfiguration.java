package rs.pametnakupovina.backend.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminApiKeyInterceptor adminApiKeyInterceptor;

    public AdminWebConfiguration(
            AdminApiKeyInterceptor adminApiKeyInterceptor
    ) {
        this.adminApiKeyInterceptor = adminApiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiKeyInterceptor)
                .addPathPatterns(
                        "/api/v1/imports/**",
                        "/api/v1/stores/geocoding-review-queue",
                        "/api/v1/stores/*/geocoding-results",
                        "/api/v1/stores/*/geocoding-review"
                );
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // The owner's review page: look-alike products, reports and types.
        registry.addRedirectViewController("/admin", "/admin/index.html");
        registry.addRedirectViewController("/admin/", "/admin/index.html");
    }
}
