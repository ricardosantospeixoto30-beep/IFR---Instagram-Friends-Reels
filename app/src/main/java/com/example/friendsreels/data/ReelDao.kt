package com.example.friendsreels.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {

    /**
     * Insert a new Reel; returns -1 if a row with the same `reelUrl`
     * unique index already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reel: ReelEntity): Long

    /**
     * Existence check used when we don't have a URL yet: dedup by
     * (thread + author + direction). Not perfect (collapses multiple
     * Reels from the same author in the same thread) but good enough
     * for the PoC-8 first iteration.
     */
    @Query(
        "SELECT COUNT(*) FROM reels " +
            "WHERE threadTitle = :thread " +
            "AND ((reelAuthor IS NULL AND :author IS NULL) OR reelAuthor = :author) " +
            "AND direction = :direction"
    )
    suspend fun countMatching(thread: String, author: String?, direction: String): Int

    @Query("SELECT * FROM reels WHERE id = :id")
    suspend fun byId(id: Long): ReelEntity?

    @Query("SELECT * FROM reels ORDER BY discoveredAt DESC")
    fun observeAll(): Flow<List<ReelEntity>>

    @Query("SELECT COUNT(*) FROM reels")
    suspend fun count(): Int

    @Query("DELETE FROM reels")
    suspend fun clearAll()

    @Query("UPDATE reels SET seenAt = :epochMs WHERE id = :id AND seenAt IS NULL")
    suspend fun markSeen(id: Long, epochMs: Long)

    /**
     * Backfill `dmSender` on a row that already has this `reelUrl`. Only
     * writes when the existing value is null so we don't clobber a good
     * name with a null.
     */
    @Query("UPDATE reels SET dmSender = :dmSender WHERE reelUrl = :url AND dmSender IS NULL")
    suspend fun updateDmSenderByUrl(url: String, dmSender: String?): Int

    /**
     * Promote a discovery-only row (created by `ACTION_DISCOVER_REELS`
     * without a URL) into a fully enriched row by writing the URL and
     * dmSender captured by `ACTION_COPY_REEL_URL`. Matches by
     * (threadTitle, reelAuthor, direction) with `reelUrl IS NULL`, which
     * is the same key used by [countMatching] so at most one row is
     * affected per call in practice.
     *
     * This closes the interaction gap where a Reel had first been
     * discovered (URL null) and then copied — without this method the
     * insert path creates a duplicate row because the URL unique index
     * treats NULLs as distinct.
     */
    @Query(
        "UPDATE reels SET reelUrl = :url, dmSender = :dmSender " +
            "WHERE reelUrl IS NULL " +
            "AND threadTitle = :thread " +
            "AND ((reelAuthor IS NULL AND :author IS NULL) OR reelAuthor = :author) " +
            "AND direction = :direction"
    )
    suspend fun promoteDiscoveryRow(
        url: String,
        dmSender: String?,
        thread: String,
        author: String?,
        direction: String,
    ): Int
}
