package rs.pametnakupovina.backend.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * A shopper's things belong to an account, and a phone is only one way into
 * it. Until someone signs in the account holds nothing about them: it is the
 * random number their phone made, one layer removed, so that a second phone
 * can later join the same account instead of starting empty.
 */
@Repository
public class AccountRepository {

    private final JdbcClient jdbcClient;

    public AccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * The account this phone belongs to, made on first sight.
     *
     * <p>Two first requests from the same new phone can arrive at once, so
     * the unique index on the phone decides which one wins and the account is
     * read back afterwards. The loser's empty account row stays behind — it
     * holds nothing and belongs to nobody, and paying for a lock on every
     * request to avoid it would cost more than it saves.
     */
    public long forDevice(String clientTokenHash) {
        Optional<Long> known = jdbcClient.sql("""
                        UPDATE app.account_device
                           SET last_seen_at = NOW()
                         WHERE client_token_hash = :hash
                        RETURNING account_id
                        """)
                .param("hash", clientTokenHash)
                .query(Long.class)
                .optional();

        if (known.isPresent()) {
            return known.get();
        }

        long accountId = jdbcClient.sql("""
                        INSERT INTO app.account DEFAULT VALUES
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.account_device (
                            account_id, client_token_hash
                        )
                        VALUES (:accountId, :hash)
                        ON CONFLICT (client_token_hash) DO NOTHING
                        """)
                .param("accountId", accountId)
                .param("hash", clientTokenHash)
                .update();

        return jdbcClient.sql("""
                        SELECT account_id
                        FROM app.account_device
                        WHERE client_token_hash = :hash
                        """)
                .param("hash", clientTokenHash)
                .query(Long.class)
                .single();
    }

    /** The account an identity already belongs to, if it has been seen. */
    public Optional<Long> findByIdentity(String provider, String subject) {
        return jdbcClient.sql("""
                        SELECT account_id
                        FROM app.account_identity
                        WHERE provider = :provider
                          AND subject = :subject
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .query(Long.class)
                .optional();
    }
}
