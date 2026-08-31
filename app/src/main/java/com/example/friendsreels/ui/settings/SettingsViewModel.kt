package com.example.friendsreels.ui.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
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
            trackedDao.observeThreadCounts().collect { list ->
                out.value = list.filter { it.threadTitle.isNotBlank() && it.threadTitle != "?" }
            }
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
}
