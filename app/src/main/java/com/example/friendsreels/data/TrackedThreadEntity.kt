package com.example.friendsreels.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A conversation the user has marked as "selected" for the feed filter
 * (spec §8 — "Seleção de conversas, pessoas e grupos").
 *
 * The MEANING of a row here depends on the current selection mode
 * ([com.example.friendsreels.service.InstagramReaderService.PREF_SELECTION_MODE]):
 *
 * - `SELECTION_MODE_NONE`: rows in this table are ignored (feed shows
 *   every Reel regardless of thread). Default on first launch.
 * - `SELECTION_MODE_INCLUDE_ONLY`: feed ONLY shows Reels whose
 *   `threadTitle` appears here — matches spec §8.1 "Apenas selecionados".
 * - `SELECTION_MODE_EXCLUDE_SELECTED`: feed shows every Reel EXCEPT
 *   those from threads in this table — spec §8.2 "Excluir selecionados".
 *
 * The primary key is the [threadTitle] itself because that's the only
 * stable identifier we have across sessions (no `thread_id` — see §6.4
 * of PROJECT_PROGRESS). If IG's title format for a thread ever changes
 * (user rename, etc.) the entry silently stops matching — a known
 * limitation shared with the batching navigation.
 *
 * Reels themselves are NOT deleted when a thread is excluded. Only the
 * feed filter changes. This means the user can move threads in/out of
 * the selection without losing discovered data.
 */
@Entity(tableName = "tracked_threads")
data class TrackedThreadEntity(
    @PrimaryKey val threadTitle: String,
    /** Epoch millis when the user added this thread to the tracked list. */
    val selectedAt: Long,
)
