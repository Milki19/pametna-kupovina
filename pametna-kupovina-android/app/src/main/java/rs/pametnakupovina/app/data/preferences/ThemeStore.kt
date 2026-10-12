package rs.pametnakupovina.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import rs.pametnakupovina.app.ui.theme.ThemeChoice

private val Context.themeDataStore by preferencesDataStore(name = "theme")
private val THEME = stringPreferencesKey("theme")

/** Tema izabrana u Meniju; bez izbora prati telefon. */
@Singleton
class ThemeStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val choice: Flow<ThemeChoice> = context.themeDataStore.data.map { preferences ->
        ThemeChoice.entries.firstOrNull { it.name == preferences[THEME] } ?: ThemeChoice.SYSTEM
    }

    suspend fun choose(choice: ThemeChoice) {
        context.themeDataStore.edit { it[THEME] = choice.name }
    }
}
