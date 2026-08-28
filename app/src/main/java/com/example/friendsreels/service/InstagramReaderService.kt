package com.example.friendsreels.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
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
 * PoC-3 (long-press): finds the visible Reel bubble in the currently open
 * Instagram conversation and dispatches a long-press gesture centered on the
 * bubble. The gesture uses AccessibilityService.dispatchGesture so we can
 * control both the location and the duration (short enough to open the IG
 * context menu while staying under the OxygenOS "Portal de conteúdo"
 * threshold). Triggered by ACTION_LONG_PRESS_FIRST_REEL. A follow-up dump is
 * scheduled once the gesture completes so we can inspect the resulting menu.
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

        val target = findFirstReelBubble(messageList)
        if (target == null) {
            Log.w(TAG, "LONG_PRESS: no Reel bubble (portrait/generic xma container) found in the visible message list.")
            return
        }

        val bounds = Rect().also { target.node.getBoundsInScreen(it) }
        Log.i(
            TAG,
            "LONG_PRESS: target kind=${target.kind} author=${target.authorUsername} " +
                "bounds=${bounds.toShortString()} center=(${bounds.centerX()},${bounds.centerY()})"
        )

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w(TAG, "LONG_PRESS: bubble has empty bounds; refusing to dispatch gesture.")
            return
        }

        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "LONG_PRESS: gesture completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "LONG_PRESS: gesture cancelled by system")
            }
        }, mainHandler)
        Log.i(TAG, "LONG_PRESS: dispatchGesture accepted=$dispatched duration=${LONG_PRESS_DURATION_MS}ms")

        // Give the context menu animation enough time to settle, then dump.
        mainHandler.postDelayed({ dumpActiveWindow("after-longpress") }, LONG_PRESS_DURATION_MS + 1500L)
    }

    private data class ReelTarget(
        val kind: String,
        val node: AccessibilityNodeInfo,
        val authorUsername: String?,
    )

    /**
     * Walk the message_list and return the tightest Reel-carrying subtree we
     * can find: prefer the portrait media container, fall back to the generic
     * card container. Both hold the actual visible reel bubble; the outer
     * message_content wrapper spans the entire row width so it is unsuitable
     * as a long-press target.
     */
    private fun findFirstReelBubble(messageList: AccessibilityNodeInfo): ReelTarget? {
        val portraitId = IgSelectors.id(IgSelectors.Thread.MESSAGE_MEDIA_PORTRAIT)
        val genericId = IgSelectors.id(IgSelectors.Thread.MESSAGE_MEDIA_GENERIC)

        val portraits = messageList.findAccessibilityNodeInfosByViewId(portraitId).orEmpty()
        val generics = messageList.findAccessibilityNodeInfosByViewId(genericId).orEmpty()

        val candidates = (portraits.map { "portrait" to it } + generics.map { "generic" to it })
            .filter {
                val r = Rect(); it.second.getBoundsInScreen(r)
                r.width() > 0 && r.height() > 0
            }
            .sortedBy {
                val r = Rect(); it.second.getBoundsInScreen(r); r.top
            }

        val (kind, node) = candidates.firstOrNull() ?: return null
        val author = node
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.REEL_AUTHOR_USERNAME))
            ?.firstOrNull()
            ?.text
            ?.toString()
        return ReelTarget(kind, node, author)
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
        private const val LONG_PRESS_DURATION_MS = 600L
        const val ACTION_DUMP_TREE = "com.example.friendsreels.ACTION_DUMP_TREE"
        const val ACTION_LONG_PRESS_FIRST_REEL = "com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL"
    }
}
