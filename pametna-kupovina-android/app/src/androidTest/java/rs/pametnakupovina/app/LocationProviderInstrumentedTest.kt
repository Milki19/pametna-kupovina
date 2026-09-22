package rs.pametnakupovina.app

import android.Manifest
import android.location.Location
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.tasks.Tasks
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import rs.pametnakupovina.app.location.FusedLocationProvider

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LocationProviderInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var locationProvider: FusedLocationProvider
    @Inject lateinit var fusedLocationClient: FusedLocationProviderClient

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val packageName
        get() = instrumentation.targetContext.packageName

    @Before
    fun prepare() {
        runShellCommand(
            "pm grant $packageName ${Manifest.permission.ACCESS_COARSE_LOCATION}"
        )
        runShellCommand(
            "pm grant $packageName ${Manifest.permission.ACCESS_FINE_LOCATION}"
        )
        runShellCommand(
            "appops set $packageName android:mock_location allow"
        )
        runShellCommand(
            "appops set $packageName android:fine_location allow"
        )
        runShellCommand(
            "appops set $packageName android:coarse_location allow"
        )
        hiltRule.inject()
    }

    @After
    fun cleanUp() {
        Tasks.await(fusedLocationClient.setMockMode(false))
        runShellCommand(
            "appops set $packageName android:mock_location default"
        )
        runShellCommand(
            "appops set $packageName android:fine_location default"
        )
        runShellCommand(
            "appops set $packageName android:coarse_location default"
        )
    }

    @Test
    fun fusedProviderReturnsEmulatorValjevoLocation() = runBlocking {
        val activityScenario = ActivityScenario.launch(
            ForegroundTestActivity::class.java
        )
        val valjevo = Location("instrumentation").apply {
            latitude = 44.2740
            longitude = 19.8800
            accuracy = 3f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try {
            Tasks.await(fusedLocationClient.setMockMode(true))
            Tasks.await(fusedLocationClient.setMockLocation(valjevo))

            val coordinates = locationProvider.currentLocation()

            assertEquals(44.2740, coordinates.latitude, 0.01)
            assertEquals(19.8800, coordinates.longitude, 0.01)
        } finally {
            activityScenario.close()
        }
    }

    private fun runShellCommand(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { stream ->
            stream.readBytes()
        }
    }
}
