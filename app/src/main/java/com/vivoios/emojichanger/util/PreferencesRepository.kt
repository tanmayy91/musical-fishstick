package com.vivoios.emojichanger.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.vivoios.emojichanger.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Repository for persisting and reading app preferences via DataStore.
 */
class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SELECTED_PACK_ID = stringPreferencesKey("selected_pack_id")
        val AUTO_REAPPLY_ON_BOOT = booleanPreferencesKey("auto_reapply_on_boot")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEYBOARD_ENABLED = booleanPreferencesKey("keyboard_enabled")
        val ACCESSIBILITY_ENABLED = booleanPreferencesKey("accessibility_enabled")
        val EMOJI_PREVIEW_ON_KEYBOARD = booleanPreferencesKey("emoji_preview_keyboard")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val AUTO_UPDATE_PACKS = booleanPreferencesKey("auto_update_packs")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            AppSettings(
                selectedPackId = prefs[Keys.SELECTED_PACK_ID],
                autoReapplyOnBoot = prefs[Keys.AUTO_REAPPLY_ON_BOOT] ?: true,
                darkModeEnabled = prefs[Keys.DARK_MODE] ?: true,
                keyboardEnabled = prefs[Keys.KEYBOARD_ENABLED] ?: false,
                accessibilityEnabled = prefs[Keys.ACCESSIBILITY_ENABLED] ?: false,
                showEmojiPreviewOnKeyboard = prefs[Keys.EMOJI_PREVIEW_ON_KEYBOARD] ?: true,
                hapticFeedbackEnabled = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
                autoUpdatePacks = prefs[Keys.AUTO_UPDATE_PACKS] ?: false,
                lastUpdateCheck = prefs[Keys.LAST_UPDATE_CHECK] ?: 0L
            )
        }

    suspend fun setSelectedPack(packId: String?) {
        context.dataStore.edit { prefs ->
            if (packId != null) prefs[Keys.SELECTED_PACK_ID] = packId
            else prefs.remove(Keys.SELECTED_PACK_ID)
        }
    }

    suspend fun setAutoReapplyOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_REAPPLY_ON_BOOT] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun setKeyboardEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEYBOARD_ENABLED] = enabled }
    }

    suspend fun setAccessibilityEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ACCESSIBILITY_ENABLED] = enabled }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setAutoUpdatePacks(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_UPDATE_PACKS] = enabled }
    }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = timestamp }
    }
}
