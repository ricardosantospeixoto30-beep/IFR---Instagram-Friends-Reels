package com.example.friendsreels.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Reel discovered inside an Instagram DM conversation.
 *
 * The identity of a Reel is not stable across scrolls (the `bubbleIndex`
 * shifts as the RecyclerView virtualizes), and we won't have the canonical
 * `reelUrl` until the user runs `ACTION_COPY_REEL_URL` on it (PoC-7). For
 * the PoC-8 first iteration we deduplicate by `(threadTitle, reelAuthor,
 * direction)` at insert time — good enough while we don't have URLs, at
 * the cost of collapsing multiple Reels from the same author in the same
 * conversation into a single row. This will move to URL-based dedup once
 * PoC-7 is integrated end-to-end.
 */
@Entity(
    tableName = "reels",
    indices = [Index(value = ["reelUrl"], unique = true)],
)
data class ReelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Header title of the conversation where the Reel was found. */
    val threadTitle: String,

    /** Original IG author of the Reel (`title_text` of the XMA container). */
    val reelAuthor: String?,

    /** RECEIVED or SENT (see [com.example.friendsreels.instagram.Direction]). */
    val direction: String,

    /** portrait or generic (which XMA container held the Reel). */
    val kind: String,

    /**
     * Index within `message_list` at the time of discovery. Not stable
     * across scrolls; kept only for logging / debugging.
     */
    val bubbleIndex: Int,

    /**
     * Canonical share URL of the Reel, extracted via `ACTION_COPY_REEL_URL`.
     * Null until PoC-7 has been run for this Reel.
     */
    val reelUrl: String?,

    /** Epoch millis when this row was inserted. */
    val discoveredAt: Long,

    /** Epoch millis when the user first viewed the Reel inside the app feed. */
    val seenAt: Long? = null,
)
