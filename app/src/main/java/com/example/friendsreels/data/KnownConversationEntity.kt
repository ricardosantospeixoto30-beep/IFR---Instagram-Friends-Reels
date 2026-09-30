package com.example.friendsreels.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A conversation the user has registered as "known" (s56) — independent of
 * whether any Reels were discovered in it yet.
 *
 * Why a separate table: before s56 the "Filtrar conversas" list was derived
 * purely from distinct `reels.threadTitle`, so (a) there was no way to pick a
 * conversation before discovering its Reels, and (b) clearing the Reels (the
 * 🗑 reset) also made the conversation disappear from the list. This table
 * decouples "conversations" from "reels": it is populated by the explicit
 * "➕ Conhecer esta conversa" action AND by any discovery pass, and it is
 * NOT touched by the reset — so conversations persist.
 */
@Entity(tableName = "known_conversations")
data class KnownConversationEntity(
    @PrimaryKey val threadTitle: String,
    /** Epoch millis when this conversation was first registered. */
    val addedAt: Long,
)
