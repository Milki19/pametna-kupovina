package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class ShoppingListWebConfiguration implements WebMvcConfigurer {

    private final ListRefArgumentResolver listRefArgumentResolver;

    public ShoppingListWebConfiguration(ListRefArgumentResolver listRefArgumentResolver) {
        this.listRefArgumentResolver = listRefArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(listRefArgumentResolver);
    }
}
