package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ShoppingListClientTokenPolicy {

    private static final int MAX_CLIENT_TOKEN_LENGTH = 100;

    public String validateAndHash(String clientToken) {
        if (clientToken == null || clientToken.isBlank()) {
            throw badRequest("X-Client-Token ne sme biti prazan");
        }

        String normalizedToken = clientToken.strip();

        if (normalizedToken.length() > MAX_CLIENT_TOKEN_LENGTH) {
            throw badRequest(
                    "X-Client-Token ne sme biti duži od 100 znakova"
            );
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            normalizedToken.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 nije dostupan",
                    exception
            );
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
