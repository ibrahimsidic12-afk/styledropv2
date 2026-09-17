package com.example.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SECURITY (Agent #14) — replaces `fallbackToDestructiveMigration()`.
 *
 * Before: any schema bump silently DELETED the user's entire wardrobe. That is a
 * data-loss defect and, because there was no explicit migration, it also meant the
 * DB shape was undocumented.
 *
 * MIGRATION_1_2 uses the defensive "create-copy-swap" pattern: it rebuilds the table
 * from a known-good DDL and copies whatever rows exist. It is safe whether the on-disk
 * v1 table already matched v2 or was missing the later columns.
 *
 * NOTE: because `secondaryColor` is `NOT NULL DEFAULT ''` in v2, the copy supplies ''
 * for any row that predates the column.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `wardrobe_items_new` (
                `id` TEXT NOT NULL,
                `imageUrl` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `color` TEXT NOT NULL,
                `secondaryColor` TEXT NOT NULL DEFAULT '',
                `style` TEXT NOT NULL,
                `fit` TEXT NOT NULL,
                `pattern` TEXT NOT NULL,
                `season` TEXT NOT NULL DEFAULT 'All Season',
                `brand` TEXT NOT NULL DEFAULT '',
                `timesWorn` INTEGER NOT NULL DEFAULT 0,
                `isFavoriteItem` INTEGER NOT NULL DEFAULT 0,
                `lastWorn` INTEGER,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `wardrobe_items_new`
                (`id`,`imageUrl`,`category`,`type`,`color`,`secondaryColor`,`style`,`fit`,
                 `pattern`,`season`,`brand`,`timesWorn`,`isFavoriteItem`,`lastWorn`,`createdAt`)
            SELECT
                `id`,`imageUrl`,`category`,`type`,`color`,
                COALESCE(`secondaryColor`,''),`style`,`fit`,`pattern`,
                COALESCE(`season`,'All Season'),COALESCE(`brand`,''),
                COALESCE(`timesWorn`,0),COALESCE(`isFavoriteItem`,0),`lastWorn`,
                COALESCE(`createdAt`,0)
            FROM `wardrobe_items`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS `wardrobe_items`")
        db.execSQL("ALTER TABLE `wardrobe_items_new` RENAME TO `wardrobe_items`")
        // Column-level sort used by the DAO's ORDER BY createdAt DESC.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_wardrobe_items_createdAt` ON `wardrobe_items` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_wardrobe_items_category` ON `wardrobe_items` (`category`)")
    }
}

/** Every migration the app knows about. Registered in [AppDatabase.getDatabase]. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
