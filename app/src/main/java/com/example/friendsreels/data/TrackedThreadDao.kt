package com.example.friendsreels.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [TrackedThreadEntity]. See that class for the semantics of a
 * row within the selection mode setting.
 */
@Dao
interface TrackedThreadDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: TrackedThreadEntity): Long

    @Query("DELETE FROM tracked_threads WHERE threadTitle = :title")
    suspend fun remove(title: String): Int

    @Query("SELECT threadTitle FROM tracked_threads ORDER BY threadTitle ASC")
    fun observeTitles(): Flow<List<String>>

    /**
     * One-shot snapshot of the current selection. Used by the batch
     * "discover history in all tracked threads" flow which needs a stable
     * list at the moment the user tapped the button (as opposed to a Flow
     * that would surprise the batch mid-run if the user tocasse selection
     * changes on another window).
     */
    @Query("SELECT threadTitle FROM tracked_threads ORDER BY threadTitle ASC")
    suspend fun snapshotTitles(): List<String>

    /**
     * Snapshot of every distinct `threadTitle` we've ever discovered
     * (from the `reels` table). The settings screen shows this list so
     * the user can pick which ones to track. Note: this can include
     * `"?"` if some very early discoveries ran before we started
     * capturing the header title; those rows are filtered out by the
     * settings UI.
     */
    @Query("SELECT DISTINCT threadTitle FROM reels ORDER BY threadTitle ASC")
    fun observeDiscoveredThreadTitles(): Flow<List<String>>

    @Query("SELECT threadTitle, COUNT(*) as reelCount FROM reels GROUP BY threadTitle ORDER BY threadTitle ASC")
    fun observeThreadCounts(): Flow<List<ThreadCount>>

    @Query("DELETE FROM tracked_threads")
    suspend fun clearAll()
}

/** Slim projection for [TrackedThreadDao.observeThreadCounts]. */
data class ThreadCount(val threadTitle: String, val reelCount: Int)
