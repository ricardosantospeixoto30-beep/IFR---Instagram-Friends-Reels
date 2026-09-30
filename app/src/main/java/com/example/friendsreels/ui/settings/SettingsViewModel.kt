package com.example.friendsreels.ui.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.data.KnownConversationDao
import com.example.friendsreels.data.PendingActionDao
import com.example.friendsreels.data.ReelDao
import com.example.friendsreels.data.ThreadCount
import com.example.friendsreels.data.TrackedThreadDao
import com.example.friendsreels.data.TrackedThreadEntity
import com.example.friendsreels.service.BatchEnrichmentBus
import com.example.friendsreels.service.InstagramReaderService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel behind [SettingsActivity]. Exposes the selection mode
 * preference (spec §8) plus the discovered thread list with per-thread
 * checkboxes, and — since s38 — the state of the batch URL enrichment
 * feature.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val trackedDao: TrackedThreadDao = AppDatabase.get(app).trackedThreadDao()
    private val reelDao: ReelDao = AppDatabase.get(app).reelDao()
    private val pendingDao: PendingActionDao = AppDatabase.get(app).pendingActionDao()
    private val knownDao: KnownConversationDao = AppDatabase.get(app).knownConversationDao()
    private val prefs: SharedPreferences = app.getSharedPreferences(
        InstagramReaderService.PREFS_NAME,
        android.content.Context.MODE_PRIVATE,
    )

    val selectionMode: StateFlow<String> = callbackFlow {
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

    /**
     * Live set of thread titles currently marked as tracked. Ordered
     * alphabetically by the DAO.
     */
    val trackedTitles: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()).also { out ->
        viewModelScope.launch {
            trackedDao.observeTitles().collect { list -> out.value = list.toSet() }
        }
    }

    /**
     * Discovered threads with their Reel counts. The `"?"` sentinel
     * (rows created before we started capturing the header title) is
     * hidden — it wouldn't be a meaningful pick.
     */
    val threadCounts: StateFlow<List<ThreadCount>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList<ThreadCount>()).also { out ->
        viewModelScope.launch {
            // s56: the list is the UNION of conversations that have Reels
            // (from `reels`) and conversations the user registered
            // (`known_conversations`, which survive the reset). Reel-less
            // known conversations show a count of 0.
            combine(
                trackedDao.observeThreadCounts(),
                knownDao.observeTitles(),
            ) { counts, known ->
                val byTitle = LinkedHashMap<String, ThreadCount>()
                counts.forEach { if (it.threadTitle.isNotBlank() && it.threadTitle != "?") byTitle[it.threadTitle] = it }
                known.forEach { t -> if (t.isNotBlank() && t != "?" && t !in byTitle) byTitle[t] = ThreadCount(t, 0) }
                byTitle.values.sortedBy { it.threadTitle.lowercase() }
            }.collect { out.value = it }
        }
    }

    /**
     * Live count of Reels that don't yet have a URL. Used by the
     * "Preparar URLs em lote" section to display "N Reels sem URL"
     * and to disable the button when the count is zero.
     */
    val missingUrlCount: StateFlow<Int> = reelDao.observeMissingUrlCount().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0,
    )

    /** State of the batch URL enrichment run in the service. */
    val batchEnrichmentState: StateFlow<BatchEnrichmentBus.State> = BatchEnrichmentBus.state

    /**
     * Total number of Reels currently stored, regardless of URL state.
     * Drives the "Apagar dados descobertos" section (s51): the button is
     * disabled at 0 and the confirmation dialog shows this count.
     */
    val totalReelCount: StateFlow<Int> = reelDao.observeAll()
        .map { it.size }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            0,
        )

    fun setSelectionMode(mode: String) {
        prefs.edit().putString(InstagramReaderService.PREF_SELECTION_MODE, mode).apply()
    }

    fun setTrackedThread(title: String, tracked: Boolean) {
        viewModelScope.launch {
            if (tracked) {
                trackedDao.insert(
                    TrackedThreadEntity(threadTitle = title, selectedAt = System.currentTimeMillis())
                )
            } else {
                trackedDao.remove(title)
            }
        }
    }

    /**
     * Fire-and-forget kick-off for the batch URL enrichment. The
     * service will bring IG to the front and process each pending
     * Reel sequentially, streaming progress into
     * [BatchEnrichmentBus].
     */
    fun startBatchEnrichment() {
        val context = getApplication<Application>()
        context.sendBroadcast(
            Intent(InstagramReaderService.ACTION_ENRICH_ALL_MISSING_URLS)
                .setPackage(context.packageName)
        )
    }

    /**
     * Ask the running batch to stop after the current Reel finishes.
     * See [InstagramReaderService.cancelBatchEnrichment].
     */
    fun cancelBatchEnrichment() {
        val context = getApplication<Application>()
        context.sendBroadcast(
            Intent(InstagramReaderService.ACTION_ENRICH_ALL_CANCEL)
                .setPackage(context.packageName)
        )
    }

    /**
     * s51 — wipe all discovered content so the user can start a fresh
     * discovery/test run. Clears the `reels` table and the
     * `pending_actions` queue. Intentionally KEEPS `tracked_threads`
     * (selection), `known_conversations` (s56 — so conversations survive
     * the reset) and SharedPreferences, so the user doesn't have to
     * reconfigure after every reset. Does not touch Instagram in any way.
     */
    fun clearAllDiscoveredData() {
        viewModelScope.launch {
            reelDao.clearAll()
            pendingDao.clearAll()
        }
    }
}
