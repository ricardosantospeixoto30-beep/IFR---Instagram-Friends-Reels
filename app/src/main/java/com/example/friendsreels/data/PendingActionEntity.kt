package com.example.friendsreels.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-initiated Instagram action that has been ENQUEUED from the feed
 * and still needs to be replayed inside the Instagram app (PoC-8
 * iteration 3 — batching).
 *
 * Rationale: interacting with a Reel from the feed requires bringing
 * Instagram to the front and driving its UI via the AccessibilityService.
 * Doing that on every tap is slow AND disorienting (the user is kicked
 * out of the feed). Instead the feed writes rows into this table and the
 * user later triggers a single "Aplicar N acções" pass that opens IG once
 * and replays every pending action back-to-back.
 *
 * Life-cycle of a row:
 *   1. Feed inserts with `status = PENDING`, `executedAt = null`.
 *   2. Executor sets `status = RUNNING` just before it starts driving IG
 *      for this row (best-effort visibility flag, in case the user peeks
 *      at the feed during batching).
 *   3. Executor sets `status = DONE` + `executedAt = now` on success, or
 *      `status = FAILED` + `error = <reason>` on failure. Failed rows are
 *      not automatically retried — the user can re-enqueue from the feed.
 *
 * The `reelId` foreign key uses ON DELETE CASCADE so wiping the feed via
 * "Clear all" also drops any pending actions attached to those Reels.
 *
 * @see PendingActionDao
 */
@Entity(
    tableName = "pending_actions",
    foreignKeys = [
        ForeignKey(
            entity = ReelEntity::class,
            parentColumns = ["id"],
            childColumns = ["reelId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["reelId"]),
        Index(value = ["status"]),
    ],
)
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** FK into `reels.id`. See [ReelEntity]. */
    val reelId: Long,

    /** One of [KIND_REACT_HEART], [KIND_REACT_LAUGH], [KIND_REPLY_TEXT]. */
    val kind: String,

    /**
     * Optional free-form payload interpreted according to [kind]. For
     * `KIND_REPLY_TEXT` this holds the message the user typed. For
     * reactions it is null.
     */
    val payload: String? = null,

    /** One of [STATUS_PENDING], [STATUS_RUNNING], [STATUS_DONE], [STATUS_FAILED]. */
    val status: String,

    /** Epoch millis when the row was enqueued. */
    val createdAt: Long,

    /** Epoch millis when the executor finished the row (any terminal status). */
    val executedAt: Long? = null,

    /** Human-readable reason for a `FAILED` row. Null otherwise. */
    val error: String? = null,
) {
    companion object {
        const val KIND_REACT_HEART = "REACT_HEART"
        const val KIND_REACT_LAUGH = "REACT_LAUGH"
        const val KIND_REPLY_TEXT = "REPLY_TEXT"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_DONE = "DONE"
        const val STATUS_FAILED = "FAILED"

        val TERMINAL_STATUSES = setOf(STATUS_DONE, STATUS_FAILED)
    }
}
