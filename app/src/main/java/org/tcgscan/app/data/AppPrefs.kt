package org.tcgscan.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The one DataStore file named "settings". It is declared here and nowhere else: opening a second
 * `preferencesDataStore` over the same name crashes at runtime, so every reader and writer of these
 * preferences goes through [AppPrefs].
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

/**
 * The small user choices that outlive a session: the language a scan session starts with (FR-5)
 * and whether first-run onboarding has been completed or skipped (EC-1).
 *
 * Cheap to construct: the underlying DataStore is a process-wide singleton owned by the
 * application context, so several instances share one file safely.
 */
class AppPrefs(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.settingsDataStore

    private val data: Flow<Preferences> = store.data.catch { cause ->
        // An unreadable preferences file must not break a screen: read it as « aucun choix fait ».
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    /** Language chosen for scan sessions, `null` while the user has made no choice. */
    val defaultScanLang: Flow<String?> = data.map { it[KEY_DEFAULT_SCAN_LANG] }

    /** False until onboarding has been finished or skipped; `MainActivity` routes on it. */
    val onboardingDone: Flow<Boolean> = data.map { it[KEY_ONBOARDING_DONE] ?: false }

    suspend fun setDefaultScanLang(lang: String) {
        store.edit { it[KEY_DEFAULT_SCAN_LANG] = lang }
    }

    suspend fun setOnboardingDone(done: Boolean = true) {
        store.edit { it[KEY_ONBOARDING_DONE] = done }
    }

    private companion object {
        // Key names are persisted: never rename them.
        val KEY_DEFAULT_SCAN_LANG = stringPreferencesKey("default_scan_lang")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }
}
