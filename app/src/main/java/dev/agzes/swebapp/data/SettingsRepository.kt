package dev.agzes.swebapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val SELECTED_FOLDER_URI = stringPreferencesKey("selected_folder_uri")
        val LOCALHOST_ONLY = booleanPreferencesKey("localhost_only")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val PORT = intPreferencesKey("port")
        val SPA_MODE = booleanPreferencesKey("spa_mode")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val HOT_RELOAD = booleanPreferencesKey("hot_reload")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val SHOW_NOTIFICATION = booleanPreferencesKey("show_notification")
        val RESTRICT_WEB_FEATURES = booleanPreferencesKey("restrict_web_features")
        val INTERCEPT_CONSOLE = booleanPreferencesKey("intercept_console")
    }

    val selectedFolderUriFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_FOLDER_URI]
        }

    val localhostOnlyFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[LOCALHOST_ONLY] ?: true
        }

    val autoStartFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[AUTO_START] ?: false
        }

    val portFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[PORT] ?: 8080
        }

    val spaModeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SPA_MODE] ?: true
        }

    val batterySaverFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[BATTERY_SAVER] ?: false
        }

    val hotReloadFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HOT_RELOAD] ?: false
        }

    val themeModeFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[THEME_MODE] ?: 0
        }

    val showNotificationFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SHOW_NOTIFICATION] ?: true
        }

    val restrictWebFeaturesFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[RESTRICT_WEB_FEATURES] ?: true
        }

    val interceptConsoleFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[INTERCEPT_CONSOLE] ?: false
        }

    suspend fun saveSelectedFolderUri(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_FOLDER_URI] = uri
        }
    }

    suspend fun saveLocalhostOnly(localhostOnly: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOCALHOST_ONLY] = localhostOnly
        }
    }

    suspend fun saveAutoStart(autoStart: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_START] = autoStart
        }
    }

    suspend fun savePort(port: Int) {
        val validPort = port.coerceIn(1024, 65535)
        context.dataStore.edit { preferences ->
            preferences[PORT] = validPort
        }
    }

    suspend fun saveSpaMode(spaMode: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SPA_MODE] = spaMode
        }
    }

    suspend fun saveBatterySaver(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BATTERY_SAVER] = enabled
        }
    }

    suspend fun saveHotReload(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOT_RELOAD] = enabled
        }
    }

    suspend fun saveThemeMode(mode: Int) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE] = mode
        }
    }

    suspend fun saveShowNotification(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_NOTIFICATION] = show
        }
    }

    suspend fun saveRestrictWebFeatures(restrict: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[RESTRICT_WEB_FEATURES] = restrict
        }
    }

    suspend fun saveInterceptConsole(intercept: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[INTERCEPT_CONSOLE] = intercept
        }
    }
}
