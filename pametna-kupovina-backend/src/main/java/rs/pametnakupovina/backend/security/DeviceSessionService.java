package rs.pametnakupovina.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.account.AccountRepository;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A phone proves who it is with a short-lived access token and keeps it
 * fresh with a refresh token that works once. Each is only as good as the
 * row behind it, so logging out or removing a phone ends access at once.
 *
 * <p>The phone's old random number (X-Client-Token, up to app 1.8) is
 * exchanged exactly once for a session and then retired. Until an old app is
 * updated it may keep using that number directly; see
 * {@link #callerForLegacyToken}.
 */
@Service
public class DeviceSessionService {

    static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private static final int LONGEST_DEVICE_NAME = 100;

    private final DeviceSessionRepository repository;
    private final AccountRepository accountRepository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;
    private final Duration accessTtl;
    private final Duration refreshIdleTtl;
    private final boolean legacyTokenAccepted;
    private final Clock clock;

    public DeviceSessionService(
            DeviceSessionRepository repository,
            AccountRepository accountRepository,
            ShoppingListClientTokenPolicy clientTokenPolicy,
            @Value("${auth.access-token-ttl:15m}") Duration accessTtl,
            @Value("${auth.refresh-token-idle-ttl:180d}") Duration refreshIdleTtl,
            @Value("${auth.legacy-client-token.enabled:true}") boolean legacyTokenAccepted,
            Optional<Clock> clock
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.clientTokenPolicy = clientTokenPolicy;
        this.accessTtl = accessTtl;
        this.refreshIdleTtl = refreshIdleTtl;
        this.legacyTokenAccepted = legacyTokenAccepted;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * A new phone, or an old one moving from its number to a session. A
     * number that has already been exchanged opens nothing — except when the
     * session it opened was never used, which means the answer to the
     * exchange was lost on the way and the phone is asking again.
     */
    @Transactional
    public SessionGrant open(String deviceToken, String deviceName) {
        String hash = clientTokenPolicy.validateAndHash(deviceToken);
        repository.deleteExpiredSessions();

        DeviceSessionRepository.Device device = repository.findDevice(hash)
                .orElseGet(() -> {
                    accountRepository.forDevice(hash);
                    return repository.findDevice(hash).orElseThrow();
                });

        if (device.retired()) {
            boolean answerWasLost = repository.lockSessionOfDevice(device.id())
                    .filter(session -> !session.pairUsed() && session.neverRefreshed())
                    .isPresent();

            if (!answerWasLost) {
                throw rejected("Ovaj telefon već ima sesiju. Otvori aplikaciju ponovo.");
            }
        }

        repository.retireLegacyToken(device.id(), cleanName(deviceName));
        DeviceSessionRepository.IssuedTokens tokens = issue();
        repository.replaceSession(device.id(), tokens);

        return grant(tokens, device.id());
    }

    /**
     * A refresh token works once. The one before it is still honoured while
     * the pair it was swapped for has not been used — the phone never got the
     * answer — and after that it can only be a copy, so the session ends.
     * The refusal must not undo that, hence no rollback for it.
     */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SessionGrant refresh(String refreshToken) {
        String hash = SessionTokens.hashOrNull(refreshToken, SessionTokens.REFRESH_PREFIX);

        if (hash == null) {
            throw rejected("Sesija nije ispravna.");
        }

        Instant now = clock.instant();
        Optional<DeviceSessionRepository.Session> current = repository.lockByRefreshHash(hash);

        if (current.isPresent()) {
            return rotate(current.get(), hash, now);
        }

        Optional<DeviceSessionRepository.Session> previous = repository.lockByPreviousRefreshHash(hash);

        if (previous.isPresent() && !previous.get().pairUsed()) {
            return rotate(previous.get(), hash, now);
        }

        // Stari token posle upotrebe novog: neko drugi ga ima.
        previous.ifPresent(session -> repository.deleteSession(session.id()));
        throw rejected("Sesija je istekla. Otvori aplikaciju ponovo.");
    }

    /** Odjava: telefon napušta nalog, a sesija nestaje sa njim. */
    @Transactional
    public void signOut(DeviceCaller caller) {
        repository.removeDevice(caller.accountId(), caller.deviceId());
    }

    public List<DeviceSummary> devices(DeviceCaller caller) {
        return repository.devices(caller.accountId()).stream()
                .map(device -> new DeviceSummary(
                        device.id(),
                        device.name(),
                        device.firstSeenAt(),
                        device.lastSeenAt(),
                        device.id() == caller.deviceId()
                ))
                .toList();
    }

    /** Uklanjanje izgubljenog telefona; njegova sesija prestaje odmah. */
    @Transactional
    public void removeDevice(DeviceCaller caller, long deviceId) {
        if (!repository.removeDevice(caller.accountId(), deviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Telefon nije na ovom nalogu.");
        }
    }

    public Optional<DeviceCaller> callerForAccessToken(String accessToken) {
        String hash = SessionTokens.hashOrNull(accessToken, SessionTokens.ACCESS_PREFIX);
        return hash == null ? Optional.empty() : repository.callerForAccessHash(hash);
    }

    /**
     * Apps up to 1.8 send their number with every request. That still works
     * while the setting allows it, for as long as the phone has not moved to
     * a session: after that the number is retired and opens nothing.
     */
    @Transactional
    public Optional<DeviceCaller> callerForLegacyToken(String clientToken) {
        if (!legacyTokenAccepted) {
            return Optional.empty();
        }

        String hash = clientTokenPolicy.validateAndHash(clientToken);
        Optional<DeviceSessionRepository.Device> known = repository.findDevice(hash);

        if (known.isPresent() && known.get().retired()) {
            return Optional.empty();
        }

        long accountId = accountRepository.forDevice(hash);
        return repository.findDevice(hash)
                .map(device -> new DeviceCaller(accountId, device.id(), hash));
    }

    private SessionGrant rotate(DeviceSessionRepository.Session session, String spentHash, Instant now) {
        if (session.expired(now)) {
            repository.deleteSession(session.id());
            throw rejected("Sesija je istekla. Otvori aplikaciju ponovo.");
        }

        DeviceSessionRepository.IssuedTokens tokens = issue();
        repository.rotate(session.id(), spentHash, tokens);
        return grant(tokens, session.deviceId());
    }

    private DeviceSessionRepository.IssuedTokens issue() {
        return DeviceSessionRepository.IssuedTokens.issue(clock.instant(), accessTtl, refreshIdleTtl);
    }

    private SessionGrant grant(DeviceSessionRepository.IssuedTokens tokens, long deviceId) {
        return new SessionGrant(
                tokens.accessToken(),
                accessTtl.toSeconds(),
                tokens.refreshToken(),
                deviceId
        );
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        String stripped = name.strip();
        return stripped.length() > LONGEST_DEVICE_NAME
                ? stripped.substring(0, LONGEST_DEVICE_NAME)
                : stripped;
    }

    /** 401 sa zaglavljem po kome aplikacija zna da treba nova sesija. */
    static ResponseStatusException rejected(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE);
                return headers;
            }
        };
    }

    public record SessionGrant(
            String accessToken,
            long accessExpiresInSeconds,
            String refreshToken,
            long deviceId
    ) {
    }

    public record DeviceSummary(
            long id,
            String name,
            Instant firstSeenAt,
            Instant lastSeenAt,
            boolean current
    ) {
    }
}
