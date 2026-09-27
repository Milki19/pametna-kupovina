package rs.pametnakupovina.backend.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Sessions and the phones they belong to; only hashes of tokens are stored. */
@Repository
public class DeviceSessionRepository {

    private final JdbcClient jdbcClient;

    public DeviceSessionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<Device> findDevice(String clientTokenHash) {
        return jdbcClient.sql("""
                        SELECT id, account_id, client_token_hash,
                               legacy_token_retired_at IS NOT NULL AS retired
                        FROM app.account_device
                        WHERE client_token_hash = :hash
                        """)
                .param("hash", clientTokenHash)
                .query((row, number) -> new Device(
                        row.getLong("id"),
                        row.getLong("account_id"),
                        row.getString("client_token_hash"),
                        row.getBoolean("retired")
                ))
                .optional();
    }

    /** From now on the phone's old number opens nothing; the session does. */
    public void retireLegacyToken(long deviceId, String name) {
        jdbcClient.sql("""
                        UPDATE app.account_device
                           SET legacy_token_retired_at = COALESCE(legacy_token_retired_at, NOW()),
                               name = COALESCE(:name, name),
                               last_seen_at = NOW()
                         WHERE id = :deviceId
                        """)
                .param("deviceId", deviceId)
                .param("name", name)
                .update();
    }

    /** One session per phone: a new one replaces whatever was there. */
    public void replaceSession(long deviceId, IssuedTokens tokens) {
        jdbcClient.sql("""
                        INSERT INTO app.device_session (
                            device_id,
                            access_token_hash, access_expires_at,
                            refresh_token_hash, refresh_expires_at
                        )
                        VALUES (:deviceId, :accessHash, :accessExpiresAt,
                                :refreshHash, :refreshExpiresAt)
                        ON CONFLICT (device_id) DO UPDATE
                           SET access_token_hash = EXCLUDED.access_token_hash,
                               access_expires_at = EXCLUDED.access_expires_at,
                               refresh_token_hash = EXCLUDED.refresh_token_hash,
                               refresh_expires_at = EXCLUDED.refresh_expires_at,
                               previous_refresh_token_hash = NULL,
                               current_pair_used = FALSE,
                               created_at = NOW(),
                               refreshed_at = NOW()
                        """)
                .param("deviceId", deviceId)
                .param("accessHash", tokens.accessHash())
                .param("accessExpiresAt", offset(tokens.accessExpiresAt()))
                .param("refreshHash", tokens.refreshHash())
                .param("refreshExpiresAt", offset(tokens.refreshExpiresAt()))
                .update();
    }

    /** The phone's session, locked until the transaction ends. */
    public Optional<Session> lockSessionOfDevice(long deviceId) {
        return lockSession("device_id = :value", deviceId);
    }

    public Optional<Session> lockByRefreshHash(String refreshHash) {
        return lockSession("refresh_token_hash = :value", refreshHash);
    }

    public Optional<Session> lockByPreviousRefreshHash(String refreshHash) {
        return lockSession("previous_refresh_token_hash = :value", refreshHash);
    }

    private Optional<Session> lockSession(String condition, Object value) {
        return jdbcClient.sql("""
                        SELECT id, device_id, refresh_token_hash, refresh_expires_at,
                               current_pair_used,
                               previous_refresh_token_hash IS NULL AS never_refreshed
                        FROM app.device_session
                        WHERE %s
                        FOR UPDATE
                        """.formatted(condition))
                .param("value", value)
                .query((row, number) -> new Session(
                        row.getLong("id"),
                        row.getLong("device_id"),
                        row.getString("refresh_token_hash"),
                        row.getObject("refresh_expires_at", OffsetDateTime.class).toInstant(),
                        row.getBoolean("current_pair_used"),
                        row.getBoolean("never_refreshed")
                ))
                .optional();
    }

    /** New pair; the refresh token just spent is remembered as the previous one. */
    public void rotate(long sessionId, String spentRefreshHash, IssuedTokens tokens) {
        jdbcClient.sql("""
                        UPDATE app.device_session
                           SET access_token_hash = :accessHash,
                               access_expires_at = :accessExpiresAt,
                               refresh_token_hash = :refreshHash,
                               refresh_expires_at = :refreshExpiresAt,
                               previous_refresh_token_hash = :spent,
                               current_pair_used = FALSE,
                               refreshed_at = NOW()
                         WHERE id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .param("accessHash", tokens.accessHash())
                .param("accessExpiresAt", offset(tokens.accessExpiresAt()))
                .param("refreshHash", tokens.refreshHash())
                .param("refreshExpiresAt", offset(tokens.refreshExpiresAt()))
                .param("spent", spentRefreshHash)
                .update();

        jdbcClient.sql("""
                        UPDATE app.account_device
                           SET last_seen_at = NOW()
                         WHERE id = (SELECT device_id FROM app.device_session WHERE id = :sessionId)
                        """)
                .param("sessionId", sessionId)
                .update();
    }

    public void deleteSession(long sessionId) {
        jdbcClient.sql("DELETE FROM app.device_session WHERE id = :id")
                .param("id", sessionId)
                .update();
    }

    /** Sessions nobody refreshed in time; their phones start over. */
    public void deleteExpiredSessions() {
        jdbcClient.sql("DELETE FROM app.device_session WHERE refresh_expires_at < NOW()")
                .update();
    }

    /** Who holds a live access token, if anyone. */
    public Optional<DeviceCaller> callerForAccessHash(String accessHash) {
        Optional<SessionCaller> found = jdbcClient.sql("""
                        SELECT session.id AS session_id,
                               session.current_pair_used,
                               device.id AS device_id,
                               device.account_id,
                               device.client_token_hash
                        FROM app.device_session AS session
                        JOIN app.account_device AS device ON device.id = session.device_id
                        WHERE session.access_token_hash = :hash
                          AND session.access_expires_at > NOW()
                        """)
                .param("hash", accessHash)
                .query((row, number) -> new SessionCaller(
                        row.getLong("session_id"),
                        row.getBoolean("current_pair_used"),
                        new DeviceCaller(
                                row.getLong("account_id"),
                                row.getLong("device_id"),
                                row.getString("client_token_hash")
                        )
                ))
                .optional();

        found.filter(caller -> !caller.pairUsed())
                .ifPresent(caller -> jdbcClient.sql("""
                                UPDATE app.device_session
                                   SET current_pair_used = TRUE
                                 WHERE id = :id
                                """)
                        .param("id", caller.sessionId())
                        .update());

        return found.map(SessionCaller::caller);
    }

    public List<AccountDevice> devices(long accountId) {
        return jdbcClient.sql("""
                        SELECT id, name, first_seen_at, last_seen_at
                        FROM app.account_device
                        WHERE account_id = :accountId
                        ORDER BY last_seen_at DESC, id
                        """)
                .param("accountId", accountId)
                .query((row, number) -> new AccountDevice(
                        row.getLong("id"),
                        row.getString("name"),
                        row.getObject("first_seen_at", OffsetDateTime.class).toInstant(),
                        row.getObject("last_seen_at", OffsetDateTime.class).toInstant()
                ))
                .list();
    }

    /**
     * The phone leaves the account and its session goes with it (the row
     * cascades). An account left with no phone and no sign-in could never be
     * reached again, so it goes too; one with a sign-in waits for the next.
     */
    public boolean removeDevice(long accountId, long deviceId) {
        int removed = jdbcClient.sql("""
                        DELETE FROM app.account_device
                         WHERE id = :deviceId
                           AND account_id = :accountId
                        """)
                .param("deviceId", deviceId)
                .param("accountId", accountId)
                .update();

        jdbcClient.sql("""
                        DELETE FROM app.account
                         WHERE id = :accountId
                           AND NOT EXISTS (SELECT 1 FROM app.account_device WHERE account_id = :accountId)
                           AND NOT EXISTS (SELECT 1 FROM app.account_identity WHERE account_id = :accountId)
                        """)
                .param("accountId", accountId)
                .update();

        return removed > 0;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    public record Device(long id, long accountId, String clientTokenHash, boolean retired) {
    }

    public record Session(
            long id,
            long deviceId,
            String refreshHash,
            Instant refreshExpiresAt,
            boolean pairUsed,
            boolean neverRefreshed
    ) {
        boolean expired(Instant now) {
            return !refreshExpiresAt.isAfter(now);
        }
    }

    public record AccountDevice(long id, String name, Instant firstSeenAt, Instant lastSeenAt) {
    }

    public record IssuedTokens(
            String accessToken,
            String accessHash,
            Instant accessExpiresAt,
            String refreshToken,
            String refreshHash,
            Instant refreshExpiresAt
    ) {
        static IssuedTokens issue(Instant now, Duration accessTtl, Duration refreshIdleTtl) {
            String access = SessionTokens.newAccessToken();
            String refresh = SessionTokens.newRefreshToken();
            return new IssuedTokens(
                    access, SessionTokens.sha256(access), now.plus(accessTtl),
                    refresh, SessionTokens.sha256(refresh), now.plus(refreshIdleTtl)
            );
        }
    }

    private record SessionCaller(long sessionId, boolean pairUsed, DeviceCaller caller) {
    }
}
