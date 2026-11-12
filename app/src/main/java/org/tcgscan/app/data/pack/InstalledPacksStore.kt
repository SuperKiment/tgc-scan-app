package org.tcgscan.app.data.pack

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.installedPacksDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "installed_packs",
)

/**
 * What is installed, from the manifest's point of view: per language the published version, card
 * count, archive size and `pack_schema` (docs/pack-format.md §6).
 *
 * This store is the app's answer to « une mise à jour est-elle disponible ? » (FR-23); the pack
 * files themselves are the answer to « puis-je scanner ? ». Only `PackInstaller` writes here, and
 * only after a successful atomic install, so a record always describes a pack that exists.
 */
class InstalledPacksStore(private val context: Context) {

    /** Every recorded pack, keyed by language code. Emits on every install, update and removal. */
    val installed: Flow<Map<String, InstalledPack>> = context.installedPacksDataStore.data
        .catch { cause ->
            // A corrupt or unreadable preferences file must not break the catalogue: treat it as
            // « rien d'installé » and let the user reinstall a pack.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::readAll)

    /**
     * The packs whose file is on disk, which is what scanning and the collection need. Re-evaluated
     * on every [installed] emission, so an install, update or removal from Settings is picked up.
     *
     * A record whose file vanished is dropped. A pack pushed by hand (docs/manual-debug.md) has no
     * record: one is derived from its `pack_meta` (`version` is `"local"`, `bytes` is the file size,
     * so replacing the file changes the record). A pushed file that is not a readable pack is skipped.
     * Blocking: collect it off the main thread.
     */
    val onDisk: Flow<Map<String, InstalledPack>> = installed.map { records ->
        val packs = LinkedHashMap<String, InstalledPack>()
        PackPaths.installedLangs(context).forEach { lang ->
            val pack = records[lang] ?: derivePack(lang)
            if (pack != null) packs[lang] = pack
        }
        packs
    }

    /** The record of one language, `null` when that pack is not installed. */
    fun pack(lang: String): Flow<InstalledPack?> = installed.map { it[lang] }

    /** One-shot read, for callers that are not Flow-driven (the installer and the guards). */
    suspend fun current(): Map<String, InstalledPack> = installed.first()

    /** Stores (or replaces) the record of [pack]; called once an install has been made atomic. */
    suspend fun record(pack: InstalledPack) {
        require(PackPaths.isValidLang(pack.lang)) { "Invalid pack language code: ${pack.lang}" }
        context.installedPacksDataStore.edit { prefs ->
            prefs[KEY_LANGS] = (prefs[KEY_LANGS] ?: emptySet()) + pack.lang
            prefs[versionKey(pack.lang)] = pack.version
            prefs[cardCountKey(pack.lang)] = pack.cardCount
            prefs[bytesKey(pack.lang)] = pack.bytes
            prefs[packSchemaKey(pack.lang)] = pack.packSchema
        }
    }

    /** Drops the record of [lang] after its files were deleted; never touches owned cards (EC-20). */
    suspend fun forget(lang: String) {
        context.installedPacksDataStore.edit { prefs ->
            prefs[KEY_LANGS] = (prefs[KEY_LANGS] ?: emptySet()) - lang
            prefs.remove(versionKey(lang))
            prefs.remove(cardCountKey(lang))
            prefs.remove(bytesKey(lang))
            prefs.remove(packSchemaKey(lang))
        }
    }

    private fun derivePack(lang: String): InstalledPack? {
        val catalog = PackCatalog.open(context, lang) ?: return null
        val meta = try {
            catalog.use { it.meta() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pack $lang has no readable pack_meta", e)
            return null
        }
        return InstalledPack(
            lang = lang,
            version = LOCAL_VERSION,
            cardCount = meta.cardCount,
            bytes = PackPaths.installedBytes(context, lang),
            packSchema = meta.packSchema,
        )
    }

    private fun readAll(prefs: Preferences): Map<String, InstalledPack> {
        val langs = prefs[KEY_LANGS] ?: return emptyMap()
        val packs = LinkedHashMap<String, InstalledPack>(langs.size)
        langs.sorted().forEach { lang ->
            // A language without a version is a half-written record from a killed process: ignore it.
            val version = prefs[versionKey(lang)] ?: return@forEach
            packs[lang] = InstalledPack(
                lang = lang,
                version = version,
                cardCount = prefs[cardCountKey(lang)] ?: 0,
                bytes = prefs[bytesKey(lang)] ?: 0L,
                packSchema = prefs[packSchemaKey(lang)] ?: 1,
            )
        }
        return packs
    }

    private companion object {

        const val TAG = "InstalledPacksStore"

        /** Version of a record derived from a pack file nobody installed through `PackInstaller`. */
        const val LOCAL_VERSION = "local"

        val KEY_LANGS = stringSetPreferencesKey("installed_langs")

        fun versionKey(lang: String) = stringPreferencesKey("pack_${lang}_version")

        fun cardCountKey(lang: String) = intPreferencesKey("pack_${lang}_card_count")

        fun bytesKey(lang: String) = longPreferencesKey("pack_${lang}_bytes")

        fun packSchemaKey(lang: String) = intPreferencesKey("pack_${lang}_pack_schema")
    }
}
