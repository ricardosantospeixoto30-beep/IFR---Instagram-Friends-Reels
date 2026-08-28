package com.example.friendsreels.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.friendsreels.R
import com.example.friendsreels.instagram.IgSelectors

/**
 * Accessibility service used to read and drive Instagram screens.
 *
 * All operations are broadcast-triggered so we can invoke them from `adb` or
 * from a button inside the app. The public actions are declared in the
 * companion object at the bottom of this file.
 *
 * PoC-2 (dump): dumps the currently active window (or every window) to
 * logcat. Actions: ACTION_DUMP_TREE, ACTION_DUMP_ALL_WINDOWS.
 *
 * PoC-3 (long-press): finds the visible Reel bubble in the currently open
 * Instagram conversation and dispatches a long-press gesture centered on the
 * bubble. Uses AccessibilityService.dispatchGesture so we can control both
 * the location and the duration (short enough to open the IG context menu
 * while staying under the OxygenOS "Portal de conteúdo" threshold).
 * Action: ACTION_LONG_PRESS_FIRST_REEL.
 *
 * PoC-5 (react): after the long-press opens the reaction row, clicks the
 * requested emoji ImageView. The reaction is applied to the underlying DM
 * message on Instagram's side and persists after we close the conversation.
 * Actions: ACTION_REACT_HEART, ACTION_REACT_LAUGH.
 */
class InstagramReaderService : AccessibilityService() {

    private var actionReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Header title of the last Instagram conversation the user visibly opened.
     * Captured from `header_title` in the thread screen. Used as a defensive
     * hint (e.g. logs and future navigation) — restoring the exact thread is
     * currently not needed because bringing IG to front without
     * `FLAG_ACTIVITY_REORDER_TO_FRONT` already resumes the last screen.
     */
    private var lastKnownConversationTitle: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "InstagramReaderService connected")
        registerActionReceiver()
        postControlNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track which conversation the user opens so we can log / restore it
        // if IG ever fails to come back to the same thread.
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != IgSelectors.IG_PACKAGE) return
        val root = rootInActiveWindow ?: return
        val title = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.HEADER_TITLE))
            ?.firstOrNull()
            ?.text
            ?.toString()
            ?.trim()
        if (!title.isNullOrEmpty() && title != lastKnownConversationTitle) {
            lastKnownConversationTitle = title
            Log.d(TAG, "Tracked last conversation title='$title'")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "InstagramReaderService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterActionReceiver()
        cancelControlNotification()
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
                    ACTION_DUMP_TREE -> runInInstagram { dumpActiveWindow("adb") }
                    ACTION_DUMP_ALL_WINDOWS -> runInInstagram { dumpAllWindows("adb") }
                    ACTION_LONG_PRESS_FIRST_REEL ->
                        runInInstagram { longPressFirstReel(afterLongPress = AfterLongPress.DumpAllWindows) }
                    ACTION_REACT_HEART ->
                        runInInstagram { longPressFirstReel(afterLongPress = AfterLongPress.TapReaction("❤")) }
                    ACTION_REACT_LAUGH ->
                        runInInstagram { longPressFirstReel(afterLongPress = AfterLongPress.TapReaction("😂")) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP_TREE)
            addAction(ACTION_DUMP_ALL_WINDOWS)
            addAction(ACTION_LONG_PRESS_FIRST_REEL)
            addAction(ACTION_REACT_HEART)
            addAction(ACTION_REACT_LAUGH)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        actionReceiver = receiver
        Log.i(
            TAG,
            "Action receiver registered (dump=$ACTION_DUMP_TREE, dumpAll=$ACTION_DUMP_ALL_WINDOWS, " +
                "longpress=$ACTION_LONG_PRESS_FIRST_REEL, heart=$ACTION_REACT_HEART, laugh=$ACTION_REACT_LAUGH)"
        )
    }

    private fun unregisterActionReceiver() {
        actionReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { /* ignore */ }
        }
        actionReceiver = null
    }

    // ---------------------------------------------------------------------
    // Foreground helper — bring Instagram in front before acting
    // ---------------------------------------------------------------------

    /**
     * Run [action] once the Instagram app is the foreground / active window.
     *
     * If Instagram is already in front, [action] runs immediately.
     * Otherwise the service launches Instagram (retaining the last screen the
     * user was on, since the standard launch intent resumes the existing
     * task) and polls until IG becomes the active window or a small timeout
     * expires.
     *
     * IMPORTANT: we use only `FLAG_ACTIVITY_NEW_TASK`, matching what the
     * system launcher does when you tap the Instagram icon. We deliberately
     * do NOT add `FLAG_ACTIVITY_REORDER_TO_FRONT` because that flag causes
     * Instagram to reset the task back to its root activity (the inbox
     * list), losing the conversation the user was on. `NEW_TASK` alone
     * brings the existing task to the front in its current state — same as
     * pressing Home and re-opening the app icon.
     */
    private fun runInInstagram(action: () -> Unit) {
        val currentPkg = rootInActiveWindow?.packageName?.toString()
        if (currentPkg == IgSelectors.IG_PACKAGE) {
            action()
            return
        }
        Log.i(TAG, "runInInstagram: foreground is '$currentPkg', bringing Instagram to front.")
        val launch = packageManager.getLaunchIntentForPackage(IgSelectors.IG_PACKAGE)
        if (launch == null) {
            Log.e(TAG, "runInInstagram: Instagram is not installed (no launch intent).")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            Log.e(TAG, "runInInstagram: failed to launch Instagram", e)
            return
        }
        pollInstagramForeground(retriesLeft = FOREGROUND_POLL_MAX_RETRIES, action = action)
    }

    private fun pollInstagramForeground(retriesLeft: Int, action: () -> Unit) {
        val currentPkg = rootInActiveWindow?.packageName?.toString()
        if (currentPkg == IgSelectors.IG_PACKAGE) {
            Log.i(TAG, "runInInstagram: Instagram now in foreground, running action.")
            action()
            return
        }
        if (retriesLeft <= 0) {
            Log.w(TAG, "runInInstagram: gave up waiting for Instagram to come to front (last=$currentPkg).")
            return
        }
        mainHandler.postDelayed(
            { pollInstagramForeground(retriesLeft - 1, action) },
            FOREGROUND_POLL_INTERVAL_MS
        )
    }

    // ---------------------------------------------------------------------
    // PoC-3 / PoC-5 — long-press on the first Reel + optional follow-up
    // ---------------------------------------------------------------------

    /** What to do once the long-press gesture has (probably) opened the menu. */
    private sealed class AfterLongPress {
        object DumpAllWindows : AfterLongPress()
        data class TapReaction(val emoji: String) : AfterLongPress()
    }

    private fun longPressFirstReel(afterLongPress: AfterLongPress) {
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
                "bounds=${bounds.toShortString()} center=(${bounds.centerX()},${bounds.centerY()}) " +
                "afterLongPress=${afterLongPress::class.simpleName}"
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

        val settleMs = LONG_PRESS_DURATION_MS + POST_LONG_PRESS_SETTLE_MS
        when (afterLongPress) {
            AfterLongPress.DumpAllWindows -> {
                mainHandler.postDelayed({ dumpAllWindows("after-longpress") }, settleMs)
            }
            is AfterLongPress.TapReaction -> {
                mainHandler.postDelayed({ tapQuickReaction(afterLongPress.emoji) }, settleMs)
            }
        }
    }

    /**
     * After the long-press has opened the reaction row, find the ImageView
     * corresponding to [emoji] (e.g. "❤" or "😂") and click it. The reaction
     * row lives on the MAIN IG window (not on the context menu popup), so
     * rootInActiveWindow is sufficient here, but for robustness we search
     * across every currently visible window.
     */
    private fun tapQuickReaction(emoji: String) {
        val expectedDescriptions = IgSelectors.ContextMenu.quickReactionDescriptions(emoji)
        Log.i(TAG, "REACT: looking for quick-reaction emoji '$emoji' (desc in $expectedDescriptions)")

        val emojiNode = findFirstNodeAcrossWindows { node ->
            val desc = node.contentDescription?.toString() ?: return@findFirstNodeAcrossWindows false
            expectedDescriptions.contains(desc)
        }
        if (emojiNode == null) {
            Log.w(TAG, "REACT: could not find quick-reaction emoji '$emoji'. Was the context menu open?")
            return
        }

        val clicked = emojiNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val emojiBounds = Rect().also { emojiNode.getBoundsInScreen(it) }
        Log.i(
            TAG,
            "REACT: performAction(ACTION_CLICK) on emoji '$emoji' returned $clicked " +
                "bounds=${emojiBounds.toShortString()}"
        )
    }

    /**
     * Depth-first search across every window currently reported by the
     * accessibility framework, returning the first node that satisfies
     * [predicate].
     */
    private fun findFirstNodeAcrossWindows(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val allWindows = try { windows } catch (_: Exception) { emptyList() }
        val roots = allWindows.orEmpty().mapNotNull { it.root } +
            listOfNotNull(rootInActiveWindow)
        val seen = HashSet<AccessibilityNodeInfo>()
        for (root in roots) {
            if (!seen.add(root)) continue
            val hit = findFirstNodeInSubtree(root, predicate)
            if (hit != null) return hit
        }
        return null
    }

    private fun findFirstNodeInSubtree(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
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

    /**
     * Dump every window currently reported by the accessibility framework.
     *
     * The active/main app window is what `rootInActiveWindow` returns, but
     * popups, dialogs, bottom sheets and context menus are frequently placed
     * in separate windows layered on top. Instagram's message context menu
     * (Reply / Copy link / Forward / Delete) is one such case.
     *
     * Requires `flagRetrieveInteractiveWindows` in the service config, which
     * is already enabled in accessibility_service_config.xml.
     */
    private fun dumpAllWindows(reason: String) {
        val allWindows = try { windows } catch (_: Exception) { emptyList() }
        if (allWindows.isNullOrEmpty()) {
            Log.w(TAG, "DUMP_ALL ($reason): windows() returned empty; falling back to active window.")
            dumpActiveWindow(reason)
            return
        }
        Log.i(TAG, "===== DUMP_ALL START reason=$reason windowCount=${allWindows.size} =====")
        for ((index, window) in allWindows.withIndex()) {
            val root = window.root
            val pkg = root?.packageName?.toString() ?: "?"
            val type = when (window.type) {
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
                else -> "OTHER(${window.type})"
            }
            val bounds = android.graphics.Rect().also { window.getBoundsInScreen(it) }
            val isActive = window.isActive
            val isFocused = window.isFocused
            Log.i(
                TAG,
                "----- WINDOW[$index] id=${window.id} type=$type pkg=$pkg " +
                    "active=$isActive focused=$isFocused bounds=${bounds.toShortString()} -----"
            )
            if (root == null) {
                Log.i(TAG, "  (no root node)")
            } else {
                dumpNode(root, 1)
            }
        }
        Log.i(TAG, "===== DUMP_ALL END reason=$reason =====")
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
        private const val POST_LONG_PRESS_SETTLE_MS = 1500L
        private const val FOREGROUND_POLL_INTERVAL_MS = 200L
        private const val FOREGROUND_POLL_MAX_RETRIES = 20 // ~4s total

        const val ACTION_DUMP_TREE = "com.example.friendsreels.ACTION_DUMP_TREE"
        const val ACTION_DUMP_ALL_WINDOWS = "com.example.friendsreels.ACTION_DUMP_ALL_WINDOWS"
        const val ACTION_LONG_PRESS_FIRST_REEL = "com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL"
        const val ACTION_REACT_HEART = "com.example.friendsreels.ACTION_REACT_HEART"
        const val ACTION_REACT_LAUGH = "com.example.friendsreels.ACTION_REACT_LAUGH"

        private const val NOTIF_CHANNEL_ID = "friends_reels_controls"
        private const val NOTIF_ID = 1001
    }

    // ---------------------------------------------------------------------
    // Control notification — persistent notification with action buttons.
    //
    // Tapping a button inside the notification shade closes the shade and
    // returns focus to whatever app was underneath (typically Instagram in
    // the exact conversation the user was on). This avoids the foreground
    // switch that happens when the user leaves IG to tap a button inside our
    // MainActivity, which is why this is the recommended way to trigger the
    // PoC actions.
    // ---------------------------------------------------------------------

    private fun postControlNotification() {
        createNotificationChannel()
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .addAction(
                0,
                getString(R.string.notif_action_heart),
                pendingBroadcast(ACTION_REACT_HEART, requestCode = 1)
            )
            .addAction(
                0,
                getString(R.string.notif_action_laugh),
                pendingBroadcast(ACTION_REACT_LAUGH, requestCode = 2)
            )
            .addAction(
                0,
                getString(R.string.notif_action_dump),
                pendingBroadcast(ACTION_DUMP_ALL_WINDOWS, requestCode = 3)
            )

        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled by the user — control notification skipped.")
            return
        }
        try {
            nm.notify(NOTIF_ID, builder.build())
            Log.i(TAG, "Control notification posted (id=$NOTIF_ID).")
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission — control notification skipped.", e)
        }
    }

    private fun cancelControlNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        val system = getSystemService(NotificationManager::class.java)
        system?.createNotificationChannel(channel)
    }

    private fun pendingBroadcast(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, requestCode, intent, flags)
    }
}
