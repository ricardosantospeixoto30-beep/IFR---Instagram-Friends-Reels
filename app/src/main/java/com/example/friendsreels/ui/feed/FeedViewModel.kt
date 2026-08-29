package com.example.friendsreels.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.data.ReelDao
import com.example.friendsreels.data.ReelEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FeedViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: ReelDao = AppDatabase.get(app).reelDao()

    val reels: StateFlow<List<ReelEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markSeen(id: Long) {
        viewModelScope.launch { dao.markSeen(id, System.currentTimeMillis()) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clearAll() }
    }
}
