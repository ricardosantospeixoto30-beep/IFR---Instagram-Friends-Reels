package com.example.friendsreels.ui.feed

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.data.PendingActionDao
import com.example.friendsreels.data.PendingActionEntity
import com.example.friendsreels.data.ReelDao
import com.example.friendsreels.data.ReelEntity
import com.example.friendsreels.data.TrackedThreadDao
import com.example.friendsreels.data.TrackedThreadEntity
import com.example.friendsreels.service.InstagramReaderService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-Reel derived UI state (spec §7). Combines PoC-8 batching signals
 * with `ReelEntity.seenAt` into the vision-level flags:
 *
 * - [seen]: the user has opened this Reel at least once inside the feed.
 * - [pendingHeart] / [pendingLaugh] / [pendingReply]: there is a
 *   `pending_actions` row still in `PENDING` for this Reel. Surfaced as
 *   the "❤ na fila" badges on the card.
 * - [currentReaction]: the reaction that was most recently applied
 *   ([PendingActionEntity.KIND_REACT_HEART] or `KIND_REACT_LAUGH`), or
 *   null if no reaction has ever been sent through the feed. Instagram
 *   allows only ONE reaction per message, so reacting a second time
 *   REPLACES the previous one — we mirror that by keeping only the
 *   latest [PendingActionEntity.executedAt] amongst `DONE` reactions.
 *   Note: if the user later removes the reaction directly in IG, we
 *   don't detect it. That requires reading the
 *   `message_reactions_pill_container` in a sync pass which is out of
 *   scope for the current PoC — see §6.2.
 * - [replied]: at least one reply has been applied (`DONE` row of
 *   `KIND_REPLY_TEXT`). Spec's `REPLIED`.
 * - [failedActions]: count of `FAILED` rows for this Reel — surfaced on
 *   the card so the user knows an action didn't go through.
 */
data class ReelUiState(
    val seen: Boolean = false,
    val pendingHeart: Boolean = false,
    val pendingLaugh: Boolean = false,
    val pendingReply: Boolean = false,
    val currentReaction: String? = null,
    val replied: Boolean = false,
    val failedActions: Int = 0,
)

/**
 * State for the feed screen.
 *
 * Beyond the raw list of Reels, exposes:
 * - [uiStates]: map of `reelId -> ReelUiState` combining seen flag,
 *   pending actions and applied ("done") actions. See [ReelUiState].
 * - [pendingCount]: number of pending rows — surfaced on the "Aplicar N
 *   acções" button.
 * - [enqueueReaction] / [enqueueReply]: user-facing entry points that
 *   write into the `pending_actions` table instead of driving IG live.
 * - [clearTerminal]: wipe DONE/FAILED receipts.
 */
class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: ReelDao = AppDatabase.get(app).reelDao()
    private val pendingDao: PendingActionDao = AppDatabase.get(app).pendingActionDao()
    private val trackedDao: TrackedThreadDao = AppDatabase.get(app).trackedThreadDao()
    private val prefs: SharedPreferences = app.getSharedPreferences(
        InstagramReaderService.PREFS_NAME,
        android.content.Context.MODE_PRIVATE,
    )

    /**
     * Live snapshot of the selection mode (spec §8). Uses a
     * SharedPreferences change listener so switching mode in the
     * Settings screen updates the feed immediately.
     */
    private val selectionMode: StateFlow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == InstagramReaderService.PREF_SELECTION_MODE) {
                trySend(
                    p.getString(
                        InstagramReaderService.PREF_SELECTION_MODE,
                        InstagramReaderService.PREF_SELECTION_MODE_DEFAULT,
                    ) ?: InstagramReaderService.PREF_SELECTION_MODE_DEFAULT
                )
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(
            prefs.getString(
                InstagramReaderService.PREF_SELECTION_MODE,
                InstagramReaderService.PREF_SELECTION_MODE_DEFAULT,
            ) ?: InstagramReaderService.PREF_SELECTION_MODE_DEFAULT
        )
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        InstagramReaderService.PREF_SELECTION_MODE_DEFAULT,
    )

    private val trackedTitles: StateFlow<Set<String>> = trackedDao.observeTitles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        .let { titlesFlow ->
            kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()).also { out ->
                viewModelScope.launch {
                    titlesFlow.collect { list -> out.value = list.toSet() }
                }
            }
        }

    /**
     * Live-filtered list of Reels shown on the feed. Applies the
     * selection mode filter over the raw `reels` table:
     * - NONE → show everything
     * - INCLUDE_ONLY → keep only Reels whose `threadTitle` is in the
     *   tracked set
     * - EXCLUDE_SELECTED → drop Reels whose `threadTitle` is in the
     *   tracked set
     */
    val reels: StateFlow<List<ReelEntity>> = combine(
        dao.observeAll(),
        trackedTitles,
        selectionMode,
    ) { list, tracked, mode ->
        when (mode) {
            InstagramReaderService.SELECTION_MODE_INCLUDE_ONLY -> list.filter { it.threadTitle in tracked }
            InstagramReaderService.SELECTION_MODE_EXCLUDE_SELECTED -> list.filter { it.threadTitle !in tracked }
            else -> list
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> = pendingDao.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Combined per-Reel state map. Derived from the live actions list and
     * the reels list. Both flows always emit an updated snapshot so the
     * feed UI reflects reactions the moment the executor marks them DONE.
     */
    val uiStates: StateFlow<Map<Long, ReelUiState>> = kotlinx.coroutines.flow.combine(
        dao.observeAll(),
        pendingDao.observeAll(),
    ) { reelList, actions ->
        val seenSet = reelList.asSequence().filter { it.seenAt != null }.map { it.id }.toSet()
        val byReel = actions.groupBy { it.reelId }
        reelList.associate { reel ->
            val list = byReel[reel.id].orEmpty()
            val latestReaction = list
                .asSequence()
                .filter {
                    it.status == PendingActionEntity.STATUS_DONE &&
                        (it.kind == PendingActionEntity.KIND_REACT_HEART ||
                            it.kind == PendingActionEntity.KIND_REACT_LAUGH)
                }
                .maxByOrNull { it.executedAt ?: 0L }
            reel.id to ReelUiState(
                seen = reel.id in seenSet,
                pendingHeart = list.any { it.kind == PendingActionEntity.KIND_REACT_HEART && it.status == PendingActionEntity.STATUS_PENDING },
                pendingLaugh = list.any { it.kind == PendingActionEntity.KIND_REACT_LAUGH && it.status == PendingActionEntity.STATUS_PENDING },
                pendingReply = list.any { it.kind == PendingActionEntity.KIND_REPLY_TEXT && it.status == PendingActionEntity.STATUS_PENDING },
                currentReaction = latestReaction?.kind,
                replied = list.any { it.kind == PendingActionEntity.KIND_REPLY_TEXT && it.status == PendingActionEntity.STATUS_DONE },
                failedActions = list.count { it.status == PendingActionEntity.STATUS_FAILED },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun markSeen(id: Long) {
        viewModelScope.launch { dao.markSeen(id, System.currentTimeMillis()) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clearAll() }
    }

    /**
     * Fire the [InstagramReaderService.ACTION_ENRICH_REEL_URL] broadcast
     * for the given [reelId]. The service drives IG to the thread,
     * scrolls to the specific Reel, opens the viewer, and captures the
     * URL via the existing copy-link chain. On success the URL is
     * backfilled into the row and the feed's WebView will pick it up
     * on the next recomposition.
     */
    fun requestUrlEnrichment(reelId: Long) {
        val ctx = getApplication<Application>()
        val intent = Intent(InstagramReaderService.ACTION_ENRICH_REEL_URL)
            .setPackage(ctx.packageName)
            .putExtra(InstagramReaderService.EXTRA_REEL_ID, reelId)
        ctx.sendBroadcast(intent)
    }

    /**
     * Enqueue a reaction ([PendingActionEntity.KIND_REACT_HEART] or
     * [PendingActionEntity.KIND_REACT_LAUGH]). Reactions are deduplicated:
     * if the same (reel, kind) already has a PENDING row we no-op. The
     * result is returned via [onResult] so the UI can toast an
     * appropriate message.
     */
    fun enqueueReaction(reel: ReelEntity, kind: String, onResult: (Result) -> Unit) {
        viewModelScope.launch {
            val existing = pendingDao.countPending(reel.id, kind)
            if (existing > 0) {
                onResult(Result.AlreadyQueued)
                return@launch
            }
            pendingDao.insert(
                PendingActionEntity(
                    reelId = reel.id,
                    kind = kind,
                    payload = null,
                    status = PendingActionEntity.STATUS_PENDING,
                    createdAt = System.currentTimeMillis(),
                )
            )
            onResult(Result.Queued)
        }
    }

    /**
     * Enqueue a reply to [reel] with [text]. Deduplicated by (reelId, kind)
     * — the same visible Reel can only have one pending reply at a time to
     * prevent accidental spamming. If the user wants to queue a second
     * reply they have to apply the first one first.
     *
     * The empty/blank check is a defensive guard — the UI dialog should
     * disable the send button when the text field is empty, but users
     * still find ways.
     */
    fun enqueueReply(reel: ReelEntity, text: String, onResult: (Result) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onResult(Result.Empty)
            return
        }
        viewModelScope.launch {
            val existing = pendingDao.countPending(reel.id, PendingActionEntity.KIND_REPLY_TEXT)
            if (existing > 0) {
                onResult(Result.AlreadyQueued)
                return@launch
            }
            pendingDao.insert(
                PendingActionEntity(
                    reelId = reel.id,
                    kind = PendingActionEntity.KIND_REPLY_TEXT,
                    payload = trimmed,
                    status = PendingActionEntity.STATUS_PENDING,
                    createdAt = System.currentTimeMillis(),
                )
            )
            onResult(Result.Queued)
        }
    }

    /**
     * Cancel every PENDING action queued for [reelId]. Rows already
     * RUNNING/DONE/FAILED are left alone (they're either already partway
     * through the executor or a receipt of past work).
     */
    fun cancelPendingForReel(reelId: Long) {
        viewModelScope.launch { pendingDao.cancelPendingForReel(reelId) }
    }

    fun clearTerminal() {
        viewModelScope.launch { pendingDao.clearTerminal() }
    }

    enum class Result { Queued, AlreadyQueued, Empty }
}

