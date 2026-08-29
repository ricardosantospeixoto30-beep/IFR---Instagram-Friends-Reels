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
 * State for the feed screen.
 *
 * Beyond the raw list of Reels, exposes:
 * - [pendingByReelKind]: a set of `(reelId, kind)` pairs currently in the
 *   `PENDING` state, so the feed can badge the buttons that already have a
 *   queued action.
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

    /** Set of "reelId:kind" tokens with a PENDING row — used to badge the cards. */
    val pendingByReelKind: StateFlow<Set<String>> = pendingDao.observePendingPairs()
        .map { list -> list.map { "${it.reelId}:${it.kind}" }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
     * — the same visible Reel can only have one pending 👀 at a time to
     * prevent accidental spamming. If the user wants to queue two
     * different replies they'll need to send + re-queue.
     */
    fun enqueueReply(reel: ReelEntity, text: String, onResult: (Result) -> Unit) {
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
                    payload = text,
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

    enum class Result { Queued, AlreadyQueued }
}

