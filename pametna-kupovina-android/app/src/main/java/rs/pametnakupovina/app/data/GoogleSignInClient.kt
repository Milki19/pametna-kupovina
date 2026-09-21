package rs.pametnakupovina.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import javax.inject.Inject
import javax.inject.Singleton
import rs.pametnakupovina.app.BuildConfig

/** Korisnik je zatvorio Google-ov ekran; to nije greška i ne prijavljuje se. */
class SignInCancelled : Exception()

/** Na telefonu nema nijednog Google naloga da se ponudi. */
class NoGoogleAccount : Exception()

/**
 * Jedino što ovde izlazi je token koji ide serveru. Ime, slika i e-mail
 * adresa stižu u istom odgovoru i namerno se ne diraju — server ih ne traži
 * i politika privatnosti kaže da ih nema.
 */
@Singleton
class GoogleSignInClient @Inject constructor() {

    suspend fun idToken(activityContext: Context): String {
        val option = GetGoogleIdOption.Builder()
            // Ponudi i naloge kojima aplikacija nije ranije odobrena: prva
            // prijava bi inače imala prazan spisak naloga i izgledala pokvareno.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_CLIENT_ID)
            .build()

        val response = try {
            CredentialManager.create(activityContext).getCredential(
                activityContext,
                GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
            )
        } catch (cancelled: GetCredentialCancellationException) {
            throw SignInCancelled()
        } catch (missing: NoCredentialException) {
            throw NoGoogleAccount()
        } catch (failed: GetCredentialException) {
            throw IllegalStateException(
                "Google prijava nije uspela: ${failed.type}",
                failed
            )
        }

        val credential = response.credential

        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Google je vratio nepoznatu vrstu prijave.")
        }

        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
