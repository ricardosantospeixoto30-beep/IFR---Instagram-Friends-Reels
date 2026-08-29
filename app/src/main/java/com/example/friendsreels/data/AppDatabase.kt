package com.example.friendsreels.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Friends Reels. Single table for now (`reels`);
 * additional tables (batched actions queue, thread cursor) will be
 * added in later PoC-8 iterations.
 */
@Database(entities = [ReelEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reelDao(): ReelDao

    companion object {
        private const val DB_NAME = "friends_reels.db"

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { INSTANCE = it }
            }
        }
    }
}
