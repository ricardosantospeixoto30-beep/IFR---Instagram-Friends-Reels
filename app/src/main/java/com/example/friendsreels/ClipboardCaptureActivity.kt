package com.example.friendsreels

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.friendsreels.service.InstagramReaderService

/**
 * Invisible bridge Activity used to read the system clipboard on Android 10+.
 *
 * `AccessibilityService` cannot read `ClipboardManager.primaryClip` when it is
 * not the foreground app — the framework silently returns an empty clip since
 * API 29 (privacy protection). To capture the URL that Instagram just wrote
 * to the clipboard after a programmatic "Copy link" tap, we briefly bring a
 * transparent Activity of our own app to the front, read the clipboard, and
 * finish immediately.
 *
 * Timing note: reading the clipboard in `onCreate` was too early on the
 * OnePlus Nord 5 (Android 16) — the platform still considered us
 * "background" and returned an empty clip. The clipboard becomes readable
 * once we truly have window focus, which is signalled by
 * [onWindowFocusChanged] with `hasFocus=true`. A small handler delay is
 * added as a safety net for the retry.
 */
class ClipboardCaptureActivity : Activity() {

    private var captured = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !captured) {
            // The framework can still miss the clipboard for a few ms right
            // after focus is granted. Give it one short retry cycle before
            // giving up.
            tryCaptureWithRetry(retriesLeft = 3)
        }
    }

    private fun tryCaptureWithRetry(retriesLeft: Int) {
        if (captured) return
        val text = readClipboardOrNull()
        if (!text.isNullOrBlank()) {
            captured = true
            deliverAndFinish(text)
            return
        }
        if (retriesLeft <= 0) {
            Log.w(TAG, "tryCaptureWithRetry: gave up, clipboard still empty.")
            captured = true
            deliverAndFinish(null)
            return
        }
        handler.postDelayed({ tryCaptureWithRetry(retriesLeft - 1) }, RETRY_DELAY_MS)
    }

    private fun readClipboardOrNull(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "readClipboardOrNull: failed", e)
            null
        }
    }

    private fun deliverAndFinish(text: String?) {
        Log.i(TAG, "deliverAndFinish: read clipboard -> ${text?.take(120)}")
        val broadcast = Intent(InstagramReaderService.ACTION_CLIPBOARD_CAPTURED)
            .setPackage(packageName)
            .putExtra(InstagramReaderService.EXTRA_CLIPBOARD_TEXT, text)
        sendBroadcast(broadcast)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClipboardCapture"
        private const val RETRY_DELAY_MS = 80L
    }
}

