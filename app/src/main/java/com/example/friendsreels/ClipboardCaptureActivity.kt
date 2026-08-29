package com.example.friendsreels

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * The Activity uses `Theme.Translucent.NoTitleBar` and calls
 * `overridePendingTransition(0, 0)` so the visual disruption is minimal.
 * Result is delivered back to the service via a broadcast.
 */
class ClipboardCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        captureAndFinish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        captureAndFinish()
    }

    private fun captureAndFinish() {
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "captureAndFinish: failed to read clipboard", e)
            null
        }
        Log.i(TAG, "captureAndFinish: read clipboard -> ${text?.take(120)}")
        val broadcast = Intent(InstagramReaderService.ACTION_CLIPBOARD_CAPTURED)
            .setPackage(packageName)
            .putExtra(InstagramReaderService.EXTRA_CLIPBOARD_TEXT, text)
        sendBroadcast(broadcast)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "ClipboardCapture"
    }
}
