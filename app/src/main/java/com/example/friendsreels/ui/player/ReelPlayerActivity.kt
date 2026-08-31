package com.example.friendsreels.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.friendsreels.R
import com.example.friendsreels.ui.theme.FriendsReelsTheme

/**
 * Full-screen fallback Reel player. Kept around from session 30-32 so
 * the feed's 3-dot menu can still fall back to this when the inline
 * feed WebView (session 36) fails to render.
 *
 * Delegates to the shared helpers in [EmbedPlayer.kt] — see that file
 * for the "why" behind the URL rewrite, the redirect blocking, and the
 * JS autoplay poke.
 */
class ReelPlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        setContent {
            FriendsReelsTheme {
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
    val context = LocalContext.current
    var loadError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val embedUrl = url?.let(::toEmbedUrl)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.player_title)) }) },
    ) { padding ->
        if (embedUrl.isNullOrBlank()) {
            EmptyPlayer(padding)
            return@Scaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    buildReelWebView(
                        ctx,
                        onReceivedError = { errorText -> loadError = errorText },
                    ).apply {
                        webViewRef = this
                        loadUrl(embedUrl)
                    }
                },
                update = { /* the URL is loaded once in factory */ },
            )
            loadError?.let { err ->
                LoadErrorOverlay(
                    error = err,
                    url = embedUrl,
                    originalUrl = url,
                    onRetry = {
                        loadError = null
                        webViewRef?.loadUrl(embedUrl)
                    },
                    onOpenInIg = { openInInstagram(context, url) },
                )
            }
        }
    }
}

@Composable
private fun EmptyPlayer(padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Text(text = stringResource(R.string.player_missing_url))
    }
}

@Composable
private fun LoadErrorOverlay(
    error: String,
    url: String,
    originalUrl: String,
    onRetry: () -> Unit,
    onOpenInIg: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            if (url != originalUrl) {
                Text(
                    text = originalUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenInIg, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feed_open_in_ig_native))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.player_retry))
            }
        }
    }
}

private fun openInInstagram(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .setPackage("com.instagram.android")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
        return
    } catch (_: Exception) { /* fall through */ }
    val browser = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(browser)
    } catch (_: Exception) { /* nothing else we can do */ }
}
