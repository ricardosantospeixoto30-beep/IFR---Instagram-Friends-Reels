package com.example.friendsreels.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A Reel discovered inside an Instagram DM conversation.
 *
 * Two sources feed this table:
 * - `ACTION_DISCOVER_REELS` (PoC-8): fast enumeration of what is visible
 *   on screen. Only fills `threadTitle`, `reelAuthor`, `direction`, `kind`
 *   and `bubbleIndex`. `reelUrl` and `dmSender` stay null.
 * - `ACTION_COPY_REEL_URL` (PoC-7 + PoC-8): opens the Reel viewer to grab
 *   the canonical share URL and the human sender (`dmSender`), then
 *   inserts an enriched row here. If a row already exists for that URL,
 *   the existing row is backfilled with `dmSender`.
 *
 * Deduplication:
 * - Canonical: `reelUrl` unique index (used once we have URLs).
 * - Discovery-time fallback: `(threadTitle, reelAuthor, direction)` check
 *   inside the DAO to avoid inserting the same visible bubble twice
 *   across successive discovery calls.
 */
@Entity(
    tableName = "reels",
    indices = [Index(value = ["reelUrl"], unique = true)],
)
data class ReelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Header title of the conversation where the Reel was found. */
    val threadTitle: String,

    /**
     * Original IG author of the Reel (`title_text` of the XMA container,
     * i.e. the account that posted the Reel on Instagram — NOT the DM
     * sender).
     */
    val reelAuthor: String?,

    /**
     * Human sender of the Reel inside the DM. In 1-a-1 chats this equals
     * `threadTitle`. In groups this is the specific member who shared the
     * Reel — captured from `sender_username_or_fullname` inside the Reel
     * viewer (see [com.example.friendsreels.instagram.IgSelectors.ReelViewer]).
     * Null when the Reel was only discovered via the fast enumeration and
     * has not been opened yet.
     */
    val dmSender: String? = null,

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
