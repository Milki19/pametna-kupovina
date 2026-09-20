package rs.pametnakupovina.backend.legal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Google Play will not take the app without a privacy policy anyone can open
 * without installing it, so both texts live at an address of their own.
 */
@Configuration
public class LegalPagesConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(
                "/privatnost", "/privatnost/index.html");
        registry.addRedirectViewController(
                "/privatnost/", "/privatnost/index.html");
        registry.addRedirectViewController("/uslovi", "/uslovi/index.html");
        registry.addRedirectViewController("/uslovi/", "/uslovi/index.html");
    }
}
