package rs.pametnakupovina.backend.account;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Signing in does not make a new place for a shopper's things; it says that
 * the place this phone already uses is theirs. Which is why the same person
 * on a second phone ends up with the lists from the first, instead of an
 * empty app and a shrug.
 */
@Service
public class AccountSignInService {

    private static final Duration INVITE_VALID_FOR = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final GoogleIdentityVerifier googleVerifier;

    public AccountSignInService(
            AccountRepository accountRepository,
            GoogleIdentityVerifier googleVerifier
    ) {
        this.accountRepository = accountRepository;
        this.googleVerifier = googleVerifier;
    }

    public AccountState state(DeviceCaller caller) {
        return accountRepository.state(caller.accountId());
    }

    /** Brisanje iz same aplikacije; vidi {@link AccountRepository#forget}. */
    public void delete(DeviceCaller caller) {
        accountRepository.forget(caller.clientTokenHash());
    }

    @Transactional
    public AccountState signInWithGoogle(DeviceCaller caller, String idToken) {
        String subject = googleVerifier.subjectOf(idToken);
        long deviceAccount = caller.accountId();

        Optional<Long> known = accountRepository.findByIdentity(
                GoogleIdentityVerifier.PROVIDER,
                subject
        );

        if (known.isEmpty()) {
            // Prva prijava: nalog koji telefon već koristi postaje njegov.
            accountRepository.attachIdentity(
                    deviceAccount,
                    GoogleIdentityVerifier.PROVIDER,
                    subject
            );

            return accountRepository.state(deviceAccount);
        }

        // Isti čovek sa drugog telefona: telefon i sve što je na njemu
        // napravio prelaze na nalog koji već postoji.
        accountRepository.moveEverything(deviceAccount, known.get());

        return accountRepository.state(known.get());
    }

    /**
     * Kod koji drugi telefon u domaćinstvu skenira da bi ušao u ovaj nalog.
     * Dovoljno dug da se ne pogađa, pa mu ne treba brojanje pokušaja.
     */
    public Invite invite(DeviceCaller caller) {
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        accountRepository.saveInvite(caller.accountId(), code, INVITE_VALID_FOR);

        return new Invite(code, INVITE_VALID_FOR.toMinutes());
    }

    /**
     * Telefon i sve što je na njemu napravio prelaze u nalog koji je pozvao —
     * isto kao prijava istim Google nalogom na drugom telefonu.
     */
    @Transactional
    public AccountState join(DeviceCaller caller, String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kod ne sme biti prazan.");
        }
        long household = accountRepository.useInvite(code.strip())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Kod je istekao ili je već iskorišćen. Zatraži novi."
                ));

        accountRepository.moveEverything(caller.accountId(), household);

        return accountRepository.state(household);
    }

    /**
     * @param signedIn  whether this phone's account has an identity; there is
     *                  deliberately no name or address here, because the
     *                  server keeps neither
     * @param household whether other phones share the account
     */
    public record AccountState(boolean signedIn, boolean household) {
    }

    /** @param validMinutes aplikacija 1.4 ga čita; ostaje dok je 1.4 kod ljudi */
    public record Invite(String code, long validMinutes) {
    }
}
