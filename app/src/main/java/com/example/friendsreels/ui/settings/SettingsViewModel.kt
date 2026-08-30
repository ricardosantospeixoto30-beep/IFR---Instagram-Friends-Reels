package com.example.friendsreels.ui.settings

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.data.ThreadCount
import com.example.friendsreels.data.TrackedThreadDao
import com.example.friendsreels.data.TrackedThreadEntity
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
 * checkboxes.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val trackedDao: TrackedThreadDao = AppDatabase.get(app).trackedThreadDao()
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
}
