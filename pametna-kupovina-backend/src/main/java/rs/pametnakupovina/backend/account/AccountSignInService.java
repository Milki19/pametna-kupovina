package rs.pametnakupovina.backend.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

import java.util.Optional;

/**
 * Signing in does not make a new place for a shopper's things; it says that
 * the place this phone already uses is theirs. Which is why the same person
 * on a second phone ends up with the lists from the first, instead of an
 * empty app and a shrug.
 */
@Service
public class AccountSignInService {

    private final AccountRepository accountRepository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;
    private final GoogleIdentityVerifier googleVerifier;

    public AccountSignInService(
            AccountRepository accountRepository,
            ShoppingListClientTokenPolicy clientTokenPolicy,
            GoogleIdentityVerifier googleVerifier
    ) {
        this.accountRepository = accountRepository;
        this.clientTokenPolicy = clientTokenPolicy;
        this.googleVerifier = googleVerifier;
    }

    public AccountState state(String clientToken) {
        long accountId = accountFor(clientToken);

        return new AccountState(accountRepository.isSignedIn(accountId));
    }

    @Transactional
    public AccountState signInWithGoogle(String clientToken, String idToken) {
        String subject = googleVerifier.subjectOf(idToken);
        long deviceAccount = accountFor(clientToken);

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

            return new AccountState(true);
        }

        // Isti čovek sa drugog telefona: telefon i sve što je na njemu
        // napravio prelaze na nalog koji već postoji.
        accountRepository.moveEverything(deviceAccount, known.get());

        return new AccountState(true);
    }

    private long accountFor(String clientToken) {
        return accountRepository.forDevice(
                clientTokenPolicy.validateAndHash(clientToken)
        );
    }

    /**
     * @param signedIn whether this phone's account has an identity; there is
     *                 deliberately nothing else here, because the server
     *                 keeps neither a name nor an address to report back
     */
    public record AccountState(boolean signedIn) {
    }
}
