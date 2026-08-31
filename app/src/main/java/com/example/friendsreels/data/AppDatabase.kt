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
 * - v4 (sessão 37): adds `tracked_threads` table for the conversation
 *   selection feature (spec §8) — see [TrackedThreadEntity].
 * - v5 (sessão 49): adds `currentReaction` column to `reels` (spec §7)
 *   so the feed can show the reaction actually present on the DM,
 *   not just the one our own app has sent. Populated during
 *   `enumerateReels` from `message_reactions_pill_container`.
 *
 * We use `fallbackToDestructiveMigration()` — the PoC data is regenerated
 * by the user with a quick "Descobrir" pass.
 */
@Database(
    entities = [ReelEntity::class, PendingActionEntity::class, TrackedThreadEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reelDao(): ReelDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun trackedThreadDao(): TrackedThreadDao

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
