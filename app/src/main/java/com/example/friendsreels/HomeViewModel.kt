package com.example.friendsreels

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.service.InstagramReaderService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel behind [MainActivity]'s Home screen. Exposes the conversation
 * list (for the "Esquecer conversas" dialog), the stored-Reel count, the
 * single-pass depth preference, and the forget/reset operations — so the
 * primary actions the user repeats while testing (discover+prepare, forget
 * conversations, reset Reels) are one tap away from Home instead of buried
 * in Settings (s57).
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val reelDao = db.reelDao()
    private val knownDao = db.knownConversationDao()
    private val trackedDao = db.trackedThreadDao()
    private val pendingDao = db.pendingActionDao()
    private val prefs: SharedPreferences =
        app.getSharedPreferences(InstagramReaderService.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Union of conversations that have Reels (`reels`) and conversations the
     * user registered (`known_conversations`). The `"?"` sentinel is hidden.
     */
    val conversations: StateFlow<List<String>> =
        combine(
            trackedDao.observeThreadCounts(),
            knownDao.observeTitles(),
        ) { counts, known ->
            val set = LinkedHashSet<String>()
            counts.forEach { if (it.threadTitle.isNotBlank() && it.threadTitle != "?") set.add(it.threadTitle) }
            known.forEach { if (it.isNotBlank() && it != "?") set.add(it) }
            set.sortedBy { it.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Total Reels stored — drives the "Apagar Reels" button enabled state. */
    val totalReelCount: StateFlow<Int> = reelDao.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Reels still missing a URL — drives the "Preparar URLs em lote" button. */
    val missingUrlCount: StateFlow<Int> = reelDao.observeMissingUrlCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun isScanToEnd(): Boolean = prefs.getBoolean(
        InstagramReaderService.PREF_SINGLEPASS_SCAN_TO_END,
        InstagramReaderService.PREF_SINGLEPASS_SCAN_TO_END_DEFAULT,
    )

    fun setScanToEnd(value: Boolean) {
        prefs.edit().putBoolean(InstagramReaderService.PREF_SINGLEPASS_SCAN_TO_END, value).apply()
    }

    /**
     * Forget the given conversations: delete their Reels + pending actions,
     * and drop them from the known/tracked lists. Deliberately DIFFERENT from
     * [clearAllReels] (reset), which keeps conversations.
     */
    fun forgetConversations(titles: List<String>) {
        if (titles.isEmpty()) return
        viewModelScope.launch {
            pendingDao.deleteForThreads(titles)
            reelDao.deleteByThreads(titles)
            knownDao.removeAll(titles)
            trackedDao.removeAll(titles)
        }
    }

    /** Forget every conversation (Reels + known + tracked + pending). */
    fun forgetAllConversations() {
        viewModelScope.launch {
            pendingDao.clearAll()
            reelDao.clearAll()
            knownDao.clearAll()
            trackedDao.clearAll()
        }
    }

    /**
     * s51 reset — wipe Reels + pending queue but KEEP the conversation lists
     * and settings, so the user can re-discover from scratch during testing.
     */
    fun clearAllReels() {
        viewModelScope.launch {
            reelDao.clearAll()
            pendingDao.clearAll()
        }
    }
}
