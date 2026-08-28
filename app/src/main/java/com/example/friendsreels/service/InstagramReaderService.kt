package com.example.friendsreels.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service used to read Instagram screens.
 *
 * Phase 1 / PoC-2 capability: on-demand dump of the currently active window's
 * accessibility tree to logcat. Triggered via broadcast so it can be invoked
 * from `adb` while the user is standing on the target Instagram screen.
 */
class InstagramReaderService : AccessibilityService() {

    private var dumpReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "InstagramReaderService connected")
        registerDumpReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op during PoC-2; navigation logic comes in later PoCs.
    }

    override fun onInterrupt() {
        Log.w(TAG, "InstagramReaderService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterDumpReceiver()
    }

    private fun registerDumpReceiver() {
        if (dumpReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_DUMP_TREE -> dumpActiveWindow()
                }
            }
        }
        val filter = IntentFilter(ACTION_DUMP_TREE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        dumpReceiver = receiver
        Log.i(TAG, "Dump receiver registered (action=$ACTION_DUMP_TREE)")
    }

    private fun unregisterDumpReceiver() {
        dumpReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { /* ignore */ }
        }
        dumpReceiver = null
    }

    private fun dumpActiveWindow() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "DUMP requested but rootInActiveWindow is null. Bring a foreground app first.")
            return
        }
        val pkg = root.packageName?.toString() ?: "?"
        Log.i(TAG, "===== DUMP START pkg=$pkg =====")
        dumpNode(root, 0)
        Log.i(TAG, "===== DUMP END pkg=$pkg =====")
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
    }
}
