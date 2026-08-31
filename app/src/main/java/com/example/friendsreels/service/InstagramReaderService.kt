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
import com.example.friendsreels.data.PendingActionEntity
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
        restorePersistedBatchEnrichResult()
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
                    ACTION_DISCOVER_REELS_HISTORY -> runInInstagram { discoverReelsHistory() }
                    ACTION_APPLY_PENDING -> runInInstagram { applyPendingActions() }
                    ACTION_ENRICH_REEL_URL -> {
                        val reelId = intent.getLongExtra(EXTRA_REEL_ID, -1L)
                        if (reelId > 0) runInInstagram { enrichReelUrl(reelId) }
                        else Log.w(TAG, "ENRICH_URL: missing/invalid $EXTRA_REEL_ID extra")
                    }
                    ACTION_ENRICH_ALL_MISSING_URLS -> enrichAllMissingUrls()
                    ACTION_ENRICH_ALL_CANCEL -> cancelBatchEnrichment()
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
            addAction(ACTION_DISCOVER_REELS_HISTORY)
            addAction(ACTION_APPLY_PENDING)
            addAction(ACTION_ENRICH_REEL_URL)
            addAction(ACTION_ENRICH_ALL_MISSING_URLS)
            addAction(ACTION_ENRICH_ALL_CANCEL)
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
                "copyUrl=$ACTION_COPY_REEL_URL, discover=$ACTION_DISCOVER_REELS, " +
                "history=$ACTION_DISCOVER_REELS_HISTORY, applyPending=$ACTION_APPLY_PENDING, " +
                "enrichUrl=$ACTION_ENRICH_REEL_URL, enrichAll=$ACTION_ENRICH_ALL_MISSING_URLS, " +
                "enrichCancel=$ACTION_ENRICH_ALL_CANCEL)"
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
        dispatchLongPressOn(target, afterLongPress, windowBounds)
    }

    /**
     * Actually dispatch the long-press gesture on an already-located
     * [target] bubble and schedule the [afterLongPress] follow-up after
     * the settle window. Extracted from [longPressFirstReel] so the
     * batching executor (PoC-8 iter 4) can call it directly after
     * [locateReelWithScroll] finds the Reel that matches a queued row.
     *
     * [windowBounds] is passed in when the caller already has the IG
     * APPLICATION window bounds (avoids a re-lookup). Passing null makes
     * this method re-derive them.
     */
    private fun dispatchLongPressOn(
        target: DmReelEntry,
        afterLongPress: AfterLongPress,
        windowBounds: Rect? = null,
    ) {
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

        val resolvedWindowBounds = windowBounds
            ?: findIgApplicationWindow()?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
        if (resolvedWindowBounds != null &&
            !resolvedWindowBounds.contains(bounds.centerX(), bounds.centerY())
        ) {
            Log.w(
                TAG,
                "LONG_PRESS: center (${bounds.centerX()},${bounds.centerY()}) is OUTSIDE Instagram " +
                    "window bounds ${resolvedWindowBounds.toShortString()}. Refusing to dispatch off-screen gesture."
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

        dispatchOpenReelViewerTap(bounds)
    }

    /**
     * Dispatch the short tap gesture on [bounds] to open the Reel
     * viewer and schedule [tapShareInReelViewer] after the viewer
     * settle window. Extracted from [openFirstReelViewer] so the
     * on-demand URL enrichment path (session 37) can call it directly
     * after locating a specific target via [locateReelWithScroll].
     *
     * Callers must set [pendingCopy] BEFORE calling this, since the
     * clipboard capture broadcast that eventually persists the URL
     * consults `pendingCopy` for the thread/author/direction key.
     */
    private fun dispatchOpenReelViewerTap(bounds: Rect) {
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
        var success = false
        if (url.isNullOrBlank()) {
            Log.w(TAG, "COPY_LINK: ClipboardCaptureActivity returned empty text.")
        } else {
            Log.i(TAG, "COPY_LINK: Reel URL = '$url'")
            if (pending != null) {
                persistCopiedReel(pending, url)
                success = true
            } else {
                Log.w(TAG, "COPY_LINK: no pendingCopy context — URL not persisted to DB.")
            }
        }
        // Close the share sheet + the Reel viewer so the user is back on
        // the conversation. Two BACKs: first closes whatever is on top,
        // second closes the viewer.
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, BACK_AFTER_COPY_DELAY_MS)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, BACK_AFTER_COPY_DELAY_MS * 2)

        // s39 — completion feedback for single copy URL / single Reel
        // enrichment. We only post when the row is safely persisted AND
        // we're not part of a batch (the batch handles its own aggregate
        // completion notification once all Reels are done).
        if (success && !batchEnrichmentInProgress && pending != null) {
            val body = if (!pending.reelAuthor.isNullOrBlank())
                getString(R.string.notif_completion_copy_url_body, pending.reelAuthor, pending.threadTitle)
            else
                getString(R.string.notif_completion_copy_url_body_no_author, pending.threadTitle)
            postCompletionNotification(
                title = getString(R.string.notif_completion_copy_url_title),
                body = body,
            )
        }
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
     * Persist the enriched Reel context + URL into Room. Tries three paths
     * in order:
     * 1. **Promote a discovery-only row** — if a previous
     *    `ACTION_DISCOVER_REELS` created a row for the same
     *    (thread, author, direction) with `reelUrl=null`, upgrade it in
     *    place with the URL + dmSender. Closes the interaction gap
     *    reported in session 25 where 🔍 followed by 🔗 was creating a
     *    duplicate row.
     * 2. **Insert new row** — otherwise, insert a fresh enriched row.
     *    The `reelUrl` unique index guarantees no duplicates when the
     *    same URL is copied twice.
     * 3. **Backfill dmSender by URL** — if the insert was rejected by the
     *    unique index (URL already exists), only update the dmSender when
     *    it was previously null.
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

            // 1. Try to promote a discovery-only row (URL null) that
            //    matches the same (thread, author, direction).
            val promoted = dao.promoteDiscoveryRow(
                url = url,
                dmSender = pending.dmSender,
                thread = pending.threadTitle,
                author = pending.reelAuthor,
                direction = pending.direction.name,
            )
            if (promoted > 0) {
                val total = dao.count()
                Log.i(
                    TAG,
                    "COPY_LINK: promoted $promoted discovery-only row(s) to enriched " +
                        "(thread='${pending.threadTitle}' author=${pending.reelAuthor} " +
                        "direction=${pending.direction} dmSender=${pending.dmSender}) totalInDb=$total."
                )
                return@launch
            }

            // 2. Try a fresh insert.
            val id = dao.insert(row)
            if (id > 0) {
                val total = dao.count()
                Log.i(
                    TAG,
                    "COPY_LINK: inserted row id=$id thread='${pending.threadTitle}' " +
                        "author=${pending.reelAuthor} dmSender=${pending.dmSender} totalInDb=$total."
                )
                return@launch
            }

            // 3. URL already existed — only backfill dmSender if needed.
            val updated = dao.updateDmSenderByUrl(url, pending.dmSender)
            Log.i(
                TAG,
                "COPY_LINK: URL already in DB — backfilled dmSender rows=$updated " +
                    "(dmSender=${pending.dmSender})."
            )
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
            mainHandler.post {
                val body = if (inserted > 0)
                    getString(R.string.notif_completion_discover_body, inserted, threadTitle)
                else
                    getString(R.string.notif_completion_discover_body_empty, threadTitle)
                postCompletionNotification(
                    title = getString(R.string.notif_completion_discover_title),
                    body = body,
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // PoC-8 iteration 3 part B — history discovery (auto scroll)
    //
    // Same enumeration as `discoverReels()` but repeats after auto-scrolling
    // the conversation upward. Stops when three consecutive scrolls fail
    // to insert new rows (heuristic for "reached the top of the DM") or
    // after HISTORY_MAX_SCROLLS scrolls (safety cap).
    //
    // Direction of scroll: in Instagram DMs the newest message is at the
    // bottom. Older content sits above; to bring it into view we need the
    // list to scroll BACKWARD (accessibility parlance) — same as pulling
    // the visible messages downwards. We try `ACTION_SCROLL_BACKWARD` on
    // the RecyclerView first (cleanest, respects fling+deceleration), and
    // fall back to a gesture from y_near_top → y_near_bottom (finger drag
    // DOWN) when that action is refused.
    //
    // Deduplication is the same (thread, author, direction) heuristic used
    // by the fast discover — imperfect but matches the current schema.
    // A single author sharing several Reels in the same thread will
    // collapse to one row, which conveniently also drives the "no new
    // inserts" stop condition.
    // ---------------------------------------------------------------------

    private var historyInProgress = false

    /**
     * Mutable state carried across the ping-pong between enumerate and
     * scroll steps. Kept as a single object so we don't have to thread
     * counters through every callback.
     */
    private data class HistoryState(
        val threadTitle: String,
        val ignoreSent: Boolean,
        var totalScrolls: Int = 0,
        var totalInserted: Int = 0,
        var totalSkipped: Int = 0,
        var consecutiveEmpty: Int = 0,
    )

    private fun discoverReelsHistory() {
        if (historyInProgress) {
            Log.i(TAG, "HISTORY: already in progress, ignoring.")
            return
        }
        val root = findIgApplicationWindow()?.root ?: rootInActiveWindow
        if (root == null || root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "HISTORY: Instagram is not foreground, aborting.")
            return
        }
        val threadTitle = lastKnownConversationTitle?.takeIf { it.isNotBlank() } ?: "?"
        val state = HistoryState(threadTitle = threadTitle, ignoreSent = isIgnoreSentEnabled())
        historyInProgress = true
        Log.i(TAG, "HISTORY: starting thread='$threadTitle' ignoreSent=${state.ignoreSent}")
        updateHistoryProgressNotification(state)
        // Enumerate the initial visible batch, then step into the scroll loop.
        doHistoryEnumerate(state) { doHistoryScroll(state) }
    }

    /**
     * Enumerate whatever Reels are currently visible in `message_list`
     * and insert new ones into Room. Updates the state counters on the
     * main thread, then invokes [onDone] so the caller can decide
     * whether to scroll again or stop.
     */
    private fun doHistoryEnumerate(state: HistoryState, onDone: () -> Unit) {
        val root = findIgApplicationWindow()?.root ?: rootInActiveWindow
        if (root == null || root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "HISTORY: IG no longer foreground during enumerate, stopping.")
            finishHistory(state)
            return
        }
        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "HISTORY: no message_list, stopping.")
            finishHistory(state)
            return
        }
        val entries = enumerateReels(messageList)
        val kept = if (state.ignoreSent) entries.filter { it.direction == Direction.RECEIVED } else entries
        val snapshot = kept.map { Snapshot(it.index, it.kind, it.direction, it.reelAuthor) }
        val discoveredAt = System.currentTimeMillis()
        serviceScope.launch {
            val dao = AppDatabase.get(this@InstagramReaderService).reelDao()
            var inserted = 0
            var skipped = 0
            for (s in snapshot) {
                val existing = dao.countMatching(state.threadTitle, s.author, s.direction.name)
                if (existing > 0) { skipped++; continue }
                val row = ReelEntity(
                    threadTitle = state.threadTitle,
                    reelAuthor = s.author,
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
            state.totalInserted += inserted
            state.totalSkipped += skipped
            if (inserted == 0) state.consecutiveEmpty++ else state.consecutiveEmpty = 0
            Log.i(
                TAG,
                "HISTORY: scroll=${state.totalScrolls} inserted=$inserted skipped=$skipped " +
                    "totalInsertedRun=${state.totalInserted} consecutiveEmpty=${state.consecutiveEmpty} totalInDb=$total"
            )
            mainHandler.post {
                updateHistoryProgressNotification(state)
                onDone()
            }
        }
    }

    /**
     * Decide whether to scroll again, and if so, perform one backward
     * scroll on the `message_list` and chain back into [doHistoryEnumerate].
     */
    private fun doHistoryScroll(state: HistoryState) {
        if (state.consecutiveEmpty >= HISTORY_STOP_AFTER_N_EMPTY) {
            Log.i(TAG, "HISTORY: stopping — ${state.consecutiveEmpty} consecutive empty scrolls.")
            finishHistory(state)
            return
        }
        if (state.totalScrolls >= HISTORY_MAX_SCROLLS) {
            Log.i(TAG, "HISTORY: stopping — safety cap $HISTORY_MAX_SCROLLS scrolls hit.")
            finishHistory(state)
            return
        }
        val root = findIgApplicationWindow()?.root ?: rootInActiveWindow
        if (root == null || root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "HISTORY: IG no longer foreground during scroll, stopping.")
            finishHistory(state)
            return
        }
        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "HISTORY: no message_list during scroll, stopping.")
            finishHistory(state)
            return
        }

        // Preferred path: framework scroll action.
        val a11yOk = messageList.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (a11yOk) {
            state.totalScrolls++
            Log.i(
                TAG,
                "HISTORY: scroll ${state.totalScrolls}/$HISTORY_MAX_SCROLLS via ACTION_SCROLL_BACKWARD accepted"
            )
            mainHandler.postDelayed({
                doHistoryEnumerate(state) { doHistoryScroll(state) }
            }, HISTORY_SCROLL_SETTLE_MS)
            return
        }

        // Fallback: dispatch a swipe DOWN inside the message_list. In IG's
        // chat RecyclerView, dragging the visible content DOWN reveals
        // older messages that were off-screen above.
        val bounds = Rect().also { messageList.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w(TAG, "HISTORY: message_list has empty bounds, stopping.")
            finishHistory(state)
            return
        }
        val startX = bounds.exactCenterX()
        val startY = bounds.top + bounds.height() * 0.25f
        val endY = bounds.top + bounds.height() * 0.85f
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, HISTORY_SCROLL_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                state.totalScrolls++
                Log.i(
                    TAG,
                    "HISTORY: scroll ${state.totalScrolls}/$HISTORY_MAX_SCROLLS via gesture completed"
                )
                mainHandler.postDelayed({
                    doHistoryEnumerate(state) { doHistoryScroll(state) }
                }, HISTORY_SCROLL_SETTLE_MS)
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "HISTORY: scroll gesture cancelled, stopping.")
                finishHistory(state)
            }
        }, mainHandler)
        if (!accepted) {
            Log.w(TAG, "HISTORY: dispatchGesture refused, stopping.")
            finishHistory(state)
        }
    }

    private fun finishHistory(state: HistoryState) {
        Log.i(
            TAG,
            "HISTORY: finished — thread='${state.threadTitle}' scrolls=${state.totalScrolls} " +
                "totalInsertedRun=${state.totalInserted} totalSkippedRun=${state.totalSkipped}"
        )
        historyInProgress = false
        postControlNotification()
        postCompletionNotification(
            title = getString(R.string.notif_completion_history_title),
            body = getString(
                R.string.notif_completion_history_body,
                state.totalInserted,
                state.threadTitle,
                state.totalScrolls,
            ),
        )
        returnToAppIfEnabled()
    }

    /**
     * Overwrite the persistent notification with a "A descobrir histórico…"
     * status while [discoverReelsHistory] is running. Reverts to the normal
     * button row via [postControlNotification] when the run finishes.
     */
    private fun updateHistoryProgressNotification(state: HistoryState) {
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(
                    R.string.notif_history_progress,
                    state.totalScrolls,
                    HISTORY_MAX_SCROLLS,
                    state.totalInserted,
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setContentIntent(pendingActivity(
                com.example.friendsreels.ui.feed.FeedActivity::class.java,
                requestCode = 0,
            ))
            .setProgress(HISTORY_MAX_SCROLLS, state.totalScrolls, false)
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "updateHistoryProgressNotification: missing POST_NOTIFICATIONS", e)
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
    // PoC-8 iteration 3 — batching executor
    //
    // The feed enqueues reactions/replies into `pending_actions`. Here we
    // drain that queue in FIFO order, driving the same primitives that
    // power the notification buttons (long-press → quick-reaction / reply).
    //
    // Scope of this iteration (PoC-9, session 33):
    //   - Cross-conversation navigation is now handled internally by
    //     [navigateToThreadAsync]. Before each step the executor checks
    //     the live `header_title`; if it does not match `step.reel.
    //     threadTitle`, IG is driven back to the inbox and the matching
    //     row is clicked (matched by `contentDescription` prefix, which
    //     the Compose inbox exposes even without resource-ids — see
    //     [IgSelectors.Inbox]).
    //   - Steps are grouped by threadTitle before execution so a single
    //     batch visits each conversation at most once (preserving the
    //     original createdAt ordering both between and within groups).
    //   - Actions of the same kind on different Reels of the same thread
    //     still hit "the first RECEIVED Reel visible" — this is a known
    //     simplification shared with PoC-5/6 and will be replaced by
    //     targeting per specific bubble in PoC-8 iter 4.
    // ---------------------------------------------------------------------

    // -------------------------------------------------------------------
    // PoC-9 — thread navigation helpers
    // -------------------------------------------------------------------

    /**
     * Live-read of the current thread's header title. Unlike
     * [lastKnownConversationTitle] (which is only refreshed by
     * `onAccessibilityEvent` when a NEW title is seen, and therefore
     * stays STALE after a BACK-to-inbox), this walks the current tree
     * every call. Returns null when we're not inside a thread (e.g.
     * we're on the inbox, on Home, or IG is not foreground).
     */
    private fun currentHeaderTitle(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) return null
        return root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.HEADER_TITLE))
            ?.firstOrNull()
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * True when the Direct inbox is currently focused. Strict signal:
     * the `direct_tab` bottom nav button must exist AND be marked
     * selected. Previously (session 33) we used the presence of a
     * "Mensagens" text node anywhere in the tree, but that false-
     * positives on IG Home (which shows a "Notas" / stories rail with
     * usernames — `clickInboxRow` then matched some random name prefix
     * and clicked it, going nowhere, and the batch spent 10 attempts
     * spinning on that click). Session-35 device log confirmed the
     * failure mode (`docs/screen-dumps/feed.txt`).
     *
     * The tab is marked `isSelected=true` while the inbox is the active
     * tab regardless of localization, so we don't need the label
     * matching any more. If the bottom nav is hidden (e.g. we're inside
     * a thread), `directTab` is null and we fall back to false — which
     * correctly triggers the "click direct_tab" branch of the state
     * machine.
     */
    private fun isInboxVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != IgSelectors.IG_PACKAGE) return false
        val targetId = IgSelectors.id(IgSelectors.BottomNav.DIRECT_TAB)
        val directTab = findFirstNodeAcrossWindows { it.viewIdResourceName == targetId }
            ?: return false
        return directTab.isSelected
    }

    /** Click the Direct tab in the bottom navigation. Returns true on dispatch. */
    private fun clickDirectTab(): Boolean {
        val targetId = IgSelectors.id(IgSelectors.BottomNav.DIRECT_TAB)
        val node = findFirstNodeAcrossWindows { n ->
            n.viewIdResourceName == targetId
        } ?: return false
        // Bottom tabs may not be clickable directly — walk up to the nearest clickable ancestor.
        var candidate: AccessibilityNodeInfo? = node
        var hops = 0
        while (candidate != null && !candidate.isClickable && hops < 4) {
            candidate = candidate.parent
            hops++
        }
        val toClick = candidate ?: node
        return toClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Locate the inbox row whose `contentDescription` starts with
     * `"<threadTitle>, "` (the format observed in PoC-2, e.g. `"Pedro
     * Sardoeira, ...preview... ·, 3 h"`), then click its nearest
     * clickable ancestor. Returns true on dispatch. Case-sensitive
     * matching — thread titles are captured verbatim into the DB from the
     * header, so an exact match is expected.
     */
    private fun clickInboxRow(threadTitle: String): Boolean {
        val prefix = "$threadTitle, "
        val row = findFirstNodeAcrossWindows { n ->
            val d = n.contentDescription?.toString()
            d != null && d.startsWith(prefix)
        } ?: run {
            Log.w(TAG, "NAV: no inbox row starts with '$prefix'.")
            return false
        }
        var candidate: AccessibilityNodeInfo? = row
        var hops = 0
        while (candidate != null && !candidate.isClickable && hops < 6) {
            candidate = candidate.parent
            hops++
        }
        val toClick = candidate ?: row
        val ok = toClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "NAV: click inbox row '$threadTitle' returned $ok")
        return ok
    }

    /**
     * Iteratively drive IG to the thread whose `header_title` equals
     * [target]. Each attempt inspects the current state and dispatches
     * ONE action (BACK, direct_tab click, or inbox row click), then
     * re-polls after [NAV_STEP_SETTLE_MS]. Stops when the header matches
     * (success) or [attemptsLeft] reaches 0 (failure).
     *
     * State machine per attempt:
     *   - Inside a thread with a mismatched header → BACK to reach inbox.
     *   - Not in a thread and inbox not visible → click direct_tab.
     *   - Inbox visible → search for the row and click it.
     */
    private fun navigateToThreadAsync(
        target: String,
        attemptsLeft: Int,
        onDone: (Boolean) -> Unit,
    ) {
        if (attemptsLeft <= 0) {
            Log.w(TAG, "NAV: gave up navigating to '$target' after ${NAV_MAX_ATTEMPTS} attempts.")
            onDone(false)
            return
        }
        val header = currentHeaderTitle()
        if (header == target) {
            Log.i(TAG, "NAV: arrived at '$target' (attemptsLeft=$attemptsLeft).")
            onDone(true)
            return
        }

        val stage: String
        val dispatched: Boolean
        when {
            !header.isNullOrEmpty() -> {
                stage = "back-out (current='$header')"
                dispatched = performGlobalAction(GLOBAL_ACTION_BACK)
            }
            isInboxVisible() -> {
                stage = "inbox-click"
                dispatched = clickInboxRow(target)
                if (!dispatched) {
                    Log.w(TAG, "NAV: inbox row '$target' unclickable, giving up.")
                    onDone(false)
                    return
                }
            }
            else -> {
                stage = "open-direct-tab"
                dispatched = clickDirectTab()
                if (!dispatched) {
                    Log.w(TAG, "NAV: direct_tab not found, giving up.")
                    onDone(false)
                    return
                }
            }
        }
        Log.i(TAG, "NAV: attempt ${NAV_MAX_ATTEMPTS - attemptsLeft + 1} stage=$stage dispatched=$dispatched target='$target'")
        mainHandler.postDelayed({
            navigateToThreadAsync(target, attemptsLeft - 1, onDone)
        }, NAV_STEP_SETTLE_MS)
    }

    private var batchInProgress = false
    /** Counters used to render the s39 completion notification after apply pending. */
    private var applyPendingSucceeded = 0
    private var applyPendingFailed = 0

    // -------------------------------------------------------------------
    // PoC-8 iter 4 (session 34) — locate a specific Reel via history scroll
    // -------------------------------------------------------------------

    /**
     * Try to find the exact bubble matching [target] inside the currently
     * open conversation. If it isn't in the visible portion of
     * `message_list`, scroll BACKWARD (towards older messages) and retry,
     * up to [scrollsLeft] scrolls. Invokes [onDone] with a matching
     * [DmReelEntry] on success or null when the search is exhausted /
     * IG isn't in a valid state.
     *
     * Matching strategy:
     *   1. Enumerate the received-Reel bubbles currently in the tree.
     *   2. If [target.reelAuthor] is non-null, pick the top-most bubble
     *      with the same `reelAuthor`. This is our best identity signal
     *      short of persisting `reelUrl` for every bubble (which requires
     *      opening the viewer — see PoC-7). Multiple Reels shared from
     *      the same IG creator will collide; the top-most (older) match
     *      wins. Trade-off documented in §5 of PROJECT_PROGRESS.
     *   3. If [target.reelAuthor] is null (row inserted before PoC-4
     *      captured the author), fall back to the top-most visible
     *      received Reel — same behaviour as [findFirstReelBubble].
     */
    private fun locateReelWithScroll(
        target: ReelEntity,
        scrollsLeft: Int,
        onDone: (DmReelEntry?) -> Unit,
    ) {
        val igWindow = findIgApplicationWindow()
        val root = igWindow?.root ?: rootInActiveWindow
        if (root == null || root.packageName?.toString() != IgSelectors.IG_PACKAGE) {
            Log.w(TAG, "LOCATE: no IG root available (pkg=${root?.packageName}), giving up.")
            onDone(null)
            return
        }
        val messageList = root
            .findAccessibilityNodeInfosByViewId(IgSelectors.id(IgSelectors.Thread.MESSAGE_LIST))
            .firstOrNull()
        if (messageList == null) {
            Log.w(TAG, "LOCATE: no message_list; giving up.")
            onDone(null)
            return
        }

        val candidates = enumerateReels(messageList)
            .filter { it.bounds.width() > 0 && it.bounds.height() >= MIN_REEL_BUBBLE_HEIGHT_PX }
            .filter { it.direction == Direction.RECEIVED }
        val wantedAuthor = target.reelAuthor
        val match = if (wantedAuthor != null) {
            candidates.firstOrNull { it.reelAuthor == wantedAuthor }
        } else {
            candidates.firstOrNull()
        }
        if (match != null) {
            Log.i(
                TAG,
                "LOCATE: matched reelId=${target.id} author=$wantedAuthor at " +
                    "index=${match.index} bounds=${match.bounds.toShortString()} " +
                    "(scrollsLeft=$scrollsLeft, visibleReceived=${candidates.size})"
            )
            onDone(match)
            return
        }

        if (scrollsLeft <= 0) {
            val visibleAuthors = candidates.map { it.reelAuthor ?: "?" }
            Log.w(
                TAG,
                "LOCATE: could not find reelId=${target.id} author=$wantedAuthor after " +
                    "$BATCH_MAX_SCROLLS scrolls. visibleReceived=${candidates.size} authors=$visibleAuthors"
            )
            onDone(null)
            return
        }

        val scrolled = messageList.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (scrolled) {
            Log.i(
                TAG,
                "LOCATE: target not visible, ACTION_SCROLL_BACKWARD accepted " +
                    "scrollsLeft=$scrollsLeft author=$wantedAuthor"
            )
            mainHandler.postDelayed({
                locateReelWithScroll(target, scrollsLeft - 1, onDone)
            }, LOCATE_SCROLL_SETTLE_MS)
            return
        }

        // Fallback: dispatch a swipe DOWN inside the message_list to
        // reveal older messages. Same technique used by discoverReelsHistory
        // when the a11y scroll action is refused.
        val bounds = Rect().also { messageList.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            Log.w(TAG, "LOCATE: message_list has empty bounds; giving up.")
            onDone(null)
            return
        }
        val startX = bounds.exactCenterX()
        val startY = bounds.top + bounds.height() * 0.25f
        val endY = bounds.top + bounds.height() * 0.85f
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, LOCATE_SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.i(
                    TAG,
                    "LOCATE: swipe fallback completed scrollsLeft=$scrollsLeft author=$wantedAuthor"
                )
                mainHandler.postDelayed({
                    locateReelWithScroll(target, scrollsLeft - 1, onDone)
                }, LOCATE_SCROLL_SETTLE_MS)
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.w(TAG, "LOCATE: swipe fallback cancelled by system; giving up.")
                onDone(null)
            }
        }, mainHandler)
        if (!dispatched) {
            Log.w(TAG, "LOCATE: swipe fallback rejected by dispatchGesture; giving up.")
            onDone(null)
        }
    }

    // -------------------------------------------------------------------
    // On-demand URL enrichment (session 37) — ACTION_ENRICH_REEL_URL
    // -------------------------------------------------------------------

    /**
     * Enrich a specific [reelId] with its canonical share URL. Combines
     * the PoC-9 navigation with the PoC-8 iter 4 scroll-to-locate and the
     * PoC-7 copy-link primitive: nav to thread → locate bubble by author
     * → tap to open viewer → let the existing chain (share sheet → copy
     * link → clipboard bridge → [handleClipboardCaptured]) persist the
     * URL back into the row via [ReelDao.promoteDiscoveryRow].
     *
     * Fires from the feed's per-page "🔗 Preparar Reel" button when the
     * user lands on a Reel that hasn't been through the copy-link pass
     * yet. Failure paths (Reel row deleted, IG missing, thread renamed,
     * bubble not in history, share sheet doesn't have "Copy link") all
     * log-and-give-up silently — the placeholder stays and the user can
     * try again or manually open the IG conversation.
     */
    private fun enrichReelUrl(reelId: Long) {
        serviceScope.launch {
            val reel = AppDatabase.get(this@InstagramReaderService).reelDao().byId(reelId)
            if (reel == null) {
                Log.w(TAG, "ENRICH_URL: reelId=$reelId not in DB, ignoring.")
                return@launch
            }
            if (!reel.reelUrl.isNullOrBlank()) {
                Log.i(TAG, "ENRICH_URL: reelId=$reelId already has a URL, ignoring.")
                return@launch
            }
            mainHandler.post { startEnrichmentForReel(reel) }
        }
    }

    /**
     * Main-thread entry after we've resolved the Reel row. Navigates to
     * the correct thread and then locates the bubble.
     */
    private fun startEnrichmentForReel(reel: ReelEntity) {
        Log.i(
            TAG,
            "ENRICH_URL: starting for reelId=${reel.id} author=${reel.reelAuthor} " +
                "thread='${reel.threadTitle}' current='${currentHeaderTitle()}'"
        )
        val currentHeader = currentHeaderTitle()
        if (currentHeader == reel.threadTitle) {
            locateAndOpenReelViewer(reel)
        } else {
            navigateToThreadAsync(reel.threadTitle, attemptsLeft = NAV_MAX_ATTEMPTS) { navOk ->
                if (!navOk) {
                    Log.w(TAG, "ENRICH_URL: nav to '${reel.threadTitle}' failed, giving up reelId=${reel.id}")
                    return@navigateToThreadAsync
                }
                mainHandler.postDelayed({ locateAndOpenReelViewer(reel) }, NAV_POST_ARRIVAL_SETTLE_MS)
            }
        }
    }

    private fun locateAndOpenReelViewer(reel: ReelEntity) {
        locateReelWithScroll(reel, scrollsLeft = BATCH_MAX_SCROLLS) { entry ->
            if (entry == null) {
                Log.w(TAG, "ENRICH_URL: could not locate Reel author=${reel.reelAuthor} in '${reel.threadTitle}', giving up reelId=${reel.id}")
                return@locateReelWithScroll
            }
            // Guard against off-screen taps.
            val windowBounds = findIgApplicationWindow()?.let { w ->
                Rect().also { w.getBoundsInScreen(it) }
            }
            if (windowBounds != null && !windowBounds.contains(entry.bounds.centerX(), entry.bounds.centerY())) {
                Log.w(TAG, "ENRICH_URL: bubble center outside IG window bounds — refusing to tap off-screen.")
                return@locateReelWithScroll
            }
            // Set pendingCopy BEFORE tapping so the clipboard bridge can
            // resolve the correct DB row via promoteDiscoveryRow (matched
            // by threadTitle + reelAuthor + direction).
            pendingCopy = PendingCopy(
                threadTitle = reel.threadTitle,
                direction = entry.direction,
                reelAuthor = entry.reelAuthor ?: reel.reelAuthor,
                kind = entry.kind,
                bubbleIndex = entry.index,
            )
            Log.i(TAG, "ENRICH_URL: located reelId=${reel.id}, tapping to open viewer. pendingCopy=$pendingCopy")
            dispatchOpenReelViewerTap(Rect(entry.bounds))
        }
    }

    // -------------------------------------------------------------------
    // Batch URL enrichment (session 38) — ACTION_ENRICH_ALL_MISSING_URLS
    // -------------------------------------------------------------------

    /**
     * True while a batch enrichment run is in flight. Prevents double
     * broadcasts from spawning overlapping executors, since two runs
     * would fight over `pendingCopy` and produce duplicate rows.
     */
    private var batchEnrichmentInProgress = false

    /**
     * Set by `ACTION_ENRICH_ALL_CANCEL`. Checked between each Reel; the
     * current step is allowed to finish (so IG doesn't get left mid-
     * share-sheet) but no further steps are dispatched.
     */
    private var batchEnrichmentCancelled = false

    /**
     * Kick off a batch that enriches every `reels` row where
     * `reelUrl IS NULL`. Broadcasts progress via [BatchEnrichmentBus].
     * The whole thing is a no-op (with a fresh `LastResult(0,0,false)`
     * so the UI can update) if there are no rows to enrich.
     */
    private fun enrichAllMissingUrls() {
        if (batchEnrichmentInProgress) {
            Log.i(TAG, "ENRICH_ALL: batch already in progress, ignoring.")
            return
        }
        batchEnrichmentInProgress = true
        batchEnrichmentCancelled = false
        BatchEnrichmentBus.update(
            BatchEnrichmentBus.State(running = true, currentIndex = 0, total = 0, lastResult = null)
        )
        serviceScope.launch {
            val dao = AppDatabase.get(this@InstagramReaderService).reelDao()
            val missing = dao.allMissingUrls()
            if (missing.isEmpty()) {
                Log.i(TAG, "ENRICH_ALL: nothing to enrich, exiting.")
                val emptyResult = BatchEnrichmentBus.LastResult(0, 0, cancelled = false)
                BatchEnrichmentBus.update(
                    BatchEnrichmentBus.State(
                        running = false,
                        currentIndex = 0,
                        total = 0,
                        lastResult = emptyResult,
                    )
                )
                mainHandler.post {
                    batchEnrichmentInProgress = false
                    persistBatchEnrichResult(emptyResult)
                    postCompletionNotification(
                        title = getString(R.string.notif_completion_batch_enrich_title),
                        body = getString(R.string.notif_completion_batch_enrich_body, 0, 0),
                    )
                }
                return@launch
            }
            // Group by thread so we visit each conversation only once,
            // then flatten preserving intra-group `discoveredAt ASC`.
            val ordered = missing
                .groupBy { it.threadTitle }
                .toList()
                .sortedBy { (_, list) -> list.minOfOrNull { it.discoveredAt } ?: 0L }
                .flatMap { (_, list) -> list.sortedBy { it.discoveredAt } }
            val threadCount = ordered.map { it.threadTitle }.distinct().size
            Log.i(
                TAG,
                "ENRICH_ALL: starting batch for ${ordered.size} reel(s) across $threadCount thread(s)."
            )
            BatchEnrichmentBus.update(
                BatchEnrichmentBus.State(
                    running = true,
                    currentIndex = 0,
                    total = ordered.size,
                    lastResult = null,
                )
            )
            mainHandler.post {
                updateBatchEnrichProgressNotification(0, ordered.size)
                runInInstagram {
                    processBatchEnrichmentStep(
                        reels = ordered,
                        index = 0,
                        succeeded = 0,
                        failed = 0,
                    )
                }
            }
        }
    }

    /**
     * Process the [index]-th Reel of [reels], then chain to the next.
     * Runs on the main thread; only the polling loop hops to the IO
     * scope. On completion (or cancellation) updates
     * [BatchEnrichmentBus] with the final [BatchEnrichmentBus.LastResult]
     * and clears [batchEnrichmentInProgress].
     */
    private fun processBatchEnrichmentStep(
        reels: List<ReelEntity>,
        index: Int,
        succeeded: Int,
        failed: Int,
    ) {
        if (batchEnrichmentCancelled || index >= reels.size) {
            val done = index >= reels.size
            Log.i(
                TAG,
                "ENRICH_ALL: batch " +
                    (if (done) "complete" else "cancelled at ${index + 1}/${reels.size}") +
                    " (succeeded=$succeeded failed=$failed).",
            )
            val result = BatchEnrichmentBus.LastResult(
                succeeded = succeeded,
                failed = failed,
                cancelled = !done,
            )
            BatchEnrichmentBus.update(
                BatchEnrichmentBus.State(
                    running = false,
                    currentIndex = index,
                    total = reels.size,
                    lastResult = result,
                )
            )
            batchEnrichmentInProgress = false
            persistBatchEnrichResult(result)
            // Restore the persistent control notification and post the
            // transient completion notification on the higher-importance
            // status channel so the user knows the batch is done even
            // if they're still in Instagram.
            postControlNotification()
            val bodyRes = if (!done)
                R.string.notif_completion_batch_enrich_body_cancelled
            else
                R.string.notif_completion_batch_enrich_body
            postCompletionNotification(
                title = getString(R.string.notif_completion_batch_enrich_title),
                body = getString(bodyRes, succeeded, failed),
            )
            returnToAppIfEnabled()
            return
        }

        val target = reels[index]
        // 1-based currentIndex for the UI ("A preparar 3 de 12").
        BatchEnrichmentBus.update(
            BatchEnrichmentBus.State(
                running = true,
                currentIndex = index + 1,
                total = reels.size,
                lastResult = null,
            )
        )
        updateBatchEnrichProgressNotification(index + 1, reels.size)
        Log.i(
            TAG,
            "ENRICH_ALL: step ${index + 1}/${reels.size} reelId=${target.id} " +
                "author=${target.reelAuthor} thread='${target.threadTitle}'"
        )

        serviceScope.launch {
            val dao = AppDatabase.get(this@InstagramReaderService).reelDao()
            val fresh = dao.byId(target.id)
            if (fresh == null) {
                Log.w(TAG, "ENRICH_ALL: reelId=${target.id} vanished mid-batch, skipping.")
                mainHandler.postDelayed({
                    processBatchEnrichmentStep(reels, index + 1, succeeded, failed + 1)
                }, BATCH_ENRICH_SPACING_MS)
                return@launch
            }
            if (!fresh.reelUrl.isNullOrBlank()) {
                Log.i(TAG, "ENRICH_ALL: reelId=${target.id} already enriched by a parallel path, skipping.")
                mainHandler.postDelayed({
                    processBatchEnrichmentStep(reels, index + 1, succeeded + 1, failed)
                }, BATCH_ENRICH_SPACING_MS)
                return@launch
            }
            mainHandler.post { startEnrichmentForReel(fresh) }
            // Poll the DB until the URL lands, we time out, or the
            // user cancels.
            val deadlineMs = System.currentTimeMillis() + BATCH_ENRICH_STEP_TIMEOUT_MS
            var stepOk = false
            while (System.currentTimeMillis() < deadlineMs) {
                kotlinx.coroutines.delay(BATCH_ENRICH_POLL_INTERVAL_MS)
                if (batchEnrichmentCancelled) break
                val r = dao.byId(target.id) ?: break
                if (!r.reelUrl.isNullOrBlank()) {
                    stepOk = true
                    break
                }
            }
            val nextSucceeded = succeeded + if (stepOk) 1 else 0
            val nextFailed = failed + if (stepOk) 0 else 1
            Log.i(
                TAG,
                "ENRICH_ALL: step ${index + 1} result stepOk=$stepOk " +
                    "(succeeded=$nextSucceeded failed=$nextFailed cancelled=$batchEnrichmentCancelled)"
            )
            // pendingCopy might still be set if the previous step
            // timed out mid-flight — clear it so the next step starts
            // clean.
            mainHandler.post {
                if (!stepOk) pendingCopy = null
                mainHandler.postDelayed({
                    processBatchEnrichmentStep(reels, index + 1, nextSucceeded, nextFailed)
                }, BATCH_ENRICH_SPACING_MS)
            }
        }
    }

    /**
     * Ask the running batch enrichment to stop after the current
     * Reel. If no batch is running this is a no-op.
     */
    private fun cancelBatchEnrichment() {
        if (!batchEnrichmentInProgress) {
            Log.i(TAG, "ENRICH_ALL: cancel requested but no batch running.")
            return
        }
        Log.i(TAG, "ENRICH_ALL: cancel requested — batch will stop after the current Reel.")
        batchEnrichmentCancelled = true
    }

    /**
     * Entry point for `ACTION_APPLY_PENDING`. Reads the pending queue on
     * the IO scope, then hops back to the main handler to execute each
     * row via [runBatchStep]. Guarded by [batchInProgress] so double taps
     * don't spawn overlapping executors.
     */
    private fun applyPendingActions() {
        if (batchInProgress) {
            Log.i(TAG, "APPLY_PENDING: batch already in progress, ignoring.")
            return
        }
        val currentThread = currentHeaderTitle()
        Log.i(TAG, "APPLY_PENDING: starting drain (currentThread='$currentThread').")
        batchInProgress = true
        applyPendingSucceeded = 0
        applyPendingFailed = 0

        serviceScope.launch {
            val db = AppDatabase.get(this@InstagramReaderService)
            val actions = db.pendingActionDao().pending()
            if (actions.isEmpty()) {
                Log.i(TAG, "APPLY_PENDING: queue empty, nothing to do.")
                mainHandler.post {
                    batchInProgress = false
                    postCompletionNotification(
                        title = getString(R.string.notif_completion_apply_pending_title),
                        body = getString(R.string.notif_completion_apply_pending_body, 0, 0),
                    )
                }
                return@launch
            }
            // Pre-resolve every referenced Reel so we can filter on the
            // main thread without further DB round-trips.
            val reelDao = db.reelDao()
            val rawSteps = actions.mapNotNull { action ->
                val reel = reelDao.byId(action.reelId)
                if (reel == null) {
                    // Reel row was deleted between enqueue and drain —
                    // finish the action as FAILED and drop it from the batch.
                    db.pendingActionDao().finish(
                        action.id,
                        PendingActionEntity.STATUS_FAILED,
                        System.currentTimeMillis(),
                        "Reel foi apagado da base de dados",
                    )
                    Log.w(TAG, "APPLY_PENDING: action id=${action.id} references missing reel id=${action.reelId}, dropped.")
                    null
                } else {
                    BatchStep(action = action, reel = reel)
                }
            }

            // Group by threadTitle so a single batch visits each conversation
            // at most once. Groups are ordered by their earliest `createdAt`
            // (preserves user intent — a batch that started with "Pedro"
            // still hits Pedro's thread first) and within a group the
            // original `createdAt ASC` order is kept.
            val steps = rawSteps
                .groupBy { it.reel.threadTitle }
                .toList()
                .sortedBy { (_, list) -> list.minOfOrNull { it.action.createdAt } ?: 0L }
                .flatMap { (_, list) -> list.sortedBy { it.action.createdAt } }
            val threadCount = steps.map { it.reel.threadTitle }.distinct().size
            Log.i(TAG, "APPLY_PENDING: resolved ${steps.size} step(s) across $threadCount thread(s) to run.")

            mainHandler.postDelayed({
                runBatchStep(steps, index = 0)
            }, BATCH_START_DELAY_MS)
        }
    }

    private data class BatchStep(val action: PendingActionEntity, val reel: ReelEntity)

    /**
     * Entry point for step [index]. Ensures IG is on the correct
     * conversation via [navigateToThreadAsync] before running the
     * primitive; skips the row (FAILED) if navigation gives up. Once the
     * step is dispatched, schedules the next after a per-kind delay (see
     * [BATCH_STEP_INTERVAL_REACTION_MS] / [BATCH_STEP_INTERVAL_REPLY_MS] —
     * replies are ~4.5s because IG's composer+send flow is slower than a
     * quick-reaction click). Bookkeeping in the DB happens on the IO
     * scope for each step.
     */
    private fun runBatchStep(steps: List<BatchStep>, index: Int) {
        if (index >= steps.size) {
            Log.i(
                TAG,
                "APPLY_PENDING: drain finished (${steps.size} step(s) processed, " +
                    "succeeded=$applyPendingSucceeded failed=$applyPendingFailed)."
            )
            batchInProgress = false
            postControlNotification()
            postCompletionNotification(
                title = getString(R.string.notif_completion_apply_pending_title),
                body = getString(
                    R.string.notif_completion_apply_pending_body,
                    applyPendingSucceeded,
                    applyPendingFailed,
                ),
            )
            returnToAppIfEnabled()
            return
        }
        val step = steps[index]
        val target = step.reel.threadTitle
        val currentHeader = currentHeaderTitle()

        if (currentHeader == target) {
            executeBatchStep(steps, index)
            return
        }

        // Wrong thread (or unknown state e.g. inbox / home) — drive IG to
        // the target conversation before proceeding.
        Log.i(
            TAG,
            "APPLY_PENDING: step ${index + 1}/${steps.size} needs navigation — " +
                "current='$currentHeader' target='$target'"
        )
        navigateToThreadAsync(target, attemptsLeft = NAV_MAX_ATTEMPTS) { success ->
            if (success) {
                // Extra settle so the RecyclerView renders the message
                // bubbles before we long-press the first one.
                mainHandler.postDelayed({
                    executeBatchStep(steps, index)
                }, NAV_POST_ARRIVAL_SETTLE_MS)
            } else {
                val err = "Não consegui navegar para '$target'"
                finishStepAsync(step.action.id, PendingActionEntity.STATUS_FAILED, err)
                Log.w(TAG, "APPLY_PENDING: step ${index + 1}/${steps.size} skipped — $err")
                mainHandler.postDelayed({
                    runBatchStep(steps, index + 1)
                }, BATCH_STEP_FAST_SKIP_MS)
            }
        }
    }

    /**
     * Dispatch the primitive for [steps][index]. Assumes IG is already
     * showing the correct thread (either because it was already open, or
     * because [navigateToThreadAsync] just brought us here).
     *
     * PoC-8 iter 4 (session 34): before firing the long-press we call
     * [locateReelWithScroll] to find the SPECIFIC bubble that matches
     * `step.reel` (by [ReelEntity.reelAuthor]). If the target isn't
     * currently visible, the message_list is scrolled backwards until
     * either the bubble surfaces or the scroll budget is exhausted.
     * Rows for Reels no longer in the thread are marked FAILED with a
     * clear reason and the executor moves on to the next step.
     */
    private fun executeBatchStep(steps: List<BatchStep>, index: Int) {
        val step = steps[index]
        val stepLabel = "step ${index + 1}/${steps.size}"
        updateProgressNotification(index + 1, steps.size)
        Log.i(
            TAG,
            "APPLY_PENDING: $stepLabel actionId=${step.action.id} kind=${step.action.kind} " +
                "reelId=${step.reel.id} thread='${step.reel.threadTitle}' author=${step.reel.reelAuthor}"
        )

        // Mark RUNNING before we dispatch the gestures so a peek at the
        // feed during batching shows the row as in-flight.
        markRunningAsync(step.action.id)

        val after: AfterLongPress = when (step.action.kind) {
            PendingActionEntity.KIND_REACT_HEART -> AfterLongPress.TapReaction("❤")
            PendingActionEntity.KIND_REACT_LAUGH -> AfterLongPress.TapReaction("😂")
            PendingActionEntity.KIND_REPLY_TEXT -> AfterLongPress.ReplyWithText(
                step.action.payload?.takeIf { it.isNotBlank() } ?: MOCK_REPLY_TEXT
            )
            else -> {
                val err = "kind desconhecido: ${step.action.kind}"
                finishStepAsync(step.action.id, PendingActionEntity.STATUS_FAILED, err)
                Log.w(TAG, "APPLY_PENDING: $stepLabel unknown kind, marked FAILED.")
                mainHandler.postDelayed({
                    runBatchStep(steps, index + 1)
                }, BATCH_STEP_FAST_SKIP_MS)
                return
            }
        }

        locateReelWithScroll(step.reel, scrollsLeft = BATCH_MAX_SCROLLS) { entry ->
            if (entry == null) {
                val err = "Reel do @${step.reel.reelAuthor ?: "?"} não encontrado após " +
                    "$BATCH_MAX_SCROLLS scrolls (reelId=${step.reel.id})"
                finishStepAsync(step.action.id, PendingActionEntity.STATUS_FAILED, err)
                Log.w(TAG, "APPLY_PENDING: $stepLabel skipped — $err")
                mainHandler.postDelayed({
                    runBatchStep(steps, index + 1)
                }, BATCH_STEP_FAST_SKIP_MS)
                return@locateReelWithScroll
            }

            // Target found (visible or after scrolling). Dispatch the
            // primitive. We can't easily distinguish success from failure
            // at this level (a11y callbacks only tell us the gesture went
            // out, not that IG accepted it), so for the PoC we mark the
            // row DONE optimistically once the primitive returns. The user
            // can visually confirm and re-enqueue if needed.
            dispatchLongPressOn(entry, after)
            finishStepAsync(step.action.id, PendingActionEntity.STATUS_DONE, null)

            val nextDelay = when (step.action.kind) {
                PendingActionEntity.KIND_REPLY_TEXT -> BATCH_STEP_INTERVAL_REPLY_MS
                else -> BATCH_STEP_INTERVAL_REACTION_MS
            }
            mainHandler.postDelayed({
                runBatchStep(steps, index + 1)
            }, nextDelay)
        }
    }

    private fun markRunningAsync(actionId: Long) {
        serviceScope.launch {
            AppDatabase.get(this@InstagramReaderService).pendingActionDao()
                .updateStatus(actionId, PendingActionEntity.STATUS_RUNNING)
        }
    }

    private fun finishStepAsync(actionId: Long, status: String, error: String?) {
        val now = System.currentTimeMillis()
        if (status == PendingActionEntity.STATUS_DONE) applyPendingSucceeded++
        else if (status == PendingActionEntity.STATUS_FAILED) applyPendingFailed++
        serviceScope.launch {
            AppDatabase.get(this@InstagramReaderService).pendingActionDao()
                .finish(actionId, status, now, error)
        }
    }

    /**
     * Overwrite the persistent notification with a "Aplicando N/M" status
     * while a batch is running. Reverts to the normal button row when the
     * batch finishes ([postControlNotification]).
     */
    private fun updateProgressNotification(currentStep: Int, total: Int) {
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_apply_progress, currentStep, total))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setContentIntent(pendingActivity(
                com.example.friendsreels.ui.feed.FeedActivity::class.java,
                requestCode = 0,
            ))
            .setProgress(total, currentStep, false)
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "updateProgressNotification: missing POST_NOTIFICATIONS", e)
        }
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
        private const val BUILD_TAG = "build=s40"

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

        // -----------------------------------------------------------------
        // PoC-8 iteration 3 — batching executor timing
        // -----------------------------------------------------------------
        /**
         * Delay before scheduling the NEXT batch step after firing a
         * reaction. Reactions complete quickly:
         *   long-press(600) + settle(1500) + click(~250) ≈ 2350ms
         * so 2500ms is safe.
         */
        private const val BATCH_STEP_INTERVAL_REACTION_MS = 2500L
        /**
         * Delay for replies, which are much slower:
         *   long-press(600) + settle(1500) + composer(900) + send(500)
         *   + IG's own animation to close the composer ≈ 3500ms + safety
         * Session-27 fix: s26 used 2500ms uniformly and reproduced a race
         * where step N+1's long-press fired before step N's send button
         * had been clicked, causing `REPLY: 'Responder' item not found`
         * (see docs/screen-dumps/Enfileirar.txt lines 51→66).
         */
        private const val BATCH_STEP_INTERVAL_REPLY_MS = 4500L
        /** Initial delay to give IG time to settle after being brought to front. */
        private const val BATCH_START_DELAY_MS = 800L
        /** Fast-skip interval used when we're just marking rows FAILED without touching IG. */
        private const val BATCH_STEP_FAST_SKIP_MS = 400L

        // -----------------------------------------------------------------
        // PoC-9 (session 33) — thread navigation timing
        // -----------------------------------------------------------------
        /**
         * Delay between successive navigation actions (BACK, direct_tab
         * click, inbox row click). Needs to cover IG's own slide-in/out
         * animation between screens. 1000ms is generous but safe — the
         * whole batch pays this only when it needs to switch conversations.
         */
        private const val NAV_STEP_SETTLE_MS = 1000L
        /**
         * Max attempts before [navigateToThreadAsync] gives up. Each
         * attempt dispatches one action + settles, so 10 * 1000ms = ~10s
         * of navigation time budget per step. Typical path from arbitrary
         * screen → thread is 3 attempts (Home→direct_tab, inbox→row,
         * verify).
         */
        private const val NAV_MAX_ATTEMPTS = 10
        /**
         * Extra time granted after we land on the correct thread before
         * running the primitive. IG's RecyclerView renders the message
         * bubbles progressively; long-pressing the first Reel too soon
         * has a chance of hitting an empty stub or a header. 1500ms
         * mirrors [POST_LONG_PRESS_SETTLE_MS] because the failure modes
         * are similar (a11y sees the tree before it's fully laid out).
         */
        private const val NAV_POST_ARRIVAL_SETTLE_MS = 1500L

        // -----------------------------------------------------------------
        // PoC-8 iter 4 (session 34) — locate specific Reel via scroll
        // -----------------------------------------------------------------
        /**
         * Max backwards scrolls per batch step to bring the target Reel
         * into view. IG opens conversations at the bottom, so enqueued
         * Reels are usually older messages scrolled off-screen. 20 scrolls
         * @ 800ms = ~16s hard-cap per step, which is generous enough for
         * moderately deep histories. If the user has hundreds of messages
         * above the target, they should re-open the conversation closer
         * to the target first.
         */
        private const val BATCH_MAX_SCROLLS = 20
        /** Delay after a `ACTION_SCROLL_BACKWARD` before re-enumerating. */
        private const val LOCATE_SCROLL_SETTLE_MS = 800L
        /** Duration of the fallback swipe DOWN when a11y scroll is refused. */
        private const val LOCATE_SWIPE_DURATION_MS = 500L

        // -----------------------------------------------------------------
        // s38 — batch URL enrichment timing
        // -----------------------------------------------------------------
        /**
         * Hard cap on how long a single Reel is allowed to spend inside
         * the batch enrichment pipeline. Covers: navigate to thread
         * (~5-10s worst case with scrolls) + open viewer settle (~2s) +
         * share sheet load (~2s) + clipboard read (~1s) + safety. If
         * the DB row still has no URL after this budget we mark the
         * step as FAILED and continue with the next Reel.
         */
        private const val BATCH_ENRICH_STEP_TIMEOUT_MS = 45_000L
        /** Poll the DB every this often waiting for the URL to land. */
        private const val BATCH_ENRICH_POLL_INTERVAL_MS = 500L
        /**
         * Space between finishing one Reel (URL captured or timed out)
         * and starting the next. Gives the clipboard bridge / BACK
         * gestures time to settle so we don't overlap with the
         * `handleClipboardCaptured` path from the previous step.
         */
        private const val BATCH_ENRICH_SPACING_MS = 1_500L

        // -----------------------------------------------------------------
        // PoC-8 iteration 3 part B — history discovery (auto scroll)
        // -----------------------------------------------------------------
        /** Duration of the fallback swipe-down gesture when a11y scroll is refused. */
        private const val HISTORY_SCROLL_DURATION_MS = 500L
        /** Delay between the scroll landing and enumeration (RecyclerView needs a beat to settle). */
        private const val HISTORY_SCROLL_SETTLE_MS = 800L
        /** After this many consecutive scrolls with zero new inserts we assume we're at the top. */
        private const val HISTORY_STOP_AFTER_N_EMPTY = 3
        /** Hard cap on scrolls per run so we never loop forever. */
        private const val HISTORY_MAX_SCROLLS = 30

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
         * Drain the `pending_actions` table (PoC-8 iteration 3 — batching).
         * See [applyPendingActions] for the full life-cycle: for each
         * PENDING row whose `reels.threadTitle` matches the conversation
         * currently open in Instagram, we drive the same primitives
         * PoC-5/PoC-6 use, then mark the row DONE/FAILED. Rows for other
         * threads are marked FAILED with a "wrong thread" hint so the user
         * knows to switch conversations before applying again.
         */
        const val ACTION_APPLY_PENDING = "com.example.friendsreels.ACTION_APPLY_PENDING"

        /**
         * Auto-scroll the current DM conversation upwards, enumerating
         * Reels at every step, until three consecutive scrolls fail to
         * insert any new row (approximation of "reached the top") or a
         * safety cap of scrolls is hit. Uses `ACTION_SCROLL_BACKWARD` on
         * the `message_list` when available (cleanest), falling back to a
         * `dispatchGesture` swipe DOWN inside the list. See
         * [discoverReelsHistory].
         */
        const val ACTION_DISCOVER_REELS_HISTORY = "com.example.friendsreels.ACTION_DISCOVER_REELS_HISTORY"

        /**
         * Broadcast sent by [com.example.friendsreels.ClipboardCaptureActivity]
         * with the current clipboard text. See [readReelUrlFromClipboard]
         * for why this indirection is needed on Android 10+.
         */
        const val ACTION_CLIPBOARD_CAPTURED = "com.example.friendsreels.ACTION_CLIPBOARD_CAPTURED"
        const val EXTRA_CLIPBOARD_TEXT = "clipboard_text"

        /**
         * Capture the URL of a SPECIFIC Reel already in the DB (spec §3 —
         * "Reels antigos já existentes nas conversas, sem ter de os
         * reenviar manualmente"). Bring IG to front, navigate to the
         * Reel's thread via [navigateToThreadAsync], find the exact
         * bubble by `reelAuthor` via [locateReelWithScroll], tap it to
         * open the viewer, then let the existing copy-link chain
         * ([tapShareInReelViewer] → [clickCopyLinkInShareSheet] → clip-
         * board bridge → [handleClipboardCaptured]) do the persistence.
         *
         * Fires with:
         *   Intent(ACTION_ENRICH_REEL_URL).putExtra(EXTRA_REEL_ID, id)
         *
         * Used by the feed's "🔗 Preparar Reel" button that appears on
         * a page whose Reel has no `reelUrl` yet.
         */
        const val ACTION_ENRICH_REEL_URL = "com.example.friendsreels.ACTION_ENRICH_REEL_URL"
        const val EXTRA_REEL_ID = "reel_id"

        /**
         * Batch counterpart of [ACTION_ENRICH_REEL_URL]: walks every
         * `reels` row where `reelUrl IS NULL`, ordered by `threadTitle`
         * to minimise thread navigation, and runs the same enrichment
         * primitive on each (nav → locate → viewer → share → copy).
         * Progress is published through [BatchEnrichmentBus] so the
         * Settings screen can render a live counter and a Cancel
         * button. See [enrichAllMissingUrls] for the state machine.
         *
         * Fires with:
         *   Intent(ACTION_ENRICH_ALL_MISSING_URLS)
         *
         * Idempotent: if a batch is already running the second
         * broadcast is ignored (see the guard on [batchEnrichmentInProgress]).
         */
        const val ACTION_ENRICH_ALL_MISSING_URLS =
            "com.example.friendsreels.ACTION_ENRICH_ALL_MISSING_URLS"

        /**
         * Ask the running batch enrichment to stop. Sets
         * [batchEnrichmentCancelled] and lets the current Reel finish
         * before terminating — we don't try to abort an in-flight
         * PoC-7 chain because doing so could leave IG on the share
         * sheet with a pending clipboard write and the row half-
         * updated.
         */
        const val ACTION_ENRICH_ALL_CANCEL =
            "com.example.friendsreels.ACTION_ENRICH_ALL_CANCEL"

        /** SharedPreferences file shared between the UI and the service. */
        const val PREFS_NAME = "friends_reels_prefs"

        /**
         * When true, the reaction actions ignore Reels the user sent
         * themselves (`sender_avatar` absent). Default true — this matches
         * the MVP requirement of never re-reacting to our own shares.
         */
        const val PREF_IGNORE_SENT = "ignore_sent_reels"
        const val PREF_IGNORE_SENT_DEFAULT = true

        /**
         * When true, the feed's VerticalPager swaps its swipe direction:
         * swipe UP goes to the previous Reel, swipe DOWN goes to the next.
         * Default false — matches the native Reels feed direction (spec
         * §3 "Comportamento padrão"). Toggle exposed in `SettingsActivity`.
         */
        const val PREF_INVERT_SWIPE = "invert_swipe_direction"
        const val PREF_INVERT_SWIPE_DEFAULT = false

        /**
         * Selection mode for the feed (spec §8). Controls whether the
         * `tracked_threads` list is used as a whitelist or a blacklist:
         * - [SELECTION_MODE_NONE]: no filter — feed shows every Reel.
         * - [SELECTION_MODE_INCLUDE_ONLY]: only Reels whose
         *   `threadTitle` matches a row in `tracked_threads`.
         * - [SELECTION_MODE_EXCLUDE_SELECTED]: every Reel except those
         *   from tracked threads.
         *
         * Default: [SELECTION_MODE_NONE] — on first launch nothing is
         * hidden until the user explicitly picks a mode + selects
         * threads on the Settings screen.
         */
        const val PREF_SELECTION_MODE = "selection_mode"
        const val SELECTION_MODE_NONE = "NONE"
        const val SELECTION_MODE_INCLUDE_ONLY = "INCLUDE_ONLY"
        const val SELECTION_MODE_EXCLUDE_SELECTED = "EXCLUDE_SELECTED"
        const val PREF_SELECTION_MODE_DEFAULT = SELECTION_MODE_NONE

        /**
         * When true (default), long-running actions bring the Friends
         * Reels feed to the front once they finish so the user doesn't
         * get stranded in Instagram (or in the share sheet). Applied by
         * [returnToAppIfEnabled] at the tail of `enrichAllMissingUrls`,
         * `applyPendingActions`, and `discoverReelsHistory`.
         */
        const val PREF_RETURN_TO_APP_ON_FINISH = "return_to_app_on_finish"
        const val PREF_RETURN_TO_APP_ON_FINISH_DEFAULT = true

        /**
         * Persisted mirror of [BatchEnrichmentBus.LastResult] so the
         * Settings screen still shows "Última execução: X preparados,
         * Y falharam" after the app process (or the a11y service) is
         * killed and re-created. Written at the end of every
         * [processBatchEnrichmentStep] terminal branch; loaded by
         * [restorePersistedBatchEnrichResult] on service connect.
         */
        private const val PREF_LAST_ENRICH_HAS = "last_enrich_has"
        private const val PREF_LAST_ENRICH_SUCCEEDED = "last_enrich_succeeded"
        private const val PREF_LAST_ENRICH_FAILED = "last_enrich_failed"
        private const val PREF_LAST_ENRICH_CANCELLED = "last_enrich_cancelled"

        private const val NOTIF_CHANNEL_ID = "friends_reels_controls"
        private const val NOTIF_ID = 1001

        /**
         * Separate channel for completion notifications (s39). Uses
         * IMPORTANCE_DEFAULT so the user actually notices when a batch
         * finishes; the persistent control notification stays on LOW.
         */
        private const val NOTIF_CHANNEL_STATUS_ID = "friends_reels_status"
        private const val NOTIF_ID_COMPLETION = 1003
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
    //
    // Session-27 change: Android's collapsed notification only shows up to
    // 3 action buttons; with `PRIORITY_LOW + ongoing` on OnePlus/OxygenOS
    // it stays collapsed. In s26 we had 6 buttons — only the first three
    // (❤ 😂 👀) were visible and the batching/discovery ones were
    // unreachable. So we now keep only the three that matter for the
    // batching-first flow: 🔍 discover, 🔗 copy URL (per-Reel enrichment),
    // ▶ apply pending queue. Direct reactions/replies are still available
    // in the MainActivity screen if the user needs them for one-off tests.
    // A `contentIntent` opens the feed when the body of the notification
    // is tapped — so the user's finger has somewhere useful to land.
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
            .setContentIntent(pendingActivity(
                com.example.friendsreels.ui.feed.FeedActivity::class.java,
                requestCode = 0,
            ))
            .addAction(
                0,
                getString(R.string.notif_action_discover),
                pendingBroadcast(ACTION_DISCOVER_REELS, requestCode = 10)
            )
            .addAction(
                0,
                getString(R.string.notif_action_copy_url),
                pendingBroadcast(ACTION_COPY_REEL_URL, requestCode = 9)
            )
            .addAction(
                0,
                getString(R.string.notif_action_apply),
                pendingBroadcast(ACTION_APPLY_PENDING, requestCode = 11)
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
        val system = getSystemService(NotificationManager::class.java) ?: return
        val control = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        system.createNotificationChannel(control)
        val status = NotificationChannel(
            NOTIF_CHANNEL_STATUS_ID,
            getString(R.string.notif_channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_status_description)
            setShowBadge(true)
            enableVibration(false)
        }
        system.createNotificationChannel(status)
    }

    // ---------------------------------------------------------------------
    // Completion feedback (session 39)
    //
    // Long-running actions (batch enrichment, apply pending, discover
    // history) can leave the user stranded inside Instagram with no
    // visible sign that the work is done. These helpers close that gap:
    //  - `postCompletionNotification` shows a transient, tap-to-open-feed
    //    notification on the higher-importance status channel.
    //  - `returnToAppIfEnabled` brings FeedActivity to the front when the
    //    user opted in via `PREF_RETURN_TO_APP_ON_FINISH` (default true).
    // ---------------------------------------------------------------------

    private fun postCompletionNotification(title: String, body: String) {
        createNotificationChannel()
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_STATUS_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$body\n\n" + getString(R.string.notif_completion_tap_hint)
            ))
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingActivity(
                com.example.friendsreels.ui.feed.FeedActivity::class.java,
                requestCode = 2001,
            ))
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            Log.w(TAG, "Completion notification skipped — notifications disabled by user.")
            return
        }
        try {
            nm.notify(NOTIF_ID_COMPLETION, builder.build())
            Log.i(TAG, "Completion notification posted: '$title' — $body")
        } catch (e: SecurityException) {
            Log.w(TAG, "Completion notification skipped — missing POST_NOTIFICATIONS.", e)
        }
    }

    private fun returnToAppIfEnabled() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(
            PREF_RETURN_TO_APP_ON_FINISH,
            PREF_RETURN_TO_APP_ON_FINISH_DEFAULT,
        )
        if (!enabled) {
            Log.i(TAG, "returnToAppIfEnabled: pref disabled — staying on IG.")
            return
        }
        val intent = Intent(this, com.example.friendsreels.ui.feed.FeedActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        try {
            startActivity(intent)
            Log.i(TAG, "returnToAppIfEnabled: FeedActivity launched.")
        } catch (e: Exception) {
            Log.w(TAG, "returnToAppIfEnabled: failed to launch FeedActivity", e)
        }
    }

    /**
     * Persist [result] into SharedPreferences so it can be shown in the
     * Settings screen even after the app process (or a11y service) is
     * killed. Called from the terminal branch of
     * [processBatchEnrichmentStep].
     */
    private fun persistBatchEnrichResult(result: BatchEnrichmentBus.LastResult) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_LAST_ENRICH_HAS, true)
            .putInt(PREF_LAST_ENRICH_SUCCEEDED, result.succeeded)
            .putInt(PREF_LAST_ENRICH_FAILED, result.failed)
            .putBoolean(PREF_LAST_ENRICH_CANCELLED, result.cancelled)
            .apply()
    }

    /**
     * Seed [BatchEnrichmentBus] with the last persisted result (if any)
     * so the Settings screen shows the correct history even on the
     * first render after a process restart.
     */
    private fun restorePersistedBatchEnrichResult() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_LAST_ENRICH_HAS, false)) return
        val restored = BatchEnrichmentBus.LastResult(
            succeeded = prefs.getInt(PREF_LAST_ENRICH_SUCCEEDED, 0),
            failed = prefs.getInt(PREF_LAST_ENRICH_FAILED, 0),
            cancelled = prefs.getBoolean(PREF_LAST_ENRICH_CANCELLED, false),
        )
        BatchEnrichmentBus.update(
            BatchEnrichmentBus.State(
                running = false,
                currentIndex = 0,
                total = 0,
                lastResult = restored,
            )
        )
        Log.i(TAG, "restorePersistedBatchEnrichResult: restored $restored to bus.")
    }

    /**
     * Overwrite the persistent control notification with a "A preparar
     * URLs %1/%2…" status while [enrichAllMissingUrls] is running.
     * Reverts to the normal button row via [postControlNotification]
     * when the batch finishes. Mirrors [updateProgressNotification] /
     * [updateHistoryProgressNotification].
     */
    private fun updateBatchEnrichProgressNotification(currentStep: Int, total: Int) {
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_enrich_all_progress, currentStep, total))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setContentIntent(pendingActivity(
                com.example.friendsreels.ui.feed.FeedActivity::class.java,
                requestCode = 0,
            ))
            .setProgress(total.coerceAtLeast(1), currentStep, false)
            .addAction(
                0,
                getString(R.string.notif_action_cancel),
                pendingBroadcast(ACTION_ENRICH_ALL_CANCEL, requestCode = 12),
            )
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "updateBatchEnrichProgressNotification: missing POST_NOTIFICATIONS", e)
        }
    }

    private fun pendingBroadcast(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, requestCode, intent, flags)
    }

    private fun pendingActivity(cls: Class<*>, requestCode: Int): PendingIntent {
        val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, requestCode, intent, flags)
    }
}
