package rs.pametnakupovina.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fajl veći od onoga što server prima (1 MB), i posle smanjivanja. */
class ReceiptFileTooLarge : Exception()

/** Izabrano nije ni slika ni PDF. */
class NotAReceiptFile : Exception()

/** Fajl spreman za slanje: sadržaj i vrsta. */
class ReceiptFile(val bytes: ByteArray, val mimeType: String)

/**
 * Digitalni račun iz aplikacije trgovine (screenshot ili PDF) stoji na istom
 * telefonu, pa ga kamera ne može uhvatiti. Fajl se šalje serveru, koji pročita
 * QR kod; ovde se samo pripremi da stane u ono što server prima.
 */
@Singleton
class ReceiptFiles @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun read(uri: Uri): ReceiptFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri).orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw NotAReceiptFile()

        when {
            mimeType == "application/pdf" || bytes.startsWith("%PDF") -> {
                if (bytes.size > MOST_BYTES) throw ReceiptFileTooLarge()
                ReceiptFile(bytes, "application/pdf")
            }
            mimeType.startsWith("image/") || mimeType.isEmpty() -> smallEnough(bytes, mimeType)
            else -> throw NotAReceiptFile()
        }
    }

    /**
     * Screenshot telefona je obično manji od 1 MB. Veći se prepakuje u JPEG
     * pune veličine, a smanjuje se tek ako ni to ne stane: gust QR računa
     * traži što više tačaka.
     */
    private fun smallEnough(bytes: ByteArray, mimeType: String): ReceiptFile {
        if (bytes.size <= MOST_BYTES && mimeType in SENT_AS_IS) {
            return ReceiptFile(bytes, mimeType)
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw NotAReceiptFile()
        var scaled = bitmap
        try {
            repeat(4) {
                val jpeg = ByteArrayOutputStream().also {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, it)
                }.toByteArray()
                if (jpeg.size <= MOST_BYTES) return ReceiptFile(jpeg, "image/jpeg")

                val next = Bitmap.createScaledBitmap(
                    scaled, scaled.width * 3 / 4, scaled.height * 3 / 4, true
                )
                if (scaled !== bitmap) scaled.recycle()
                scaled = next
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
        throw ReceiptFileTooLarge()
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private companion object {
        /** Caddy i server primaju do 1 MB; ostaje mesta za omot zahteva. */
        const val MOST_BYTES = 950_000
        val SENT_AS_IS = setOf("image/png", "image/jpeg")
    }
}
