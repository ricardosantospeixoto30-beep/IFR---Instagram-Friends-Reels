package com.example.friendsreels.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.data.PendingActionDao
import com.example.friendsreels.data.PendingActionEntity
import com.example.friendsreels.data.ReelDao
import com.example.friendsreels.data.ReelEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
 * - [reactedHeart] / [reactedLaugh]: at least one reaction of that kind
 *   has already been applied to the Reel (`DONE` PoC-8 row). MVP proxy
 *   for the spec's `REACTION_SENT`. Note: if the user later removes the
 *   reaction directly in IG, we don't detect it — that requires a
 *   REACTIONS_PILL sync pass which is out of scope for the current PoC.
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
    val reactedHeart: Boolean = false,
    val reactedLaugh: Boolean = false,
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

    val reels: StateFlow<List<ReelEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            reel.id to ReelUiState(
                seen = reel.id in seenSet,
                pendingHeart = list.any { it.kind == PendingActionEntity.KIND_REACT_HEART && it.status == PendingActionEntity.STATUS_PENDING },
                pendingLaugh = list.any { it.kind == PendingActionEntity.KIND_REACT_LAUGH && it.status == PendingActionEntity.STATUS_PENDING },
                pendingReply = list.any { it.kind == PendingActionEntity.KIND_REPLY_TEXT && it.status == PendingActionEntity.STATUS_PENDING },
                reactedHeart = list.any { it.kind == PendingActionEntity.KIND_REACT_HEART && it.status == PendingActionEntity.STATUS_DONE },
                reactedLaugh = list.any { it.kind == PendingActionEntity.KIND_REACT_LAUGH && it.status == PendingActionEntity.STATUS_DONE },
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

