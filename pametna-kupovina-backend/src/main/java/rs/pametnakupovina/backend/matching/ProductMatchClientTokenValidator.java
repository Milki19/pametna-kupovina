package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;

@Component
public class ProductMatchClientTokenValidator {

    private static final int MAX_CLIENT_TOKEN_LENGTH = 100;

    public String validateRequired(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            throw new IllegalArgumentException(
                    "clientToken ne sme biti prazan"
            );
        }

        String normalizedToken = clientToken.strip();

        if (normalizedToken.length() > MAX_CLIENT_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "clientToken ne sme biti duži od 100 znakova"
            );
        }

        return normalizedToken;
    }

    public String validateOptional(String clientToken) {
        return clientToken == null
                ? null
                : validateRequired(clientToken);
    }
}
