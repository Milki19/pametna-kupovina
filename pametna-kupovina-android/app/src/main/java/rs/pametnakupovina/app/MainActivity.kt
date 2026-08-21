package rs.pametnakupovina.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import rs.pametnakupovina.app.ui.PametnaKupovinaApp
import rs.pametnakupovina.app.ui.theme.PametnaKupovinaTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PametnaKupovinaTheme {
                PametnaKupovinaApp()
            }
        }
    }
}
