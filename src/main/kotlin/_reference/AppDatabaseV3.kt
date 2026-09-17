package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.entity.FavoriteItem
import com.example.data.entity.Outfit
import com.example.data.entity.OutfitItem
import com.example.data.entity.OutfitLog
import com.example.data.entity.TrendScore
import com.example.data.entity.TrendSignal
import com.example.data.entity.WardrobeItem

/**
 * StyleDrop v3 database.
 *
 * What changed vs the repo's current `AppDatabase.kt`:
 *   - `version = 2`  ->  `version = 4`  (indices, then favourite backfill)
 *   - `fallbackToDestructiveMigration()`  ->  `.addMigrations(*DatabaseMigrations.ALL)`
 *   - `exportSchema = false`  ->  `exportSchema = true` (schema JSON committed to VCS)
 *   - `allowMainThreadQueries()` is deliberately ABSENT (never add it)
 *   - foreign keys enabled via `setForeignKeyConstraintsEnabled` through Room's
 *     `@Entity` FK metadata (Room enables them by default on API 16+).
 */
@Database(
    entities = [
        WardrobeItem::class,
        Outfit::class,
        OutfitItem::class,
        OutfitLog::class,
        FavoriteItem::class,
        TrendSignal::class,
        TrendScore::class,
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun wardrobeDao(): WardrobeDao

    companion object {
        private const val DB_NAME = "styledrop_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Swap `.addMigrations(...)` for `.addAutoMigrations(...)` if you later
         * adopt Room's auto-migration annotations — but keep the explicit
         * objects for anything involving data movement (e.g. MIGRATION_3_4).
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*DatabaseMigrations.ALL)
                    // Downgrades (v4 app data opened by an older APK) may reset;
                    // UPGRADES must never be destructive.
                    .addFallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Test-only in-memory builder. */
        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
    }
}
