package com.example.friendsreels.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ClipData
import android.content.ClipboardManager

class InstagramAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "IGAutomationService"
        const val ACTION_START_SYNC = "com.example.friendsreels.ACTION_START_SYNC"
    }

    private val startSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Received start‑sync broadcast")
            launchInstagramAndExtractDemoReel()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        val info = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        registerReceiver(startSyncReceiver, IntentFilter(ACTION_START_SYNC))
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No live handling needed for PoC; we drive everything from the broadcast.
    }

    /** --------------------------------------------------------------
     *  PoC flow – launch Instagram, open Direct, find ONE Reel thumbnail,
     *  copy its URL to Logcat.
     *  -------------------------------------------------------------- */
    private fun launchInstagramAndExtractDemoReel() {
        // 1️⃣ Launch Instagram
        val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        Thread.sleep(3000) // give Instagram time to start – acceptable for PoC

        // 2️⃣ Find the "Direct" tab by content‑description
        val root = rootInActiveWindow ?: run { Log.e(TAG, "Root null"); return }
        val directNode = findNodeByContentDescription(root, "Direct")
        if (directNode != null) {
            directNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Clicked Direct tab")
        } else {
            Log.e(TAG, "Direct tab not found"); return
        }
        Thread.sleep(2000)

        // 3️⃣ Open first conversation (quick heuristic – click first avatar image)
        val firstConv = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_profile_picture")?.firstOrNull()
        if (firstConv != null) {
            firstConv.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Opened first conversation")
        } else {
            Log.e(TAG, "No conversation found"); return
        }
        Thread.sleep(2000)

        // 4️⃣ Look for a Reel thumbnail (content‑description contains "Reel")
        val reelNode = findNodeByContentDescription(root, "Reel")
        if (reelNode != null) {
            // Long‑click to bring up copy‑link menu
            reelNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            Thread.sleep(1000)
            // Grab clipboard – Instagram copies the deep‑link when you choose "Copy Link"
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData? = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val url = clip.getItemAt(0).coerceToText(this).toString()
                Log.i(TAG, "Extracted Reel URL: $url")
            } else {
                Log.w(TAG, "Clipboard empty – could not get URL")
            }
        } else {
            Log.w(TAG, "No Reel node found in this conversation")
        }
    }

    /** Recursive search for a node whose contentDescription contains the supplied text */
    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cd = node.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains(text.lowercase())) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(startSyncReceiver)
    }
}
