package rs.pametnakupovina.backend;

import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.account.AccountRepository;
import rs.pametnakupovina.backend.security.DeviceCaller;
import rs.pametnakupovina.backend.security.DeviceSessionRepository;
import rs.pametnakupovina.backend.security.SessionTokens;

/**
 * Services take the phone the security filter proved; a test names its phone
 * by a made-up number instead, the way an app up to 1.8 did.
 */
@Component
public class TestCallers {

    private final AccountRepository accountRepository;
    private final DeviceSessionRepository sessionRepository;

    public TestCallers(
            AccountRepository accountRepository,
            DeviceSessionRepository sessionRepository
    ) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
    }

    public DeviceCaller caller(String deviceToken) {
        String hash = SessionTokens.sha256(deviceToken.strip());
        long accountId = accountRepository.forDevice(hash);
        return new DeviceCaller(
                accountId,
                sessionRepository.findDevice(hash).orElseThrow().id(),
                hash
        );
    }

    public long account(String deviceToken) {
        return caller(deviceToken).accountId();
    }
}
