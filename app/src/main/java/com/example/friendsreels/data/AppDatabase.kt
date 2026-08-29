package com.example.friendsreels.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Friends Reels.
 *
 * Schema evolution:
 * - v1 (sessão 21): initial `reels` table.
 * - v2 (sessão 23): adds `dmSender` column to `reels` (human sender of the
 *   Reel inside the DM).
 * - v3 (sessão 26 — PoC-8 iteração 3): adds `pending_actions` table for
 *   batching of reactions/replies. Enqueued from the feed and drained by
 *   `InstagramReaderService.ACTION_APPLY_PENDING` in a single IG pass.
 *
 * We use `fallbackToDestructiveMigration()` — the PoC data is regenerated
 * by the user with a quick "Descobrir" pass.
 */
@Database(
    entities = [ReelEntity::class, PendingActionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reelDao(): ReelDao
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        private const val DB_NAME = "friends_reels.db"

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
