package org.tcgscan.app.ui.image

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import org.tcgscan.app.AppConfig

/**
 * Builds the single Coil [ImageLoader] used for TCGdex visuals (FR-25). The disk cache has an
 * explicit [AppConfig.IMAGE_CACHE_BYTES] ceiling and the loader reuses the app-wide [OkHttpClient].
 */
object ImageLoaderProvider {

    private const val CACHE_DIR = "card_images"

    fun create(context: Context, client: OkHttpClient): ImageLoader {
        val app = context.applicationContext
        return ImageLoader.Builder(app)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve(CACHE_DIR))
                    .maxSizeBytes(AppConfig.IMAGE_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /** Empties both caches; used by the Settings « vider le cache d'images » action. */
    fun clear(loader: ImageLoader) {
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
