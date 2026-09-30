package com.example.friendsreels.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A text message captured right below a Reel in the DM — the messages the
 * friend "sent with" the Reel plus my own replies (spec §5). Stored on
 * [ReelEntity.contextMessages] as a compact JSON array and shown in the feed
 * under the Reel so the user has the conversation context.
 *
 * @property text the message text.
 * @property fromMe true when I sent it (no `sender_avatar` in the bubble),
 *   false when the friend sent it.
 */
data class ContextMessage(val text: String, val fromMe: Boolean)

/**
 * JSON (de)serialization for the [ReelEntity.contextMessages] column. Kept
 * as a tiny shared helper so the a11y capture side (service) and the feed UI
 * agree on the format: a JSON array of `{ "t": <text>, "me": <bool> }`.
 */
object ContextMessages {

    fun toJson(messages: List<Pair<String, Boolean>>): String? {
        if (messages.isEmpty()) return null
        val arr = JSONArray()
        for ((text, fromMe) in messages) {
            arr.put(JSONObject().put("t", text).put("me", fromMe))
        }
        return arr.toString()
    }

    fun fromJson(json: String?): List<ContextMessage> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t", "")
                if (t.isEmpty()) null else ContextMessage(t, o.optBoolean("me", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
