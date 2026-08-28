package com.example.friendsreels.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.friendsreels.instagram.IgSelectors

/**
 * Accessibility service used to read Instagram screens.
 *
 * PoC-2 (dump): on-demand dump of the currently active window's accessibility
 * tree to logcat. Triggered by ACTION_DUMP_TREE from adb.
 *
 * PoC-3 (long-press): finds the first Reel-carrying message in the currently
 * open Instagram conversation and triggers a long-press on it via the
 * accessibility API. Triggered by ACTION_LONG_PRESS_FIRST_REEL. A follow-up
 * dump is scheduled 1500ms later so we can inspect the full context menu.
 */
class InstagramReaderService : AccessibilityService() {

    private var actionReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "InstagramReaderService connected")
        registerActionReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Kept intentionally empty: everything is broadcast-driven during PoC.
    }

    override fun onInterrupt() {
        Log.w(TAG, "InstagramReaderService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterActionReceiver()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------------
    // Broadcast receiver wiring
    // ---------------------------------------------------------------------

    private fun registerActionReceiver() {
        if (actionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_DUMP_TREE -> dumpActiveWindow("adb")
                    ACTION_LONG_PRESS_FIRST_REEL -> longPressFirstReel()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP_TREE)
            addAction(ACTION_LONG_PRESS_FIRST_REEL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        actionReceiver = receiver
        Log.i(TAG, "Action receiver registered (dump=$ACTION_DUMP_TREE, longpress=$ACTION_LONG_PRESS_FIRST_REEL)")
    }

    private fun unregisterActionReceiver() {
        actionReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { /* ignore */ }
        }
        actionReceiver = null
    }

    // ---------------------------------------------------------------------
    // PoC-3 — auto long-press on the first Reel in the open conversation
    // ---------------------------------------------------------------------

    private fun longPressFirstReel() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "LONG_PRESS requested but rootInActiveWindow is null.")
            return
        }
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "LONG_PRESS ignored: foreground is ${root.packageName}, expected ${IgSelectors.IG_PACKAGE}.")
            return
        }

        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "LONG_PRESS: no message_list found. Are you on a conversation screen?")
            return
        }

        val reelMessage = findFirstReelMessage(messageList)
        if (reelMessage == null) {
            Log.w(TAG, "LONG_PRESS: no Reel-carrying message found in the visible message list.")
            return
        }

        val authorUsername = extractReelAuthor(reelMessage)
        val bounds = Rect().also { reelMessage.getBoundsInScreen(it) }
        Log.i(TAG, "LONG_PRESS: target message bounds=${bounds.toShortString()} author=$authorUsername")

        val performed = reelMessage.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        Log.i(TAG, "LONG_PRESS: performAction(ACTION_LONG_CLICK) returned $performed")

        // Wait long enough for the context menu animation to settle, then dump
        // so we can see all menu items in the same log block.
        mainHandler.postDelayed({ dumpActiveWindow("after-longpress") }, 1500)
    }

    /**
     * Walk the message_list looking for the first `message_content` node whose
     * subtree contains a Reel/media container. Returns the outer
     * `message_content` frame (long-clickable) or null.
     */
    private fun findFirstReelMessage(messageList: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val messageContentId = IgSelectors.id(IgSelectors.Thread.MESSAGE_CONTENT)
        val messageContents = messageList.findAccessibilityNodeInfosByViewId(messageContentId) ?: return null

        val portraitId = IgSelectors.id(IgSelectors.Thread.MESSAGE_MEDIA_PORTRAIT)
        val genericId = IgSelectors.id(IgSelectors.Thread.MESSAGE_MEDIA_GENERIC)

        for (content in messageContents) {
            val hasPortrait = content.findAccessibilityNodeInfosByViewId(portraitId)?.isNotEmpty() == true
            val hasGeneric = content.findAccessibilityNodeInfosByViewId(genericId)?.isNotEmpty() == true
            if (hasPortrait || hasGeneric) return content
        }
        return null
    }

    /** Reads the Reel's original author username from within a message content node. */
    private fun extractReelAuthor(messageContent: AccessibilityNodeInfo): String? {
        val usernameNode = messageContent
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.REEL_AUTHOR_USERNAME))
            ?.firstOrNull()
        return usernameNode?.text?.toString()
    }

    // ---------------------------------------------------------------------
    // Tree dump utility (shared by PoC-2 and PoC-3)
    // ---------------------------------------------------------------------

    private fun dumpActiveWindow(reason: String) {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "DUMP requested ($reason) but rootInActiveWindow is null.")
            return
        }
        val pkg = root.packageName?.toString() ?: "?"
        Log.i(TAG, "===== DUMP START reason=$reason pkg=$pkg =====")
        dumpNode(root, 0)
        Log.i(TAG, "===== DUMP END reason=$reason pkg=$pkg =====")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val id = node.viewIdResourceName ?: "-"
        val desc = node.contentDescription?.toString()?.take(80)?.replace('\n', ' ')
        val text = node.text?.toString()?.take(80)?.replace('\n', ' ')
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val c = if (node.isClickable) "C" else "-"
        val s = if (node.isScrollable) "S" else "-"
        val l = if (node.isLongClickable) "L" else "-"
        val e = if (node.isEditable) "E" else "-"
        val flags = "[$c$s$l$e]"

        val line = buildString {
            append(indent).append(cls).append(' ').append(flags).append(' ')
            append("id=").append(id)
            if (!desc.isNullOrEmpty()) append(" desc=\"").append(desc).append('"')
            if (!text.isNullOrEmpty()) append(" text=\"").append(text).append('"')
            append(" b=").append(bounds.toShortString())
        }
        Log.i(TAG, line)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
        }
    }

    companion object {
        private const val TAG = "IGReaderService"
        const val ACTION_DUMP_TREE = "com.example.friendsreels.ACTION_DUMP_TREE"
        const val ACTION_LONG_PRESS_FIRST_REEL = "com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL"
    }
}
