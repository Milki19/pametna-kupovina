package rs.pametnakupovina.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rs.pametnakupovina.app.crash.rememberCrashes
import rs.pametnakupovina.app.data.ShoppingRepository
import rs.pametnakupovina.app.data.market.MarketStore

@HiltAndroidApp
class PametnaKupovinaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var marketStore: MarketStore

    @Inject
    lateinit var repository: ShoppingRepository

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        rememberCrashes()
        // The last known market first, then the server's word on it; offline
        // the app keeps writing amounts the way it did last time.
        startupScope.launch {
            marketStore.restore()
            runCatching { repository.accountState() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
