package com.example.friendsreels.ui.feed

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.friendsreels.service.InstagramReaderService
import com.example.friendsreels.ui.theme.FriendsReelsTheme

class FeedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(InstagramReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        val invertSwipe = prefs.getBoolean(
            InstagramReaderService.PREF_INVERT_SWIPE,
            InstagramReaderService.PREF_INVERT_SWIPE_DEFAULT,
        )
        setContent {
            FriendsReelsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FeedScreen(invertSwipe = invertSwipe)
                }
            }
        }
    }
}
