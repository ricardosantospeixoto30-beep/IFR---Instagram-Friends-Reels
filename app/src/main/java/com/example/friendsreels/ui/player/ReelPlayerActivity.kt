package com.example.friendsreels.ui.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.example.friendsreels.R

/**
 * Minimal Reel player — WebView pointing at the canonical Reel URL
 * captured by `ACTION_COPY_REEL_URL` (PoC-7).
 *
 * PoC-8 iter 3 part C: complements the "Abrir no Instagram" primary
 * action on the feed with an in-app player. Instagram's public share
 * URLs usually render the Reel with a minimal chrome even without
 * login — good enough for a first pass. If a specific Reel fails
 * (login wall, removed content, geoblock, autoplay muted, ...) the
 * feed card still exposes the "↗ Abrir no Instagram nativo" secondary
 * link as a guaranteed fallback.
 *
 * Deliberately kept simple: no ExoPlayer, no back-stack tricks, no
 * custom chrome.
 */
class ReelPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayerScreen(url)
                }
            }
        }
    }

    companion object {
        /** Extra key carrying the Reel URL to load. */
        const val EXTRA_URL = "reel_url"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(url: String?) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.player_title)) }) },
    ) { padding ->
        if (url.isNullOrBlank()) {
            EmptyPlayer(padding)
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                factory = { ctx ->
                    buildWebView(ctx).apply { loadUrl(url) }
                },
                update = { /* the URL is loaded once in factory */ },
            )
        }
    }
}

@Composable
private fun EmptyPlayer(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Text(text = stringResource(R.string.player_missing_url))
    }
}

/**
 * Configure a WebView for Instagram Reel URLs.
 * - JS is required (IG's page is entirely rendered by JS).
 * - DOM storage covers session cookies used by IG's SPA.
 * - `mediaPlaybackRequiresUserGesture = false` lets the Reel autoplay
 *   after load (matches IG's own behaviour).
 * - Wide viewport + overview mode give the Reel the expected mobile
 *   layout even though we're inside a small WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(context: android.content.Context): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webChromeClient = WebChromeClient()
        webViewClient = WebViewClient()
    }
}
