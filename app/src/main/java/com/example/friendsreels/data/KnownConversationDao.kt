package com.example.friendsreels.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [KnownConversationEntity] (s56). See that class for why the
 * "known conversations" list is decoupled from the `reels` table.
 */
@Dao
interface KnownConversationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: KnownConversationEntity): Long

    @Query("SELECT threadTitle FROM known_conversations ORDER BY threadTitle ASC")
    fun observeTitles(): Flow<List<String>>

    @Query("DELETE FROM known_conversations WHERE threadTitle = :title")
    suspend fun remove(title: String): Int

    @Query("DELETE FROM known_conversations")
    suspend fun clearAll()
}
