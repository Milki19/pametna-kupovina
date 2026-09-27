package rs.pametnakupovina.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tokens are random and mean nothing by themselves; the database says whose
 * they are and until when. That makes revoking one a delete, and a leaked
 * database useless for signing in, because only hashes are kept.
 *
 * <p>The prefixes let a secret scanner (and a person reading a log) tell an
 * access token from a refresh token at a glance.
 */
public final class SessionTokens {

    public static final String ACCESS_PREFIX = "pka_";
    public static final String REFRESH_PREFIX = "pkr_";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LONGEST_TOKEN = 100;

    private SessionTokens() {
    }

    public static String newAccessToken() {
        return ACCESS_PREFIX + randomPart();
    }

    public static String newRefreshToken() {
        return REFRESH_PREFIX + randomPart();
    }

    /** Hex SHA-256 of the token as sent, or null for something no token looks like. */
    public static String hashOrNull(String token, String prefix) {
        if (token == null) {
            return null;
        }

        String stripped = token.strip();

        if (!stripped.startsWith(prefix) || stripped.length() > LONGEST_TOKEN) {
            return null;
        }

        return sha256(stripped);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 nije dostupan", exception);
        }
    }

    private static String randomPart() {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
}
