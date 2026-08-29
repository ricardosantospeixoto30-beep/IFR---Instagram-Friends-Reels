package com.example.friendsreels.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [PendingActionEntity]. See that class for the row lifecycle.
 *
 * All reads exposed to the UI observe rows in FIFO order (oldest first)
 * so the user sees actions execute in the same order they were enqueued.
 * Terminal rows (`DONE` / `FAILED`) are kept as receipts so the feed can
 * show a badge; the user can wipe them with [clearTerminal].
 */
@Dao
interface PendingActionDao {

    @Insert
    suspend fun insert(action: PendingActionEntity): Long

    @Query("SELECT * FROM pending_actions ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingActionEntity>>

    @Query(
        "SELECT COUNT(*) FROM pending_actions " +
            "WHERE status = '${PendingActionEntity.STATUS_PENDING}'"
    )
    fun observePendingCount(): Flow<Int>

    @Query(
        "SELECT * FROM pending_actions " +
            "WHERE status = '${PendingActionEntity.STATUS_PENDING}' " +
            "ORDER BY createdAt ASC"
    )
    suspend fun pending(): List<PendingActionEntity>

    /**
     * Guard used by the feed to avoid enqueueing the same (reel, kind)
     * twice while the previous copy is still un-executed. For replies we
     * do allow multiple queued rows (the user might change their mind
     * about the text) so callers may skip this check for
     * [PendingActionEntity.KIND_REPLY_TEXT].
     */
    @Query(
        "SELECT COUNT(*) FROM pending_actions " +
            "WHERE reelId = :reelId AND kind = :kind " +
            "AND status = '${PendingActionEntity.STATUS_PENDING}'"
    )
    suspend fun countPending(reelId: Long, kind: String): Int

    @Query("UPDATE pending_actions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query(
        "UPDATE pending_actions SET status = :status, executedAt = :executedAt, error = :error " +
            "WHERE id = :id"
    )
    suspend fun finish(id: Long, status: String, executedAt: Long, error: String?)

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun byId(id: Long): PendingActionEntity?

    /** Observe the (reelId, kind) pairs that currently have a PENDING row — used to badge the feed cards. */
    @Query(
        "SELECT reelId, kind FROM pending_actions " +
            "WHERE status = '${PendingActionEntity.STATUS_PENDING}'"
    )
    fun observePendingPairs(): Flow<List<PendingActionPair>>

    @Query(
        "DELETE FROM pending_actions WHERE status IN " +
            "('${PendingActionEntity.STATUS_DONE}', '${PendingActionEntity.STATUS_FAILED}')"
    )
    suspend fun clearTerminal(): Int

    /**
     * Delete every PENDING row for [reelId]. Rows already RUNNING or in a
     * terminal state are kept — cancelling something the executor is
     * already replaying would leave IG in a weird state.
     */
    @Query(
        "DELETE FROM pending_actions " +
            "WHERE reelId = :reelId " +
            "AND status = '${PendingActionEntity.STATUS_PENDING}'"
    )
    suspend fun cancelPendingForReel(reelId: Long): Int

    @Query("DELETE FROM pending_actions")
    suspend fun clearAll()
}

/** Slim projection for [PendingActionDao.observePendingPairs]. */
data class PendingActionPair(val reelId: Long, val kind: String)
