package com.example.friendsreels.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material3.darkColorScheme
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

/**
 * Minimal Reel player — WebView pointing at the canonical Reel URL
 * captured by `ACTION_COPY_REEL_URL` (PoC-7).
 *
 * PoC-8 iter 3 part C: complements the "Abrir no Instagram" action on
 * the feed with an in-app player.
 *
 * Instagram is very aggressive about pushing users into the native app
 * — as soon as the WebView loads a Reel URL, IG's page tries to
 * redirect to `intent://…` or `instagram://reels_share/…` deep-links,
 * which the WebView doesn't handle (that's the classic
 * `net::ERR_UNKNOWN_URL_SCHEME` the user saw on the first attempt in
 * session 28). We intercept those non-http redirects in
 * [FriendsReelsWebViewClient.shouldOverrideUrlLoading] and refuse them
 * so the WebView stays on the HTTP page. If the initial HTTP page
 * itself fails, we surface a friendly error overlay with a big
 * "Abrir no Instagram nativo" button — the guaranteed fallback.
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
    val context = LocalContext.current
    var loadError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.player_title)) }) },
    ) { padding ->
        if (url.isNullOrBlank()) {
            EmptyPlayer(padding)
            return@Scaffold
        }
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    buildWebView(
                        ctx,
                        onReceivedError = { errorText -> loadError = errorText },
                    ).apply {
                        webViewRef = this
                        loadUrl(url)
                    }
                },
                update = { /* the URL is loaded once in factory */ },
            )
            loadError?.let { err ->
                LoadErrorOverlay(
                    error = err,
                    url = url,
                    onRetry = {
                        loadError = null
                        webViewRef?.loadUrl(url)
                    },
                    onOpenInIg = {
                        openInInstagram(context, url)
                    },
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
    onRetry: () -> Unit,
    onOpenInIg: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
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

/**
 * Configure a WebView for Instagram Reel URLs. See the class kdoc for
 * the reasoning behind each setting.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildWebView(
    context: android.content.Context,
    onReceivedError: (String) -> Unit,
): WebView {
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
        webViewClient = FriendsReelsWebViewClient(onReceivedError)
    }
}

/**
 * WebViewClient for Instagram Reel URLs.
 *
 * Two responsibilities:
 * 1. **Block non-http redirects.** Instagram's public web page pushes a
 *    `intent://…` or `instagram://reels_share/…` redirect as soon as it
 *    detects a mobile browser. The WebView cannot handle those schemes
 *    and shows a `net::ERR_UNKNOWN_URL_SCHEME` error page. Returning
 *    `true` from `shouldOverrideUrlLoading` for non-http schemes tells
 *    the WebView "we handled it" and cancels the navigation, keeping
 *    the HTTP page rendered.
 * 2. **Surface HTTP-level errors.** If the initial page load itself
 *    fails (network error, 4xx/5xx, blocked), we call [onError] with
 *    the description string. The Compose layer overlays a friendly
 *    error card with a "Abrir no Instagram nativo" fallback button.
 *    Sub-resource errors (like the video CDN dropping a single asset)
 *    are ignored so we don't wipe the page for every 404 on an image.
 */
private class FriendsReelsWebViewClient(
    private val onError: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val scheme = request?.url?.scheme?.lowercase()
        return if (scheme == null || scheme == "http" || scheme == "https") {
            false // let the WebView handle it
        } else {
            true // swallow intent://, instagram://, market://, etc.
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        // Only report errors for the main frame; ignore sub-resource
        // failures (they don't kill the page).
        val isMainFrame = request?.isForMainFrame == true
        if (!isMainFrame) return
        val description = error?.description?.toString() ?: "Unknown error"
        onError(description)
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

