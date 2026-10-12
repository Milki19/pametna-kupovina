package rs.pametnakupovina.app.data.preferences

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Šifruje tajne (broj telefona i tokene sesije) pre upisa u datoteku. Ključ
 * je u Android Keystore-u i ne može da se pročita ni iznese sa telefona, pa
 * kopija datoteke bez tog telefona ne otvara nalog.
 */
internal object KeystoreBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "client_identity"
    private const val PREFIX = "k1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * Ako Keystore na ovom telefonu ne radi, vrednost ostaje nešifrovana, kao
     * do verzije 2.0.2: bolje to nego aplikacija koja ne može da sačuva sesiju.
     */
    fun seal(plain: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: newKey())
        PREFIX + Base64.encodeToString(
            cipher.iv + cipher.doFinal(plain.toByteArray()),
            Base64.NO_WRAP
        )
    } catch (_: GeneralSecurityException) {
        plain
    } catch (_: ProviderException) {
        plain
    }

    /**
     * Vrednost bez prefiksa upisala je starija verzija i čita se kakva jeste.
     * `null` znači da ključa više nema ili da vrednost nije njegova, pa je
     * tajna izgubljena; prolazna greška Keystore-a se baca dalje, da telefon
     * zbog nje ne bi počeo kao nov.
     */
    fun open(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        val key = key() ?: return null

        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (_: AEADBadTagException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun key(): SecretKey? = KeyStore.getInstance(KEYSTORE)
        .apply { load(null) }
        .getKey(ALIAS, null) as? SecretKey

    private fun newKey(): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }
        .generateKey()
}
