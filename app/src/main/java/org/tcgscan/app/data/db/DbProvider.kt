package org.tcgscan.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.tcgscan.app.R

/**
 * Builds and owns the single [AppDatabase] instance. `AppContainer` is its only caller and hands
 * the instance around; nothing else may open the file.
 */
object DbProvider {

    private const val DB_NAME = "tcgscan.db"

    /**
     * Schema history (NFR-9), the only place a migration is ever registered. The SQL must match
     * what Room exports in `app/schemas`, or the schema validation fails at open.
     *
     * The builder deliberately does not call `fallbackToDestructiveMigration()`: the collection is
     * the user's own data and has no remote copy, so a missing migration must fail loudly rather
     * than silently recreate an empty database.
     */
    val MIGRATIONS: Array<Migration> = arrayOf(
        // 1 -> 2: on-device learning. The embedding of a capture is kept on its row, and the vectors
        // of validated scans go to their own table.
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `scan_detections` ADD COLUMN `embedding` BLOB")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `learned_embeddings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`lang` TEXT NOT NULL, " +
                        "`card_id` TEXT NOT NULL, " +
                        "`vector` BLOB NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_learned_embeddings_lang_card_id` " +
                        "ON `learned_embeddings` (`lang`, `card_id`)",
                )
            }
        },
    )

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
