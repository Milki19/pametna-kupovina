package rs.pametnakupovina.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import rs.pametnakupovina.app.data.preferences.KeystoreBox

class KeystoreBoxInstrumentedTest {

    @Test
    fun aSecretIsUnreadableInTheFileAndComesBackTheSame() {
        val sealed = KeystoreBox.seal("refresh-token-123")

        assertNotEquals("refresh-token-123", sealed)
        assertEquals("refresh-token-123", KeystoreBox.open(sealed))
    }

    @Test
    fun aValueWrittenByAnOlderVersionIsReadAsItIs() {
        assertEquals("3f0c1a2e-plain", KeystoreBox.open("3f0c1a2e-plain"))
    }

    @Test
    fun aValueThisPhoneCannotDecryptIsLost() {
        val sealed = KeystoreBox.seal("refresh-token-123")

        assertNull(KeystoreBox.open(sealed.dropLast(4) + "AAAA"))
        assertNull(KeystoreBox.open("k1:short"))
    }
}
