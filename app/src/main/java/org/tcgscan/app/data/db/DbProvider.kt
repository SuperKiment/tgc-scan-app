package org.tcgscan.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.tcgscan.app.R

/**
 * Builds and owns the single [AppDatabase] instance. The dependency container of T31 hands this
 * instance around; nothing else may open the file.
 */
object DbProvider {

    private const val DB_NAME = "tcgscan.db"

    /**
     * Schema history (NFR-9). Empty at version 1, and the only place a migration is ever
     * registered.
     *
     * The builder deliberately does not call `fallbackToDestructiveMigration()`: the collection is
     * the user's own data and has no remote copy, so a missing migration must fail loudly rather
     * than silently recreate an empty database.
     */
    val MIGRATIONS: Array<Migration> = emptyArray()

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(*MIGRATIONS)
            .addCallback(SeedCallback(context))
            .build()

    /**
     * Creates the system wishlist with the database (FR-16). Its name is taken from the resources
     * at creation time and then belongs to the user, who may have renamed nothing but could change
     * the device language: the row is never relabelled afterwards.
     */
    private class SeedCallback(private val appContext: Context) : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO card_lists (id, name, is_system, created_at) VALUES (?, ?, 1, ?)",
                arrayOf<Any>(
                    CardListEntity.WISHLIST_ID,
                    appContext.getString(R.string.list_wishlist_name),
                    System.currentTimeMillis(),
                ),
            )
        }
    }
}
