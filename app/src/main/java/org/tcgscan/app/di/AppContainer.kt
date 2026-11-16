package org.tcgscan.app.di

import android.content.Context
import coil.ImageLoader
import org.tcgscan.app.TcgScanApp
import org.tcgscan.app.data.AppPrefs
import org.tcgscan.app.data.CardListRepository
import org.tcgscan.app.data.CollectionRepository
import org.tcgscan.app.data.LearnedEmbeddingRepository
import org.tcgscan.app.data.db.AppDatabase
import org.tcgscan.app.data.db.DbProvider
import org.tcgscan.app.data.net.Http
import org.tcgscan.app.data.pack.InstalledPacksStore
import org.tcgscan.app.data.pack.PackRepository
import org.tcgscan.app.scan.OnnxEnv
import org.tcgscan.app.scan.ScanPhotoStore
import org.tcgscan.app.scan.ScanSessionRepository
import org.tcgscan.app.ui.image.ImageLoaderProvider

/**
 * The hand-rolled dependency container: the single instance of everything that must exist once per
 * process (database, preferences, pack and collection repositories, ORT environment, image
 * loader). Created by [TcgScanApp]; every ViewModel factory takes its dependencies from here
 * instead of building its own.
 *
 * Everything is `lazy`, so a process pays only for what the current screen touches.
 *
 * Deliberately not here: the open pack catalogues (`PackCatalogs`, `MatchPackCache`). They hold
 * native SQLite handles whose lifetime follows one screen and are closed with its ViewModel, so
 * sharing them would need reference counting for no measurable gain.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val db: AppDatabase by lazy { DbProvider.get(appContext) }

    val appPrefs: AppPrefs by lazy { AppPrefs(appContext) }

    val installedPacksStore: InstalledPacksStore by lazy { InstalledPacksStore(appContext) }

    val packRepository: PackRepository by lazy {
        PackRepository(appContext, installedPacks = installedPacksStore)
    }

    val scanPhotoStore: ScanPhotoStore by lazy { ScanPhotoStore(appContext) }

    val collectionRepository: CollectionRepository by lazy {
        CollectionRepository(db, scanPhotoStore)
    }

    val cardListRepository: CardListRepository by lazy { CardListRepository(db.cardListDao()) }

    val scanSessionRepository: ScanSessionRepository by lazy {
        ScanSessionRepository(db.scanSessionDao(), db.ownershipDao(), scanPhotoStore)
    }

    val learnedEmbeddingRepository: LearnedEmbeddingRepository by lazy {
        LearnedEmbeddingRepository(db.learnedEmbeddingDao())
    }

    /** One ORT environment for the process; the scan pipeline opens and closes its own sessions. */
    val onnxEnv: OnnxEnv by lazy { OnnxEnv(appContext) }

    val imageLoader: ImageLoader by lazy { ImageLoaderProvider.create(appContext, Http.client) }
}

/** The application's [AppContainer], reachable from any [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as TcgScanApp).container
