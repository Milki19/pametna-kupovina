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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Posle okretanja ekrana isti račun se ne šalje ponovo.
        if (savedInstanceState == null) sharedReceipt = intent.sharedFile()
        setContent {
            PametnaKupovinaTheme {
                PametnaKupovinaApp(
                    sharedReceipt = sharedReceipt,
                    onSharedReceiptTaken = { sharedReceipt = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedFile()?.let { sharedReceipt = it }
    }

    private fun Intent.sharedFile(): Uri? =
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
}
