package com.example.friendsreels.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.friendsreels.R
import com.example.friendsreels.data.PendingActionEntity
import com.example.friendsreels.data.ReelEntity
import com.example.friendsreels.service.InstagramReaderService
import com.example.friendsreels.ui.player.ReelPlayerActivity
import com.example.friendsreels.ui.player.buildReelWebView
import com.example.friendsreels.ui.player.toEmbedUrl
import com.example.friendsreels.ui.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Reels feed (spec §3). Uses a VerticalPager so each Reel
 * takes the whole screen and the user swipes up/down to navigate,
 * matching the native IG Reels experience.
 *
 * Session 36: each page now hosts an **inline WebView** that auto-plays
 * the Reel embed URL, replacing the "tap to play" button of s35. Only
 * the current page's WebView is instantiated (Compose Pager keyeps
 * off-screen pages composed but not rendered), so we don't blow up
 * memory. When the user swipes away, the WebView is disposed and its
 * Chromium session freed.
 *
 * Reels without a URL (haven't been through the `🔗` capture pass yet)
 * fall back to a placeholder telling the user to capture the URL. In a
 * future iteration this will trigger an on-demand `ACTION_ENRICH_REEL_URL`
 * that drives IG through the copy-link flow just for that Reel.
 *
 * Chip row (spec §7): shows direction (recebido/enviado), SEEN, the
 * CURRENT reaction (single chip — IG only allows one reaction at a
 * time), and REPLIED.
 *
 * 3-dot menu (spec §12): "Abrir Reel no Instagram nativo", "Cancelar
 * acções pendentes", "Definições". The session-35 "Abrir conversa no
 * Instagram" was removed because it only fired the launcher intent and
 * needed manual navigation — no value over just opening IG.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(invertSwipe: Boolean = false) {
    val vm: FeedViewModel = viewModel()
    val reels by vm.reels.collectAsState()
    val uiStates by vm.uiStates.collectAsState()
    val pendingCount by vm.pendingCount.collectAsState()
    val context = LocalContext.current

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feed_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            ApplyPendingBar(
                pendingCount = pendingCount,
                onApply = {
                    context.sendBroadcast(
                        Intent(InstagramReaderService.ACTION_APPLY_PENDING).setPackage(context.packageName)
                    )
                    Toast.makeText(context, R.string.feed_apply_hint, Toast.LENGTH_LONG).show()
                },
                onClearTerminal = { vm.clearTerminal() },
            )
        },
    ) { padding ->
        if (reels.isEmpty()) {
            EmptyState(padding)
        } else {
            val orderedReels = if (invertSwipe) reels.asReversed() else reels
            val pagerState = rememberPagerState(pageCount = { orderedReels.size })
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { page ->
                val reel = orderedReels[page]
                val state = uiStates[reel.id] ?: ReelUiState()
                val isCurrent = pagerState.currentPage == page ||
                    pagerState.targetPage == page
                ReelPage(
                    reel = reel,
                    state = state,
                    isCurrent = isCurrent,
                    onMarkSeen = { vm.markSeen(reel.id) },
                    onOpenInInstagram = { openReelInInstagram(context, reel) },
                    onOpenPlayer = { openReelInPlayer(context, reel) },
                    onQueueHeart = {
                        vm.enqueueReaction(reel, PendingActionEntity.KIND_REACT_HEART) { r ->
                            toastFor(context, r)
                        }
                    },
                    onQueueLaugh = {
                        vm.enqueueReaction(reel, PendingActionEntity.KIND_REACT_LAUGH) { r ->
                            toastFor(context, r)
                        }
                    },
                    onQueueReply = { text ->
                        vm.enqueueReply(reel, text) { r -> toastFor(context, r) }
                    },
                    onCancelPending = { vm.cancelPendingForReel(reel.id) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }
        }
    }
}

private fun toastFor(context: Context, result: FeedViewModel.Result) {
    val res = when (result) {
        FeedViewModel.Result.Queued -> R.string.feed_queued_toast
        FeedViewModel.Result.AlreadyQueued -> R.string.feed_already_queued_toast
        FeedViewModel.Result.Empty -> R.string.feed_reply_empty_toast
    }
    Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ApplyPendingBar(
    pendingCount: Int,
    onApply: () -> Unit,
    onClearTerminal: () -> Unit,
) {
    Surface(tonalElevation = 4.dp, color = Color.Black.copy(alpha = 0.85f)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (pendingCount == 0) {
                Text(
                    text = stringResource(R.string.feed_apply_pending_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            } else {
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_apply_pending, pendingCount))
                }
            }
            TextButton(onClick = onClearTerminal, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feed_clear_terminal), color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(padding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.feed_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feed_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ReelPage(
    reel: ReelEntity,
    state: ReelUiState,
    isCurrent: Boolean,
    onMarkSeen: () -> Unit,
    onOpenInInstagram: () -> Unit,
    onOpenPlayer: () -> Unit,
    onQueueHeart: () -> Unit,
    onQueueLaugh: () -> Unit,
    onQueueReply: (String) -> Unit,
    onCancelPending: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var replyDialogOpen by remember { mutableStateOf(false) }

    // Mark SEEN once when this page becomes current for the first time.
    DisposableEffect(reel.id, isCurrent) {
        if (isCurrent) onMarkSeen()
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF1A0033), Color(0xFF000000)))
            )
    ) {
        // Video area — takes the whole background. Inline WebView when
        // this is the current page and we have a URL; placeholder
        // otherwise.
        InlineReelPlayer(
            reel = reel,
            isCurrent = isCurrent,
            onOpenPlayer = onOpenPlayer,
        )

        // Top-right 3-dot menu.
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)) {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.feed_menu_content_desc),
                    tint = Color.White,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feed_menu_open_in_ig)) },
                    enabled = !reel.reelUrl.isNullOrBlank(),
                    onClick = { menuOpen = false; onOpenInInstagram() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feed_menu_cancel_pending)) },
                    enabled = state.pendingHeart || state.pendingLaugh || state.pendingReply,
                    onClick = { menuOpen = false; onCancelPending() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feed_menu_settings)) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
            }
        }

        // Chips overlay under the top bar. State chips (seen, reaction,
        // replied). Reserved space between the top app bar and the video.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 16.dp),
        ) {
            StateChipRow(reel = reel, state = state)
        }

        // Bottom gradient scrim + metadata + actions.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000))
                    )
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetadataBlock(reel)
                ActionRow(
                    state = state,
                    onQueueHeart = onQueueHeart,
                    onQueueLaugh = onQueueLaugh,
                    onQueueReply = { replyDialogOpen = true },
                )
            }
        }
    }

    if (replyDialogOpen) {
        ReplyDialog(
            initialText = InstagramReaderService.MOCK_REPLY_TEXT,
            onDismiss = { replyDialogOpen = false },
            onSend = { text ->
                replyDialogOpen = false
                onQueueReply(text)
            },
        )
    }
}

/**
 * Inline WebView that auto-plays the Reel embed URL when this page is
 * current. Off-current pages render a lightweight placeholder so we
 * don't burn memory / battery on multiple Chromium instances.
 *
 * When the Reel has no `reelUrl` yet, shows a placeholder inviting the
 * user to capture it via the `🔗` notification button. A future
 * iteration will replace this with an on-demand enrichment trigger.
 */
@Composable
private fun InlineReelPlayer(
    reel: ReelEntity,
    isCurrent: Boolean,
    onOpenPlayer: () -> Unit,
) {
    val url = reel.reelUrl
    if (url.isNullOrBlank()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.feed_no_url_yet_title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.feed_no_url_yet_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
        return
    }
    if (!isCurrent) {
        // Off-current page: minimal placeholder. IG's own vertical feed
        // does the same (pauses/blanks non-current pages).
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }
    // Current page + we have a URL → mount the WebView.
    val embedUrl = remember(url) { toEmbedUrl(url) }
    var webViewRef by remember(reel.id) { mutableStateOf<WebView?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().clickable { onOpenPlayer() },
            factory = { ctx ->
                buildReelWebView(
                    ctx,
                    onReceivedError = { /* silent inline — full player handles errors */ },
                ).apply {
                    webViewRef = this
                    loadUrl(embedUrl)
                }
            },
        )
    }
    // Clean up Chromium session when we leave this page.
    DisposableEffect(reel.id) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.loadUrl("about:blank")
            webViewRef?.onPause()
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}

@Composable
private fun StateChipRow(reel: ReelEntity, state: ReelUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DirectionChip(reel.direction)
        if (state.seen) StateChip(stringResource(R.string.feed_chip_seen), Color(0xFF3F51B5))
        state.currentReaction?.let { kind ->
            val label = when (kind) {
                PendingActionEntity.KIND_REACT_HEART -> stringResource(R.string.feed_chip_reacted_heart)
                PendingActionEntity.KIND_REACT_LAUGH -> stringResource(R.string.feed_chip_reacted_laugh)
                else -> null
            }
            val bg = when (kind) {
                PendingActionEntity.KIND_REACT_HEART -> Color(0xFFC2185B)
                PendingActionEntity.KIND_REACT_LAUGH -> Color(0xFFF57C00)
                else -> Color.Gray
            }
            if (label != null) StateChip(label, bg)
        }
        if (state.replied) StateChip(stringResource(R.string.feed_chip_replied), Color(0xFF00695C))
    }
}

@Composable
private fun DirectionChip(direction: String) {
    val (label, bg) = when (direction) {
        "RECEIVED" -> stringResource(R.string.feed_chip_received) to Color(0xFF2E7D32)
        "SENT" -> stringResource(R.string.feed_chip_sent) to Color(0xFF7B1FA2)
        else -> direction to Color.Gray
    }
    StateChip(label, bg)
}

@Composable
private fun StateChip(label: String, bg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MetadataBlock(reel: ReelEntity) {
    Text(
        text = reel.reelAuthor?.let { "@$it" } ?: stringResource(R.string.feed_unknown_author),
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = whoAndWhereLabel(reel),
        color = Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.feed_discovered_at, formatEpoch(reel.discoveredAt)),
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun whoAndWhereLabel(reel: ReelEntity): String = when (reel.direction) {
    "SENT" -> stringResource(R.string.feed_sent_in, reel.threadTitle)
    "RECEIVED" -> {
        val sender = reel.dmSender
        when {
            sender.isNullOrBlank() -> stringResource(R.string.feed_received_in, reel.threadTitle)
            sender == reel.threadTitle -> stringResource(R.string.feed_received_from, sender)
            else -> stringResource(R.string.feed_received_from_in, sender, reel.threadTitle)
        }
    }
    else -> stringResource(R.string.feed_shared_by, reel.threadTitle)
}

@Composable
private fun ActionRow(
    state: ReelUiState,
    onQueueHeart: () -> Unit,
    onQueueLaugh: () -> Unit,
    onQueueReply: () -> Unit,
) {
    val heartHighlighted = state.currentReaction == PendingActionEntity.KIND_REACT_HEART ||
        state.pendingHeart
    val laughHighlighted = state.currentReaction == PendingActionEntity.KIND_REACT_LAUGH ||
        state.pendingLaugh
    val replyHighlighted = state.replied || state.pendingReply
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionButton(
            emoji = "❤",
            label = if (state.pendingHeart) stringResource(R.string.feed_action_queued)
            else stringResource(R.string.feed_action_react),
            highlighted = heartHighlighted,
            enabled = !state.pendingHeart,
            onClick = onQueueHeart,
        )
        ActionButton(
            emoji = "😂",
            label = if (state.pendingLaugh) stringResource(R.string.feed_action_queued)
            else stringResource(R.string.feed_action_react),
            highlighted = laughHighlighted,
            enabled = !state.pendingLaugh,
            onClick = onQueueLaugh,
        )
        ActionButton(
            emoji = "💬",
            label = if (state.pendingReply) stringResource(R.string.feed_action_queued)
            else stringResource(R.string.feed_action_reply),
            highlighted = replyHighlighted,
            enabled = !state.pendingReply,
            onClick = onQueueReply,
        )
    }
    if (state.failedActions > 0) {
        Text(
            text = stringResource(R.string.feed_failed_actions_notice, state.failedActions),
            color = Color(0xFFEF9A9A),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ActionButton(
    emoji: String,
    label: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (highlighted) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ReplyDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_reply_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.feed_reply_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    label = { Text(stringResource(R.string.feed_reply_dialog_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(text) },
                enabled = text.trim().isNotEmpty(),
            ) {
                Text(stringResource(R.string.feed_reply_dialog_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.feed_reply_dialog_cancel))
            }
        },
    )
}

private fun formatEpoch(epochMs: Long): String {
    val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

private fun openReelInPlayer(context: Context, reel: ReelEntity) {
    val url = reel.reelUrl ?: return
    val intent = Intent(context, ReelPlayerActivity::class.java)
        .putExtra(ReelPlayerActivity.EXTRA_URL, url)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, R.string.feed_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun openReelInInstagram(context: Context, reel: ReelEntity) {
    val url = reel.reelUrl ?: return
    val uri = Uri.parse(url)
    val igIntent = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.instagram.android")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(igIntent)
        return
    } catch (_: Exception) { /* fall through */ }
    val browserIntent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(browserIntent)
    } catch (_: Exception) {
        Toast.makeText(context, R.string.feed_open_failed, Toast.LENGTH_SHORT).show()
    }
}
