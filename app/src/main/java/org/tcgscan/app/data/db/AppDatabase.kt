package org.tcgscan.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import org.tcgscan.app.AppConfig

/**
 * The private database of the app: ownership, lists, scan sessions and the
 * visual references learned from the user's validated scans. Nothing coming from a pack
 * is stored here (FR-20), which is what lets a pack be installed, updated or removed without ever
 * touching the collection (FR-23, AC-17).
 *
 * The version is [AppConfig.DB_SCHEMA] so the backup envelope and the database can never disagree
 * (EC-18); the schema is exported to `app/schemas` and every upgrade goes through
 * `DbProvider.MIGRATIONS` (NFR-9).
 */
@Database(
    entities = [
        OwnedCard::class,
        CardListEntity::class,
        CardListItem::class,
        ScanSessionEntity::class,
        ScanDetectionEntity::class,
        LearnedEmbedding::class,
    ],
    version = AppConfig.DB_SCHEMA,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ownershipDao(): OwnershipDao

    abstract fun cardListDao(): CardListDao

    abstract fun scanSessionDao(): ScanSessionDao

    abstract fun learnedEmbeddingDao(): LearnedEmbeddingDao
}

/**
 * Enums are persisted by name, not by ordinal, so reordering a constant cannot reinterpret stored
 * rows and a schema dump stays readable. Declaring the converters explicitly also makes them apply
 * to query parameters, which is how the session lookups filter on a status.
 */
object DbConverters {

    @TypeConverter
    fun fromSessionStatus(status: ScanSessionStatus): String = status.name

    @TypeConverter
    fun toSessionStatus(value: String): ScanSessionStatus = ScanSessionStatus.valueOf(value)

    @TypeConverter
    fun fromDetectionState(state: ScanDetectionState): String = state.name

    @TypeConverter
    fun toDetectionState(value: String): ScanDetectionState = ScanDetectionState.valueOf(value)
}
