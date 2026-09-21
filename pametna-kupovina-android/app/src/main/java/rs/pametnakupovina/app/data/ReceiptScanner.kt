package rs.pametnakupovina.app.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Korisnik je odustao od skeniranja; to nije greška. */
class ScanCancelled : Exception()

/** Skeniran je kod, ali nije sa fiskalnog računa. */
class NotAFiscalReceipt : Exception()

/**
 * Skeniranje otvara Google-ov ekran, pa slika nikad ne prođe kroz naš kod i
 * aplikaciji **ne treba dozvola za kameru**. To je jedno pitanje manje
 * korisniku i jedan podatak manje o kom politika privatnosti mora da govori.
 */
@Singleton
class ReceiptScanner @Inject constructor() {

    suspend fun verificationUrl(activityContext: Context): String {
        val scanner = GmsBarcodeScanning.getClient(
            activityContext,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )

        val value = try {
            scanner.startScan().await()
        } catch (failure: MlKitException) {
            // Pritisak na „nazad" ML Kit javlja kao grešku sa svojom šifrom,
            // ne kao otkazivanje. Korisnik koji se predomisli ne sme da dobije
            // crveno.
            if (failure.errorCode == MlKitException.CODE_SCANNER_CANCELLED) {
                throw ScanCancelled()
            }
            throw failure
        } ?: throw NotAFiscalReceipt()

        // Poreska uprava je jedina adresa koju server uopšte čita; ovde se
        // proverava samo da korisniku ne bi putovao uzalud kod sa kutije mleka.
        if (!value.startsWith("https://suf.purs.gov.rs/", ignoreCase = true)) {
            throw NotAFiscalReceipt()
        }

        return value
    }
}

private suspend fun Task<Barcode>.await(): String? = suspendCoroutine { waiting ->
    addOnSuccessListener { barcode -> waiting.resume(barcode?.rawValue) }
    addOnCanceledListener { waiting.resumeWithException(ScanCancelled()) }
    addOnFailureListener { failure -> waiting.resumeWithException(failure) }
}
