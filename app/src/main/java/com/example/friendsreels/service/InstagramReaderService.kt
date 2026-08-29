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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.friendsreels.R
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.data.ReelEntity
import com.example.friendsreels.instagram.Direction
import com.example.friendsreels.instagram.DmReelEntry
import com.example.friendsreels.instagram.IgSelectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Accessibility service used to read and drive Instagram screens.
 *
 * All operations are broadcast-triggered so we can invoke them from `adb`,
 * from a button inside the app, or from the persistent control notification
 * posted in [postControlNotification]. The public actions are declared in
 * the companion object at the bottom of this file.
 *
 * Active surface (session-24 cleanup — exploratory buttons removed):
 *
 * PoC-4/5 (react): after long-pressing the first RECEIVED Reel bubble in
 * the visible conversation, clicks a preset emoji on the reaction row.
 * Reactions default to `Direction.RECEIVED` only, controlled by the
 * persisted `ignore_sent_reels` preference.
 * Actions: [ACTION_REACT_HEART], [ACTION_REACT_LAUGH].
 *
 * PoC-6 (reply): same long-press flow but picks "Responder" from the
 * context menu popup, types the fixed [MOCK_REPLY_TEXT] into the composer
 * via ACTION_SET_TEXT, then clicks Send (with a `dispatchGesture` fallback
 * if Compose refuses the a11y click).
 * Action: [ACTION_REPLY_FIRST_REEL_MOCK].
 *
 * PoC-7 (copy URL): taps the bubble to open the Reel viewer, reads
 * `sender_username_or_fullname` to capture the human sender (matters for
 * groups), clicks Partilhar → "Copiar ligação", bridges the clipboard
 * read via the invisible ClipboardCaptureActivity (Android 10+ blocks
 * clipboard reads for background apps), and persists a fully enriched
 * [ReelEntity] into Room. Returns the user to the conversation with two
 * BACK gestures.
 * Action: [ACTION_COPY_REEL_URL] + internal [ACTION_CLIPBOARD_CAPTURED].
 *
 * PoC-8 (discover + feed): enumerates every Reel bubble currently visible
 * in the open conversation, respects the `ignoreSent` flag, and inserts
 * new rows into Room (deduplicating by `(threadTitle, reelAuthor,
 * direction)` until we have a URL, then by URL unique index).
 * Action: [ACTION_DISCOVER_REELS].
 */
class InstagramReaderService : AccessibilityService() {

    private var actionReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Header title of the last Instagram conversation the user visibly opened.
     * Captured from `header_title` in the thread screen. Used as a defensive
     * hint (e.g. logs and future navigation) — restoring the exact thread is
     * currently not needed because bringing IG to front without
     * `FLAG_ACTIVITY_REORDER_TO_FRONT` already resumes the last screen.
     */
    private var lastKnownConversationTitle: String? = null

    /**
     * Context captured when the user requests `ACTION_COPY_REEL_URL`. Held
     * from the moment we tap the bubble to open the viewer until the
     * `ACTION_CLIPBOARD_CAPTURED` broadcast arrives with the URL — at
     * which point we upsert a fully enriched [ReelEntity] into Room.
     */
    private data class PendingCopy(
        val threadTitle: String,
        val direction: Direction,
        val reelAuthor: String?,
        val kind: String,
        val bubbleIndex: Int,
        val dmSender: String? = null,
    )

    private var pendingCopy: PendingCopy? = null

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
        serviceScope.cancel()
    }

    // ---------------------------------------------------------------------
    // Broadcast receiver wiring
    // ---------------------------------------------------------------------

    private fun registerActionReceiver() {
        if (actionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_REACT_HEART ->
                        runInInstagram { longPressFirstReel(afterLongPress = AfterLongPress.TapReaction("❤")) }
                    ACTION_REACT_LAUGH ->
                        runInInstagram { longPressFirstReel(afterLongPress = AfterLongPress.TapReaction("😂")) }
                    ACTION_REPLY_FIRST_REEL_MOCK ->
                        runInInstagram {
                            longPressFirstReel(afterLongPress = AfterLongPress.ReplyWithText(MOCK_REPLY_TEXT))
                        }
                    ACTION_COPY_REEL_URL -> runInInstagram { openFirstReelViewer() }
                    ACTION_CLIPBOARD_CAPTURED -> handleClipboardCaptured(intent)
                    ACTION_DISCOVER_REELS -> runInInstagram { discoverReels() }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_REACT_HEART)
            addAction(ACTION_REACT_LAUGH)
            addAction(ACTION_REPLY_FIRST_REEL_MOCK)
            addAction(ACTION_COPY_REEL_URL)
            addAction(ACTION_CLIPBOARD_CAPTURED)
            addAction(ACTION_DISCOVER_REELS)
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
            "Action receiver registered ($BUILD_TAG heart=$ACTION_REACT_HEART, " +
                "laugh=$ACTION_REACT_LAUGH, reply=$ACTION_REPLY_FIRST_REEL_MOCK, " +
                "copyUrl=$ACTION_COPY_REEL_URL, discover=$ACTION_DISCOVER_REELS)"
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
        data class ReplyWithText(val text: String) : AfterLongPress()
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
            is AfterLongPress.ReplyWithText -> {
                mainHandler.postDelayed({ openReplyAndSend(afterLongPress.text) }, settleMs)
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

    // ---------------------------------------------------------------------
    // PoC-7 — Open the Reel viewer, tap Partilhar, click Copiar ligação
    //
    // Deep-tap on the visible Reel bubble opens Instagram's native viewer.
    // Once the viewer settles we read `sender_username_or_fullname` (the
    // human sender of the Reel in the DM, critical for group threads),
    // then click Partilhar. The IG share sheet has a "Copiar ligação"
    // entry in `direct_external_reshare_row`; we click it, wait for the
    // clipboard to be written, and bridge the read through the invisible
    // ClipboardCaptureActivity (background reads are blocked on API 29+).
    // Finally the URL + captured context are persisted into Room and two
    // BACK gestures return the user to the conversation.
    // ---------------------------------------------------------------------

    private fun openFirstReelViewer() {
        val igWindow = findIgApplicationWindow()
        val root = igWindow?.root ?: rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "OPEN_REEL requested but no Instagram root node available.")
            return
        }
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "OPEN_REEL ignored: foreground is ${root.packageName}, expected ${IgSelectors.IG_PACKAGE}.")
            return
        }
        val windowBounds = igWindow?.let { w -> Rect().also { w.getBoundsInScreen(it) } }

        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "OPEN_REEL: no message_list found. Are you on a conversation screen?")
            return
        }

        val target = findFirstReelBubble(
            messageList,
            onlyDirection = if (isIgnoreSentEnabled()) Direction.RECEIVED else null,
        )
        if (target == null) {
            Log.w(
                TAG,
                "OPEN_REEL: no eligible Reel bubble found (ignoreSent=${isIgnoreSentEnabled()})."
            )
            return
        }
        val bounds = Rect(target.bounds)
        Log.i(
            TAG,
            "OPEN_REEL: target index=${target.index} kind=${target.kind} direction=${target.direction} " +
                "author=${target.reelAuthor} bounds=${bounds.toShortString()} center=(${bounds.centerX()},${bounds.centerY()})"
        )
        if (windowBounds != null && !windowBounds.contains(bounds.centerX(), bounds.centerY())) {
            Log.w(TAG, "OPEN_REEL: bubble center outside IG window bounds — refusing to tap off-screen.")
            return
        }

        // Stash context so we can persist a fully enriched Row once the
        // clipboard capture broadcast comes back in `handleClipboardCaptured`.
        pendingCopy = PendingCopy(
            threadTitle = lastKnownConversationTitle?.takeIf { it.isNotBlank() } ?: "?",
            direction = target.direction,
            reelAuthor = target.reelAuthor,
            kind = target.kind,
            bubbleIndex = target.index,
        )
        Log.i(TAG, "COPY_LINK: pendingCopy=$pendingCopy")

        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "OPEN_REEL: tap gesture completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "OPEN_REEL: tap gesture cancelled by system")
            }
        }, mainHandler)
        Log.i(TAG, "OPEN_REEL: dispatchGesture accepted=$dispatched duration=${TAP_DURATION_MS}ms")

        val delay = TAP_DURATION_MS + REEL_VIEWER_SETTLE_MS
        mainHandler.postDelayed({ tapShareInReelViewer() }, delay)
    }

    /**
     * Click the "Partilhar" (share) button on the Reel viewer. Before the
     * click, capture `sender_username_or_fullname` (the human sender of the
     * Reel in the DM — critical for group threads). After the share sheet
     * settles, chain into [clickCopyLinkInShareSheet].
     */
    private fun tapShareInReelViewer() {
        // While the viewer is stable (right before we tap Share), grab the
        // human sender from `sender_username_or_fullname`. This is what
        // lets us know WHO shared the Reel — critical in group DMs where
        // the thread title is the group name, not a person.
        enrichPendingCopyFromViewer()

        val shareId = IgSelectors.id(IgSelectors.ReelViewer.UFI_SHARE_BUTTON)
        val shareNode = findFirstNodeAcrossWindows { it.viewIdResourceName == shareId }
        if (shareNode == null) {
            Log.w(TAG, "SHARE_IN_VIEWER: '${IgSelectors.ReelViewer.UFI_SHARE_BUTTON}' not found. Viewer failed to open?")
            dumpAllWindows("share-not-found")
            return
        }
        val bounds = Rect().also { shareNode.getBoundsInScreen(it) }
        val target = if (shareNode.isClickable) shareNode else findClickableAncestor(shareNode) ?: shareNode
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(
            TAG,
            "SHARE_IN_VIEWER: performAction(ACTION_CLICK) on 'Partilhar' returned $ok bounds=${bounds.toShortString()}"
        )
        // The IG share sheet fetches the friends grid — give it time.
        mainHandler.postDelayed({ clickCopyLinkInShareSheet() }, SHARE_SHEET_SETTLE_MS)
    }

    /**
     * Find the "Copiar ligação" entry inside the IG share sheet's external
     * reshare row and click it. Every button in the row exposes the generic
     * `id/button` resource id, so we identify by `contentDescription`
     * against [IgSelectors.ReelViewer.COPY_LINK_LABELS]. After the click,
     * IG copies the Reel URL into the system clipboard and shows a toast;
     * we wait a short moment and then read the clipboard.
     */
    private fun clickCopyLinkInShareSheet() {
        val labels = IgSelectors.ReelViewer.COPY_LINK_LABELS
        val copyNode = findFirstNodeAcrossWindows { node ->
            val desc = node.contentDescription?.toString() ?: return@findFirstNodeAcrossWindows false
            labels.contains(desc)
        }
        if (copyNode == null) {
            Log.w(TAG, "COPY_LINK: 'Copiar ligação' not found in share sheet (labels=$labels).")
            dumpAllWindows("copy-link-not-found")
            return
        }
        val bounds = Rect().also { copyNode.getBoundsInScreen(it) }
        val ok = clickWithGestureFallback(copyNode, bounds, "COPY_LINK")
        if (!ok) {
            Log.w(TAG, "COPY_LINK: click on 'Copiar ligação' failed via both performAction and dispatchGesture.")
            return
        }
        mainHandler.postDelayed({ readReelUrlFromClipboard() }, CLIPBOARD_READ_DELAY_MS)
    }

    /**
     * Try `performAction(ACTION_CLICK)` on the closest clickable ancestor of
     * [node]; if that returns false (some Compose widgets in Instagram expose
     * isClickable=true but reject the a11y click action) fall back to a real
     * tap gesture at the centre of [bounds]. Returns true iff either path
     * succeeded to schedule the touch.
     */
    private fun clickWithGestureFallback(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        tag: String,
    ): Boolean {
        val target = if (node.isClickable) node else findClickableAncestor(node) ?: node
        val performed = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "$tag: performAction(ACTION_CLICK) returned $performed bounds=${bounds.toShortString()}")
        if (performed) return true
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w(TAG, "$tag: cannot fall back to gesture, empty bounds.")
            return false
        }
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "$tag: dispatchGesture fallback completed")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "$tag: dispatchGesture fallback cancelled")
            }
        }, mainHandler)
        Log.i(TAG, "$tag: dispatchGesture fallback accepted=$dispatched")
        return dispatched
    }

    /**
     * Bridge to actually read the clipboard.
     *
     * `AccessibilityService` cannot read `ClipboardManager.primaryClip`
     * silently on Android 10+: the framework returns an empty clip when the
     * caller is not the foreground / IME app. To get around that, we launch
     * an invisible [ClipboardCaptureActivity] which is briefly foreground,
     * reads the clip and broadcasts it back to the service.
     *
     * The BACK gestures that close the share sheet + Reel viewer are then
     * dispatched only after we receive the clipboard (see
     * [handleClipboardCaptured]).
     */
    private fun readReelUrlFromClipboard() {
        val intent = Intent(this, com.example.friendsreels.ClipboardCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
            Log.i(TAG, "COPY_LINK: launched ClipboardCaptureActivity to bridge the read.")
        } catch (e: Exception) {
            Log.w(TAG, "COPY_LINK: failed to launch ClipboardCaptureActivity", e)
        }
    }

    /**
     * Called when [ClipboardCaptureActivity] delivers the clipboard content
     * back via a broadcast. Logs the URL, persists a fully enriched
     * [ReelEntity] into Room (integrating PoC-7 into PoC-8), then
     * schedules the BACK gestures so the user ends up back on the
     * conversation.
     */
    private fun handleClipboardCaptured(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT)
        val pending = pendingCopy
        pendingCopy = null
        if (url.isNullOrBlank()) {
            Log.w(TAG, "COPY_LINK: ClipboardCaptureActivity returned empty text.")
        } else {
            Log.i(TAG, "COPY_LINK: Reel URL = '$url'")
            if (pending != null) {
                persistCopiedReel(pending, url)
            } else {
                Log.w(TAG, "COPY_LINK: no pendingCopy context — URL not persisted to DB.")
            }
        }
        // Close the share sheet + the Reel viewer so the user is back on
        // the conversation. Two BACKs: first closes whatever is on top,
        // second closes the viewer.
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, BACK_AFTER_COPY_DELAY_MS)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, BACK_AFTER_COPY_DELAY_MS * 2)
    }

    /**
     * Read the human sender name from the currently-open Reel viewer
     * (`sender_username_or_fullname`) and stash it on the pending copy
     * context so it can be persisted alongside the URL. If we can't find
     * the node (viewer not open, unusual layout, etc.) we leave the
     * existing value alone.
     */
    private fun enrichPendingCopyFromViewer() {
        val pending = pendingCopy ?: return
        val root = findIgApplicationWindow()?.root ?: rootInActiveWindow ?: return
        val senderId = IgSelectors.id(IgSelectors.ReelViewer.SENDER_USERNAME_OR_FULLNAME)
        val senderNode = root.findAccessibilityNodeInfosByViewId(senderId)?.firstOrNull()
        val dmSender = senderNode?.text?.toString()?.takeIf { it.isNotBlank() }
        if (dmSender != null && dmSender != pending.dmSender) {
            pendingCopy = pending.copy(dmSender = dmSender)
            Log.i(TAG, "COPY_LINK: enriched pendingCopy with dmSender='$dmSender' from viewer.")
        } else if (dmSender == null) {
            Log.w(TAG, "COPY_LINK: sender_username_or_fullname not found in viewer.")
        }
    }

    /**
     * Persist the enriched Reel context + URL into Room. If the URL is new
     * a fresh row is inserted; if a row already exists for this URL, only
     * `dmSender` is backfilled (when previously null).
     */
    private fun persistCopiedReel(pending: PendingCopy, url: String) {
        val discoveredAt = System.currentTimeMillis()
        val row = ReelEntity(
            threadTitle = pending.threadTitle,
            reelAuthor = pending.reelAuthor,
            dmSender = pending.dmSender,
            direction = pending.direction.name,
            kind = pending.kind,
            bubbleIndex = pending.bubbleIndex,
            reelUrl = url,
            discoveredAt = discoveredAt,
        )
        serviceScope.launch {
            val dao = AppDatabase.get(this@InstagramReaderService).reelDao()
            val id = dao.insert(row)
            if (id > 0) {
                val total = dao.count()
                Log.i(
                    TAG,
                    "COPY_LINK: inserted row id=$id thread='${pending.threadTitle}' " +
                        "author=${pending.reelAuthor} dmSender=${pending.dmSender} totalInDb=$total."
                )
            } else {
                val updated = dao.updateDmSenderByUrl(url, pending.dmSender)
                Log.i(
                    TAG,
                    "COPY_LINK: URL already in DB — backfilled dmSender rows=$updated " +
                        "(dmSender=${pending.dmSender})."
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // PoC-6 — Reply to the first Reel with a fixed mock text
    // ---------------------------------------------------------------------

    /**
     * Runs after the long-press has opened the context menu popup. Clicks
     * the "Reply" item, then chains through the composer settle → set text
     * → click send flow. Every step logs its outcome. If any step cannot
     * find the required node, an automatic dump is issued so we can see
     * exactly what the IG UI looked like at that moment.
     */
    private fun openReplyAndSend(text: String) {
        val replyLabels = IgSelectors.ContextMenu.ACTION_REPLY
        Log.i(TAG, "REPLY: looking for context menu item 'Responder' (labels=$replyLabels)")

        val replyItem = findFirstNodeAcrossWindows { node ->
            val id = node.viewIdResourceName ?: return@findFirstNodeAcrossWindows false
            if (id != IgSelectors.id(IgSelectors.ContextMenu.CONTEXT_MENU_ITEM)) return@findFirstNodeAcrossWindows false
            val desc = node.contentDescription?.toString()
            desc != null && replyLabels.contains(desc)
        }
        if (replyItem == null) {
            Log.w(TAG, "REPLY: 'Responder' item not found. Was the context menu open?")
            mainHandler.postDelayed({ dumpAllWindows("reply-no-menu") }, 200L)
            return
        }
        val clicked = replyItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "REPLY: performAction(ACTION_CLICK) on 'Responder' returned $clicked")

        mainHandler.postDelayed({ typeInComposer(text) }, COMPOSER_SETTLE_MS)
    }

    /**
     * Locate the composer EditText and inject [text] via ACTION_SET_TEXT.
     * ACTION_SET_TEXT bypasses the IME entirely and works for Compose text
     * fields that expose CharSequence writes through the a11y layer, which
     * covers the current IG composer (validated on OnePlus Nord 5).
     */
    private fun typeInComposer(text: String) {
        val composer = findFirstNodeAcrossWindows { node ->
            node.viewIdResourceName == IgSelectors.id(IgSelectors.Thread.COMPOSER_EDITTEXT)
        }
        if (composer == null) {
            Log.w(TAG, "REPLY: composer EditText not found after settle.")
            mainHandler.postDelayed({ dumpAllWindows("reply-no-composer") }, 200L)
            return
        }

        // Sanity check: the reply preview strip should be up. If it isn't,
        // we can still try to set text (the reaction just won't be tied to
        // the original message), but log a warning to help future debugging.
        val hasReplyBar = findFirstNodeAcrossWindows { node ->
            node.viewIdResourceName == IgSelectors.id(IgSelectors.Thread.COMPOSER_REPLY_BAR_CONTAINER)
        } != null
        if (!hasReplyBar) {
            Log.w(TAG, "REPLY: composer visible but reply-preview bar not detected; sending anyway.")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "REPLY: performAction(ACTION_SET_TEXT text='$text') returned $setOk hasReplyBar=$hasReplyBar")

        mainHandler.postDelayed({ clickSendButton() }, SEND_SETTLE_MS)
    }

    /**
     * Find the composer's Send button. IG's resource id for this button has
     * moved around between builds, so we probe a list of known candidates
     * (see [IgSelectors.Thread.COMPOSER_SEND_BUTTON_CANDIDATES]) and fall
     * back to matching on the localized contentDescription ("Enviar" /
     * "Send"). If none of those hits, we issue an `after-set-text` dump so
     * the missing id can be added to the candidate list next time.
     */
    private fun clickSendButton() {
        val candidateIds = IgSelectors.Thread.COMPOSER_SEND_BUTTON_CANDIDATES
            .map { IgSelectors.id(it) }
            .toSet()
        val sendLabels = IgSelectors.Thread.COMPOSER_SEND_LABELS

        val sendNode = findFirstNodeAcrossWindows { node ->
            val id = node.viewIdResourceName
            if (id != null && candidateIds.contains(id)) return@findFirstNodeAcrossWindows true
            val desc = node.contentDescription?.toString() ?: return@findFirstNodeAcrossWindows false
            sendLabels.contains(desc)
        }

        if (sendNode == null) {
            Log.w(
                TAG,
                "REPLY: send button not found (tried ids=${IgSelectors.Thread.COMPOSER_SEND_BUTTON_CANDIDATES} " +
                    "desc=$sendLabels). Emitting after-set-text dump so we can capture the real id."
            )
            dumpAllWindows("after-set-text")
            return
        }
        val bounds = Rect().also { sendNode.getBoundsInScreen(it) }
        val clickable = sendNode.isClickable
        // Some builds mark the icon child non-clickable but the parent Button
        // is; walk up until we hit a clickable ancestor if needed.
        val target = if (clickable) sendNode else findClickableAncestor(sendNode) ?: sendNode
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(
            TAG,
            "REPLY: send button click returned $ok id=${sendNode.viewIdResourceName} " +
                "desc=${sendNode.contentDescription} bounds=${bounds.toShortString()}"
        )
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
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
    // PoC-4/8 — Enumerate Reel bubbles and persist to Room
    // ---------------------------------------------------------------------

    /**
     * Enumerate every Reel currently visible in the open conversation and
     * insert new ones into the Room database (see `data/ReelEntity`).
     * Deduplication is by `(threadTitle, reelAuthor, direction)` — good
     * enough while we don't have the canonical URL for every Reel.
     */
    private fun discoverReels() {
        val igWindow = findIgApplicationWindow()
        val root = igWindow?.root ?: rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "DISCOVER requested but no Instagram root node available.")
            return
        }
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "DISCOVER ignored: foreground is ${root.packageName}, expected ${IgSelectors.IG_PACKAGE}.")
            return
        }
        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "DISCOVER: no message_list found. Are you on a conversation screen?")
            return
        }

        val threadTitle = lastKnownConversationTitle?.takeIf { it.isNotBlank() } ?: "?"
        val ignoreSent = isIgnoreSentEnabled()
        val allEntries = enumerateReels(messageList)
        val visibleReceived = allEntries.count { it.direction == Direction.RECEIVED }
        val visibleSent = allEntries.size - visibleReceived
        val kept = if (ignoreSent) allEntries.filter { it.direction == Direction.RECEIVED } else allEntries
        if (kept.isEmpty()) {
            Log.i(
                TAG,
                "DISCOVER: no Reels to persist for thread='$threadTitle' " +
                    "(visibleReceived=$visibleReceived visibleSent=$visibleSent ignoreSent=$ignoreSent)."
            )
            return
        }
        // Snapshot into plain data so we can leave the a11y thread.
        val snapshot = kept.map {
            Snapshot(
                index = it.index,
                kind = it.kind,
                direction = it.direction,
                author = it.reelAuthor,
            )
        }
        val discoveredAt = System.currentTimeMillis()
        serviceScope.launch {
            val dao = AppDatabase.get(this@InstagramReaderService).reelDao()
            var inserted = 0
            var skipped = 0
            for (s in snapshot) {
                val existing = dao.countMatching(threadTitle, s.author, s.direction.name)
                if (existing > 0) {
                    skipped++
                    continue
                }
                val row = ReelEntity(
                    threadTitle = threadTitle,
                    reelAuthor = s.author,
                    // dmSender is only populated via the enriched copy-link
                    // flow (viewer open). Fast discovery leaves it null.
                    dmSender = null,
                    direction = s.direction.name,
                    kind = s.kind,
                    bubbleIndex = s.index,
                    reelUrl = null,
                    discoveredAt = discoveredAt,
                )
                val id = dao.insert(row)
                if (id > 0) inserted++ else skipped++
            }
            val total = dao.count()
            Log.i(
                TAG,
                "DISCOVER: thread='$threadTitle' visibleReceived=$visibleReceived visibleSent=$visibleSent " +
                    "ignoreSent=$ignoreSent kept=${snapshot.size} inserted=$inserted skipped=$skipped totalInDb=$total"
            )
        }
    }

    private data class Snapshot(
        val index: Int,
        val kind: String,
        val direction: Direction,
        val author: String?,
    )

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

        /**
         * Bumped every time we change the shape of the service (new actions,
         * removed actions, DB schema tweaks). Utility for the user to
         * confirm which build is actually running on the device — it shows
         * up at the top of every `Action receiver registered` log line.
         */
        private const val BUILD_TAG = "build=s24"

        private const val LONG_PRESS_DURATION_MS = 600L
        private const val POST_LONG_PRESS_SETTLE_MS = 1500L
        private const val COMPOSER_SETTLE_MS = 900L // context menu closes + reply preview + composer focus
        private const val SEND_SETTLE_MS = 500L // wait for the voice/gallery strip to become the Send button
        private const val TAP_DURATION_MS = 80L // short click gesture for opening a Reel bubble
        private const val REEL_VIEWER_SETTLE_MS = 2000L // Reel viewer needs to load the video/controls
        private const val SHARE_SHEET_SETTLE_MS = 1800L // IG share sheet loads the friends grid, needs more time
        private const val CLIPBOARD_READ_DELAY_MS = 700L // wait for IG to write the URL after clicking "Copiar ligação"
        private const val BACK_AFTER_COPY_DELAY_MS = 400L // pause between BACK gestures to close sheet + viewer
        private const val FOREGROUND_POLL_INTERVAL_MS = 200L
        private const val FOREGROUND_POLL_MAX_RETRIES = 30 // ~6s total, enough for task-switch animations
        private const val MIN_REEL_BUBBLE_HEIGHT_PX = 200 // ignore stubs that are almost fully scrolled off

        /** Placeholder text used by the PoC-6 mock reply broadcast. */
        const val MOCK_REPLY_TEXT = "👀"

        // -----------------------------------------------------------------
        // Broadcast actions (kept ONLY for the buttons that survived the
        // session-24 cleanup: react ❤/😂, reply 👀, copy URL, discover).
        // Exploratory actions (dump, list, open+dump, open+more, open+share)
        // were removed to keep the UI focused. If you need to bring them
        // back, `git log --diff-filter=D` for the exact broadcast strings.
        // -----------------------------------------------------------------
        const val ACTION_REACT_HEART = "com.example.friendsreels.ACTION_REACT_HEART"
        const val ACTION_REACT_LAUGH = "com.example.friendsreels.ACTION_REACT_LAUGH"
        const val ACTION_REPLY_FIRST_REEL_MOCK = "com.example.friendsreels.ACTION_REPLY_FIRST_REEL_MOCK"
        const val ACTION_COPY_REEL_URL = "com.example.friendsreels.ACTION_COPY_REEL_URL"
        const val ACTION_DISCOVER_REELS = "com.example.friendsreels.ACTION_DISCOVER_REELS"

        /**
         * Broadcast sent by [com.example.friendsreels.ClipboardCaptureActivity]
         * with the current clipboard text. See [readReelUrlFromClipboard]
         * for why this indirection is needed on Android 10+.
         */
        const val ACTION_CLIPBOARD_CAPTURED = "com.example.friendsreels.ACTION_CLIPBOARD_CAPTURED"
        const val EXTRA_CLIPBOARD_TEXT = "clipboard_text"

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
                getString(R.string.notif_action_reply),
                pendingBroadcast(ACTION_REPLY_FIRST_REEL_MOCK, requestCode = 5)
            )
            .addAction(
                0,
                getString(R.string.notif_action_copy_url),
                pendingBroadcast(ACTION_COPY_REEL_URL, requestCode = 9)
            )
            .addAction(
                0,
                getString(R.string.notif_action_discover),
                pendingBroadcast(ACTION_DISCOVER_REELS, requestCode = 10)
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
