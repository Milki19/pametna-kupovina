package rs.pametnakupovina.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint the application maps has a rule, and the rule is the one
 * meant for it. A new endpoint without a rule is closed and fails here, so
 * it cannot ship open by accident — nor closed without anyone noticing.
 */
@SpringBootTest(properties = "price-import.minimum-snapshot-date=")
@Testcontainers
class AccessRulesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyMappedEndpointHasTheRuleMeantForIt() {
        List<String> unlisted = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping
                : handlerMapping.getHandlerMethods().entrySet()) {
            for (String pattern : mapping.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }

                String path = pattern.replaceAll("\\{[^}]+}", "7");

                for (var method : mapping.getKey().getMethodsCondition().getMethods()) {
                    AccessRules.Access access = AccessRules.of(method.name(), path);
                    String where = method + " " + pattern;

                    if (access == AccessRules.Access.DENIED) {
                        unlisted.add(where);
                    } else if (path.startsWith("/api/v1/imports/")) {
                        assertThat(access).as(where).isEqualTo(AccessRules.Access.ADMIN);
                    } else if (path.matches("/api/v1/(shopping-lists|accounts|receipts|loyalty-cards)(/.*)?")) {
                        assertThat(access).as(where).isEqualTo(AccessRules.Access.DEVICE);
                    }
                }
            }
        }

        assertThat(unlisted).as("endpoints without an access rule").isEmpty();
    }

    @Test
    void geocodingReviewIsForTheOwnerAndNearbyShopsForEveryone() {
        assertThat(AccessRules.of("GET", "/api/v1/stores/geocoding-review-queue"))
                .isEqualTo(AccessRules.Access.ADMIN);
        assertThat(AccessRules.of("POST", "/api/v1/stores/4/geocoding-results"))
                .isEqualTo(AccessRules.Access.ADMIN);
        assertThat(AccessRules.of("GET", "/api/v1/stores/nearby"))
                .isEqualTo(AccessRules.Access.PUBLIC);
        assertThat(AccessRules.of("POST", "/api/v1/products/9/reports"))
                .isEqualTo(AccessRules.Access.DEVICE_OPTIONAL);
    }
}
