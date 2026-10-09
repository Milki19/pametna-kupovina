package rs.pametnakupovina.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import rs.pametnakupovina.app.ui.PametnaKupovinaApp
import rs.pametnakupovina.app.ui.theme.PametnaKupovinaTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Račun (screenshot ili PDF) podeljen iz aplikacije trgovine, dok se ne zavede. */
    private var sharedReceipt by mutableStateOf<Uri?>(null)

    /** Proizvod iz obaveštenja o pojeftinjenju, dok se ne otvori. */
    private var openedProduct by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Posle okretanja ekrana isti račun se ne šalje ponovo.
        if (savedInstanceState == null) {
            sharedReceipt = intent.sharedFile()
            openedProduct = intent.productToOpen()
        }
        setContent {
            PametnaKupovinaTheme {
                PametnaKupovinaApp(
                    sharedReceipt = sharedReceipt,
                    onSharedReceiptTaken = { sharedReceipt = null },
                    openedProduct = openedProduct,
                    onOpenedProductTaken = { openedProduct = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedFile()?.let { sharedReceipt = it }
        intent.productToOpen()?.let { openedProduct = it }
    }

    private fun Intent.productToOpen(): Long? =
        getLongExtra(EXTRA_PRODUCT, 0L).takeIf { it > 0L }

    private fun Intent.sharedFile(): Uri? =
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }

    companion object {
        const val EXTRA_PRODUCT = "rs.pametnakupovina.app.PRODUCT"
    }
}
