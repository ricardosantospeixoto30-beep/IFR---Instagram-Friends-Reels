package com.example.friendsreels.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Shared WebView helpers for playing Instagram Reels inline.
 *
 * Used by both [ReelPlayerActivity] (full-screen fallback player) and the
 * `FeedScreen` (inline auto-play per VerticalPager page). Extracted from
 * the original session-31 implementation of `ReelPlayerActivity` so we
 * have one canonical place with the tricky bits documented.
 *
 * Two moving parts to know about:
 * 1. [toEmbedUrl] rewrites the canonical share URL into IG's official
 *    `.../reel/<shortcode>/embed` form — skips the "Continuar na web"
 *    landing wall that IG shows to mobile browsers.
 * 2. [FriendsReelsWebViewClient] blocks the `intent://` /
 *    `instagram://reels_share/…` redirects that IG injects to push
 *    users back into the native app (which would otherwise trigger
 *    `net::ERR_UNKNOWN_URL_SCHEME`) and JS-forces autoplay because
 *    Chrome/WebView's autoplay policy blocks video-with-sound by
 *    default even when [WebView.settings.mediaPlaybackRequiresUserGesture]
 *    is off.
 */

/**
 * Build a configured WebView ready to play an IG Reel embed. Callers
 * are responsible for loading the URL (usually the return value of
 * [toEmbedUrl]) and for detaching the view when done so the WebView
 * releases its Chromium instance.
 *
 * @param onReceivedError invoked once per main-frame failure (network
 *   error, 4xx/5xx, geoblock, etc.). Subresource errors are swallowed.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun buildReelWebView(
    context: Context,
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
 * WebViewClient for Instagram Reel URLs. See file kdoc for the "why".
 */
internal class FriendsReelsWebViewClient(
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
        val isMainFrame = request?.isForMainFrame == true
        if (!isMainFrame) return
        val description = error?.description?.toString() ?: "Unknown error"
        onError(description)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(FORCE_AUTOPLAY_JS, null)
    }

    companion object {
        /**
         * Iterates every `<video>` in the page and forces play. Tries
         * unmuted first, falls back to muted on rejection because
         * Chrome always allows muted autoplay. Runs three times over
         * ~2 s because IG's embed inserts the video element
         * asynchronously and may recreate it as the page hydrates.
         */
        private const val FORCE_AUTOPLAY_JS = """
            (function() {
              function tryPlayAll() {
                var vids = document.getElementsByTagName('video');
                for (var i = 0; i < vids.length; i++) {
                  var v = vids[i];
                  var attempt = v.play();
                  if (attempt && typeof attempt.catch === 'function') {
                    attempt.catch(function() {
                      v.muted = true;
                      v.play();
                    });
                  }
                }
              }
              tryPlayAll();
              setTimeout(tryPlayAll, 600);
              setTimeout(tryPlayAll, 1500);
            })();
        """
    }
}

/**
 * Rewrite a canonical Instagram Reel share URL into the official
 * **embed** form, which skips the "Open in app" landing and renders
 * the video with an autoplay-friendly minimal chrome.
 *
 * Handles both singular (`/reel/`) and plural (`/reels/`) path
 * variants; preserves nothing else (query params, trailing paths).
 * Returns the input unchanged when no shortcode is found.
 *
 * Examples:
 *  - `https://www.instagram.com/reel/DMabc123/?igsh=xyz==`
 *      → `https://www.instagram.com/reel/DMabc123/embed`
 *  - `https://www.instagram.com/reels/DMabc123/`
 *      → `https://www.instagram.com/reel/DMabc123/embed`
 */
internal fun toEmbedUrl(rawUrl: String): String {
    val regex = Regex("""instagram\.com/reels?/([A-Za-z0-9_-]+)""")
    val match = regex.find(rawUrl) ?: return rawUrl
    val shortcode = match.groupValues[1]
    return "https://www.instagram.com/reel/$shortcode/embed"
}
