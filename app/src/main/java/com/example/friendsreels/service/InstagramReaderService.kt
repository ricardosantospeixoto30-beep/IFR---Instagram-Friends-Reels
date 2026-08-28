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
import com.example.friendsreels.instagram.Direction
import com.example.friendsreels.instagram.DmReelEntry
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
 *
 * PoC-4 (identify sender): before dispatching any action, every message
 * bubble inside `message_list` is enumerated with its Direction (RECEIVED
 * vs SENT) inferred from the presence of `sender_avatar`. Reactions default
 * to Direction.RECEIVED only, controlled by the persisted
 * `ignore_sent_reels` preference. The listing can also be dumped to logcat
 * via ACTION_LIST_REELS.
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
                    ACTION_LIST_REELS -> runInInstagram { listReels() }
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
            addAction(ACTION_LIST_REELS)
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
                "list=$ACTION_LIST_REELS, longpress=$ACTION_LONG_PRESS_FIRST_REEL, " +
                "heart=$ACTION_REACT_HEART, laugh=$ACTION_REACT_LAUGH)"
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
        if (isInstagramReady()) {
            action()
            return
        }
        val currentPkg = rootInActiveWindow?.packageName?.toString()
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

    /**
     * Instagram is considered "ready" when it is the foreground package AND
     * the a11y layer reports its APPLICATION window aligned with the display
     * origin. When IG is brought to front from another app it is animated
     * in from the right; during the animation `getBoundsInScreen` on the
     * IG window returns shifted values (e.g. left>0) that propagate down to
     * every child node. Dispatching a gesture then targets off-screen
     * coordinates. Waiting for `left==0` ensures the slide-in has finished.
     */
    private fun isInstagramReady(): Boolean {
        val currentPkg = rootInActiveWindow?.packageName?.toString()
        if (currentPkg != IgSelectors.IG_PACKAGE) return false
        val igWindow = findIgApplicationWindow() ?: return false
        val bounds = Rect().also { igWindow.getBoundsInScreen(it) }
        return bounds.left == 0 && bounds.width() > 0
    }

    private fun pollInstagramForeground(retriesLeft: Int, action: () -> Unit) {
        if (isInstagramReady()) {
            Log.i(TAG, "runInInstagram: Instagram now foreground + window settled, running action.")
            action()
            return
        }
        if (retriesLeft <= 0) {
            val pkg = rootInActiveWindow?.packageName?.toString()
            val bounds = findIgApplicationWindow()?.let {
                Rect().also { r -> it.getBoundsInScreen(r) }
            }
            Log.w(TAG, "runInInstagram: gave up waiting (pkg=$pkg, igWindowBounds=$bounds).")
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
        // IMPORTANT: on some devices `rootInActiveWindow` returns a tree whose
        // `getBoundsInScreen` values are offset from the actually rendered
        // Instagram window (observed on OnePlus Nord 5 / Android 16: the
        // same node reported x=1278..1723 via rootInActiveWindow while
        // walking `windows` reported x=147..593 — the second one matches the
        // real pixels visible on screen). Because dispatchGesture uses
        // absolute screen coordinates, using the wrong tree makes the
        // gesture land off-screen and IG never sees it. To avoid that we
        // always source the tree from the current Instagram APPLICATION
        // window in getWindows().
        val igWindow = findIgApplicationWindow()
        val root = igWindow?.root ?: rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "LONG_PRESS requested but no Instagram root node available.")
            return
        }
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "LONG_PRESS ignored: foreground is ${root.packageName}, expected ${IgSelectors.IG_PACKAGE}.")
            return
        }
        val windowBounds = igWindow?.let { w ->
            Rect().also { w.getBoundsInScreen(it) }
        }
        Log.i(
            TAG,
            "LONG_PRESS: sourcing tree from ${if (igWindow != null) "windows[APPLICATION]" else "rootInActiveWindow"} " +
                "windowBounds=${windowBounds?.toShortString() ?: "?"}"
        )

        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "LONG_PRESS: no message_list found. Are you on a conversation screen?")
            return
        }

        val target = findFirstReelBubble(
            messageList,
            onlyDirection = if (isIgnoreSentEnabled()) Direction.RECEIVED else null,
        )
        if (target == null) {
            Log.w(
                TAG,
                "LONG_PRESS: no eligible Reel bubble found (ignoreSent=${isIgnoreSentEnabled()}). " +
                    "Are you on a conversation with a received Reel visible?"
            )
            return
        }

        val bounds = Rect(target.bounds)
        Log.i(
            TAG,
            "LONG_PRESS: target index=${target.index} kind=${target.kind} " +
                "direction=${target.direction} author=${target.reelAuthor} " +
                "bounds=${bounds.toShortString()} center=(${bounds.centerX()},${bounds.centerY()}) " +
                "afterLongPress=${afterLongPress::class.simpleName}"
        )

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w(TAG, "LONG_PRESS: bubble has empty bounds; refusing to dispatch gesture.")
            return
        }

        if (windowBounds != null && !windowBounds.contains(bounds.centerX(), bounds.centerY())) {
            Log.w(
                TAG,
                "LONG_PRESS: center (${bounds.centerX()},${bounds.centerY()}) is OUTSIDE Instagram " +
                    "window bounds ${windowBounds.toShortString()}. Refusing to dispatch off-screen gesture."
            )
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

    /**
     * Find the AccessibilityWindowInfo hosting the Instagram foreground UI.
     * Prefers the active/focused APPLICATION window; falls back to any
     * APPLICATION window whose root's packageName is Instagram; finally
     * accepts any window rooted in Instagram.
     *
     * Using this window (instead of rootInActiveWindow) is important because
     * on some devices/OS builds `rootInActiveWindow` exposes an alternate
     * tree whose `getBoundsInScreen` values do not match the pixels actually
     * rendered on-screen — which breaks dispatchGesture targeting.
     */
    private fun findIgApplicationWindow(): android.view.accessibility.AccessibilityWindowInfo? {
        val all = try { windows } catch (_: Exception) { null } ?: return null
        val igWindows = all.filter { w ->
            val pkg = w.root?.packageName?.toString()
            pkg == IgSelectors.IG_PACKAGE
        }
        if (igWindows.isEmpty()) return null
        return igWindows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused)
        }
            ?: igWindows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }
            ?: igWindows.first()
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

    // ---------------------------------------------------------------------
    // PoC-4 — Enumerate Reel bubbles + detect sender direction
    // ---------------------------------------------------------------------

    /**
     * List every Reel bubble currently visible on the open conversation, with
     * direction (RECEIVED / SENT) and original Reel author. Result is logged.
     * Used as the read-only entry point for PoC-4 verification.
     */
    private fun listReels() {
        val igWindow = findIgApplicationWindow()
        val root = igWindow?.root ?: rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "LIST_REELS requested but no Instagram root node available.")
            return
        }
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "LIST_REELS ignored: foreground is ${root.packageName}, expected ${IgSelectors.IG_PACKAGE}.")
            return
        }
        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "LIST_REELS: no message_list found. Are you on a conversation screen?")
            return
        }

        val entries = enumerateReels(messageList)
        val received = entries.count { it.direction == Direction.RECEIVED }
        val sent = entries.size - received
        Log.i(
            TAG,
            "LIST_REELS: found ${entries.size} Reel bubble(s) — received=$received sent=$sent " +
                "conversation='$lastKnownConversationTitle' ignoreSent=${isIgnoreSentEnabled()}"
        )
        entries.forEach { r ->
            Log.i(
                TAG,
                "LIST_REELS[${r.index}]: dir=${r.direction} kind=${r.kind} " +
                    "author=${r.reelAuthor ?: "?"} bounds=${r.bounds.toShortString()}"
            )
        }
    }

    /**
     * Enumerate every `message_content` inside [messageList] that contains a
     * Reel share (portrait or generic XMA container). Direction is inferred
     * from the presence of `sender_avatar` inside the same bubble.
     *
     * Bubbles are returned in traversal order — which follows the
     * RecyclerView, so top-visible messages come first. Filtering by
     * on-screen size / direction is left to the caller.
     */
    private fun enumerateReels(messageList: AccessibilityNodeInfo): List<DmReelEntry> {
        val bubbles = messageList
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_CONTENT))
            .orEmpty()
        val portraitId = IgSelectors.id(IgSelectors.Thread.MESSAGE_MEDIA_PORTRAIT)
        val genericId = IgSelectors.id(IgSelectors.Thread.MESSAGE_MEDIA_GENERIC)
        val senderAvatarId = IgSelectors.id(IgSelectors.Thread.SENDER_AVATAR)
        val authorId = IgSelectors.id(IgSelectors.Thread.REEL_AUTHOR_USERNAME)

        val entries = mutableListOf<DmReelEntry>()
        var next = 0
        for (bubble in bubbles) {
            val portrait = bubble.findAccessibilityNodeInfosByViewId(portraitId)?.firstOrNull()
            val generic = bubble.findAccessibilityNodeInfosByViewId(genericId)?.firstOrNull()
            val media = portrait ?: generic ?: continue
            val kind = if (portrait != null) "portrait" else "generic"

            val direction = if (bubble.findAccessibilityNodeInfosByViewId(senderAvatarId).orEmpty().isNotEmpty()) {
                Direction.RECEIVED
            } else {
                Direction.SENT
            }

            val author = media.findAccessibilityNodeInfosByViewId(authorId)?.firstOrNull()?.text?.toString()
            val bounds = Rect().also { media.getBoundsInScreen(it) }
            entries += DmReelEntry(
                index = next++,
                kind = kind,
                direction = direction,
                reelAuthor = author,
                bounds = bounds,
                node = media,
            )
        }
        return entries
    }

    /**
     * Walk the message_list and return the tightest Reel-carrying subtree we
     * can find, filtered by [onlyDirection] (default: RECEIVED only, so we
     * never accidentally react to a Reel we sent ourselves). Passing null
     * disables the direction filter.
     *
     * The chosen candidate is the top-most bubble on screen (smallest
     * `bounds.top`) that also has a reasonable visible height. Bubbles at
     * the top of the RecyclerView are sometimes reported with just ~30 px
     * visible height while they scroll into view; long-pressing those hits
     * the partial area and IG doesn't recognise it as a message press.
     */
    private fun findFirstReelBubble(
        messageList: AccessibilityNodeInfo,
        onlyDirection: Direction? = Direction.RECEIVED,
    ): DmReelEntry? {
        return enumerateReels(messageList)
            .filter { r ->
                r.bounds.width() > 0 && r.bounds.height() >= MIN_REEL_BUBBLE_HEIGHT_PX
            }
            .filter { r -> onlyDirection == null || r.direction == onlyDirection }
            .minByOrNull { it.bounds.top }
    }

    private fun isIgnoreSentEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_IGNORE_SENT, PREF_IGNORE_SENT_DEFAULT)
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
        private const val FOREGROUND_POLL_MAX_RETRIES = 30 // ~6s total, enough for task-switch animations
        private const val MIN_REEL_BUBBLE_HEIGHT_PX = 200 // ignore stubs that are almost fully scrolled off

        const val ACTION_DUMP_TREE = "com.example.friendsreels.ACTION_DUMP_TREE"
        const val ACTION_DUMP_ALL_WINDOWS = "com.example.friendsreels.ACTION_DUMP_ALL_WINDOWS"
        const val ACTION_LIST_REELS = "com.example.friendsreels.ACTION_LIST_REELS"
        const val ACTION_LONG_PRESS_FIRST_REEL = "com.example.friendsreels.ACTION_LONG_PRESS_FIRST_REEL"
        const val ACTION_REACT_HEART = "com.example.friendsreels.ACTION_REACT_HEART"
        const val ACTION_REACT_LAUGH = "com.example.friendsreels.ACTION_REACT_LAUGH"

        /** SharedPreferences file shared between the UI and the service. */
        const val PREFS_NAME = "friends_reels_prefs"

        /**
         * When true, the reaction actions ignore Reels the user sent
         * themselves (`sender_avatar` absent). Default true — this matches
         * the MVP requirement of never re-reacting to our own shares.
         */
        const val PREF_IGNORE_SENT = "ignore_sent_reels"
        const val PREF_IGNORE_SENT_DEFAULT = true

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
                getString(R.string.notif_action_list),
                pendingBroadcast(ACTION_LIST_REELS, requestCode = 4)
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
