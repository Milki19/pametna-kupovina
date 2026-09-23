package rs.pametnakupovina.backend.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
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

    /** Whether anyone has signed in on this account yet. */
    public boolean isSignedIn(long accountId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM app.account_identity
                            WHERE account_id = :accountId
                        )
                        """)
                .param("accountId", accountId)
                .query(Boolean.class)
                .single();
    }

    public void attachIdentity(
            long accountId,
            String provider,
            String subject
    ) {
        jdbcClient.sql("""
                        INSERT INTO app.account_identity (
                            account_id, provider, subject
                        )
                        VALUES (:accountId, :provider, :subject)
                        ON CONFLICT (provider, subject) DO NOTHING
                        """)
                .param("accountId", accountId)
                .param("provider", provider)
                .param("subject", subject)
                .update();
    }

    /**
     * Signing in on a phone that already has lists of its own, or joining a
     * household: the phone and everything it made join the other account,
     * and the empty one it came from is dropped. Nothing a shopper wrote is
     * left behind — the account's rows cascade away with it, so every table
     * that belongs to an account has to be moved here first. A receipt or a
     * card the other account already has stays there once.
     */
    @Transactional
    public void moveEverything(long fromAccountId, long toAccountId) {
        if (fromAccountId == toAccountId) {
            return;
        }

        for (String move : List.of(
                "UPDATE app.shopping_list SET account_id = :to WHERE account_id = :from",
                "UPDATE app.account_device SET account_id = :to WHERE account_id = :from",
                "UPDATE app.account_identity SET account_id = :to WHERE account_id = :from",
                """
                UPDATE app.receipt AS moved SET account_id = :to
                 WHERE moved.account_id = :from
                   AND NOT EXISTS (
                       SELECT 1 FROM app.receipt AS kept
                       WHERE kept.account_id = :to
                         AND kept.verification_key = moved.verification_key
                   )
                """,
                """
                UPDATE app.loyalty_card AS moved SET account_id = :to
                 WHERE moved.account_id = :from
                   AND NOT EXISTS (
                       SELECT 1 FROM app.loyalty_card AS kept
                       WHERE kept.account_id = :to
                         AND kept.card_number = moved.card_number
                   )
                """
        )) {
            jdbcClient.sql(move)
                    .param("to", toAccountId)
                    .param("from", fromAccountId)
                    .update();
        }

        jdbcClient.sql("""
                        DELETE FROM app.account
                         WHERE id = :from
                        """)
                .param("from", fromAccountId)
                .update();
    }

    /** A new invite replaces the old one: only the last code shown works. */
    public void saveInvite(long accountId, String code, Duration validFor) {
        jdbcClient.sql("""
                        UPDATE app.account
                           SET invite_code_hash = encode(sha256(convert_to(:code, 'UTF8')), 'hex'),
                               invite_expires_at = NOW() + make_interval(secs => :seconds)
                         WHERE id = :accountId
                        """)
                .param("code", code)
                .param("seconds", validFor.toSeconds())
                .param("accountId", accountId)
                .update();
    }

    /** The account that invited, if the code is still good; it works once. */
    public Optional<Long> useInvite(String code) {
        return jdbcClient.sql("""
                        UPDATE app.account
                           SET invite_code_hash = NULL,
                               invite_expires_at = NULL
                         WHERE invite_code_hash = encode(sha256(convert_to(:code, 'UTF8')), 'hex')
                           AND invite_expires_at > NOW()
                        RETURNING id
                        """)
                .param("code", code)
                .query(Long.class)
                .optional();
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
