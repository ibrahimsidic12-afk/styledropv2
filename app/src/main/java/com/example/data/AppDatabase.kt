package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.models.WardrobeItem

// SECURITY (Agent #14): no fallbackToDestructiveMigration() — see Migrations.kt.

@Database(entities = [WardrobeItem::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wardrobeDao(): WardrobeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "styledrop_database"
                )
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
