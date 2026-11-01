package rs.pametnakupovina.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.saleRadiusDataStore by preferencesDataStore(name = "sale_radius")
private val RADIUS = intPreferencesKey("radius_meters")

/** Krug na Akcijama: 1, 3 ili 10 km, ili svi lanci. Pamti se izbor. */
@Singleton
class SaleRadiusStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun radius(): SaleRadius =
        context.saleRadiusDataStore.data.map { preferences ->
            SaleRadius.entries.firstOrNull { it.meters == preferences[RADIUS] } ?: SaleRadius.DEFAULT
        }.first()

    suspend fun choose(radius: SaleRadius) {
        context.saleRadiusDataStore.edit { it[RADIUS] = radius.meters }
    }
}

/** [meters] 0 znači „Svi lanci": bez kruga, i bez lokacije na serveru. */
enum class SaleRadius(val meters: Int) {
    KM_1(1_000),
    KM_3(3_000),
    KM_10(10_000),
    ALL(0);

    val kilometres: Int get() = meters / 1_000

    companion object {
        val DEFAULT = KM_3
    }
}
