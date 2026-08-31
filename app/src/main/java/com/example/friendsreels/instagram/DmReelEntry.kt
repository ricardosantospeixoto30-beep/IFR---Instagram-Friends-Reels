package com.example.friendsreels.instagram

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Direction of a message inside an Instagram DM.
 *
 * The distinction is inferred at read time by looking at the presence of
 * [IgSelectors.Thread.SENDER_AVATAR] inside the surrounding
 * [IgSelectors.Thread.MESSAGE_CONTENT] container:
 *
 * - RECEIVED: `sender_avatar` is present → the friend (or a group member)
 *   sent this Reel to us.
 * - SENT: `sender_avatar` is absent → we sent this Reel out.
 *
 * The MVP feed only surfaces RECEIVED entries, and the PoC reaction actions
 * default to filtering out SENT entries so we never end up reacting to a
 * Reel we shared ourselves.
 */
enum class Direction {
    RECEIVED,
    SENT,
}

/**
 * Snapshot of a Reel bubble found inside the currently visible portion of
 * a conversation. The [node] reference is only valid while the underlying
 * Accessibility tree lives, so consumers should either act on it immediately
 * or copy out the primitive fields ([direction], [reelAuthor], [bounds]).
 *
 * @property index position of this entry inside `message_list` at the time
 *   of enumeration (top-down, 0-based).
 * @property kind either "portrait" or "generic" — matches which XMA
 *   container was found inside the message bubble.
 * @property direction whether the Reel was RECEIVED or SENT by us.
 * @property reelAuthor Instagram username of the account that originally
 *   posted the Reel (extracted from `title_text` inside the media
 *   container). Independent of who shared it in the DM.
 * @property bounds screen-space bounds of the media container (the actual
 *   long-press target), matching what dispatchGesture would need.
 * @property node underlying AccessibilityNodeInfo of the media container.
 */
data class DmReelEntry(
    val index: Int,
    val kind: String,
    val direction: Direction,
    val reelAuthor: String?,
    val bounds: Rect,
    val node: AccessibilityNodeInfo,
    /**
     * Emoji currently reacted on the bubble, if any. Extracted from the
     * `message_reactions_pill_container` associated with this bubble
     * (best-effort: matched by geometric proximity when the pill is not
     * a descendant of `message_content`). Null when no reaction is
     * present. See [ReelEntity.currentReaction] for how this ends up in
     * Room.
     */
    val currentReaction: String? = null,
)
