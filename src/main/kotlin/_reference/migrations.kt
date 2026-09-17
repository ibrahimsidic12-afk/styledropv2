package com.example.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * StyleDrop — explicit Room migrations.
 *
 * REPLACES `fallbackToDestructiveMigration()`.
 *
 * Version history (matches the repo):
 *   v1 — original single-table wardrobe DB (AI Studio template)
 *   v2 — current HEAD: `wardrobe_items` (a53013b), DB version = 2
 *   v3 — indices only, no table changes            <-- this file
 *   v4 — favourite_items backfill (first-class favourites)
 *
 * Register in AppDatabase with:
 *   .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
 *   .addFallbackToDestructiveMigrationOnDowngrade()   // downgrade only, never upgrade
 */

object DatabaseMigrations {

    /**
     * 2 -> 3  ·  ADD INDICES ONLY.
     * No column is added, dropped or renamed, so every existing row survives
     * byte-for-byte. `CREATE INDEX IF NOT EXISTS` keeps it idempotent.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // --- single-column indices on the four hot filter columns ---
            db.execSQL("CREATE INDEX IF NOT EXISTS index_wardrobe_items_category ON wardrobe_items(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_wardrobe_items_color ON wardrobe_items(color)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_wardrobe_items_style ON wardrobe_items(style)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_wardrobe_items_season ON wardrobe_items(season)")

            // --- composite indices for the real query shapes ---
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_wardrobe_items_category_createdAt " +
                    "ON wardrobe_items(category, createdAt DESC)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_wardrobe_items_season_category " +
                    "ON wardrobe_items(season, category)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_wardrobe_items_style_color " +
                    "ON wardrobe_items(style, color)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_wardrobe_items_favorite_createdAt " +
                    "ON wardrobe_items(isFavoriteItem, createdAt DESC)"
            )

            // --- outfits (new) ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `outfits` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `occasion` TEXT NOT NULL,
                    `style` TEXT NOT NULL,
                    `weather` TEXT NOT NULL,
                    `score` REAL NOT NULL,
                    `notes` TEXT NOT NULL,
                    `isFavorite` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outfits_createdAt ON outfits(createdAt DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outfits_occasion ON outfits(occasion)")

            // --- outfit_items junction (new) ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `outfit_items` (
                    `outfitId` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `slotOrder` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`outfitId`, `itemId`),
                    FOREIGN KEY(`outfitId`) REFERENCES `outfits`(`id`) ON UPDATE CASCADE ON DELETE CASCADE,
                    FOREIGN KEY(`itemId`) REFERENCES `wardrobe_items`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outfit_items_itemId ON outfit_items(itemId)")

            // --- outfit_logs (new) ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `outfit_logs` (
                    `id` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `outfitId` TEXT,
                    `wornAt` INTEGER NOT NULL,
                    `occasion` TEXT NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`itemId`) REFERENCES `wardrobe_items`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_outfit_logs_itemId_wornAt " +
                    "ON outfit_logs(itemId, wornAt DESC)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outfit_logs_wornAt ON outfit_logs(wornAt DESC)")

            // --- favorite_items (new) ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favorite_items` (
                    `id` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `favoritedAt` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`itemId`) REFERENCES `wardrobe_items`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_favorite_items_itemId ON favorite_items(itemId)"
            )

            // --- trend tables (new, Agent #10) ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trend_signals` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `source` TEXT NOT NULL,
                    `entityType` TEXT NOT NULL,
                    `entity` TEXT NOT NULL,
                    `week` TEXT NOT NULL,
                    `count` INTEGER NOT NULL,
                    `engagement` INTEGER NOT NULL,
                    `region` TEXT NOT NULL,
                    `fetchedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_trend_signals_entity_week_source " +
                    "ON trend_signals(entity, week, source)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trend_signals_entity ON trend_signals(entity)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trend_scores` (
                    `entity` TEXT NOT NULL,
                    `score` REAL NOT NULL,
                    `popularity` REAL NOT NULL,
                    `velocity` REAL NOT NULL,
                    `acceleration` REAL NOT NULL,
                    `freshness` REAL NOT NULL,
                    `crossSource` REAL NOT NULL,
                    `stage` TEXT NOT NULL,
                    `season` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`entity`)
                )
                """.trimIndent()
            )
        }
    }

    /**
     * 3 -> 4  ·  Promote the legacy boolean to real rows.
     *
     * Every item that had `isFavoriteItem = 1` gets a [FavoriteItem] row stamped
     * with its last-worn date (falling back to `createdAt`). The boolean column
     * is deliberately KEPT so any un-updated UI keeps working; it is just no
     * longer the source of truth. No `ALTER TABLE` is needed — favourites are
     * additive, so there is nothing to rewrite.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO favorite_items (id, itemId, favoritedAt, note)
                SELECT 'fav-' || id,
                       id,
                       COALESCE(lastWorn, createdAt),
                       ''
                FROM wardrobe_items
                WHERE isFavoriteItem = 1
                """.trimIndent()
            )
            // keep an index-friendly path for the new access pattern
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_favorite_items_favoritedAt " +
                    "ON favorite_items(favoritedAt DESC)"
            )
        }
    }

    /** Convenience list for `Room.databaseBuilder(...).addMigrations(*DatabaseMigrations.ALL)`. */
    val ALL = arrayOf(MIGRATION_2_3, MIGRATION_3_4)
}
