package com.example.friendsreels.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.friendsreels.R
import com.example.friendsreels.data.PendingActionEntity
import com.example.friendsreels.data.ReelEntity
import com.example.friendsreels.service.InstagramReaderService
import com.example.friendsreels.ui.player.ReelPlayerActivity
import com.example.friendsreels.ui.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Reels feed (spec §3). Uses a VerticalPager so each Reel
 * takes the whole screen and the user swipes up/down to navigate. The
 * pager is optionally inverted (swipe up = previous) via a setting,
 * matching spec §3 "definição opcional para inverter".
 *
 * Each page renders:
 * - A dark hero area with the direction/state chips and a big central
 *   "▶ Ver Reel aqui" button that launches the WebView player.
 * - A metadata block at the bottom (author, sender, thread, date).
 * - Three action buttons for Reagir ❤ / 😂 / Responder — writing into
 *   `pending_actions`. Batching executes them all at once via the
 *   notification's "▶ Aplicar fila" button.
 * - A 3-dot menu (spec §12) with "Abrir Reel no Instagram nativo",
 *   "Abrir conversa no Instagram", "Cancelar pendentes deste Reel" and
 *   "Definições".
 *
 * The old batching status bar (pending count + "Limpar histórico") is
 * kept as a compact bottom overlay so the user still gets feedback on
 * how many actions are ready to apply.
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { page ->
                val reel = orderedReels[page]
                val state = uiStates[reel.id] ?: ReelUiState()
                ReelPage(
                    reel = reel,
                    state = state,
                    onPlayInApp = {
                        vm.markSeen(reel.id)
                        openReelInPlayer(context, reel)
                    },
                    onOpenInInstagram = {
                        vm.markSeen(reel.id)
                        openReelInInstagram(context, reel)
                    },
                    onOpenThreadInInstagram = { openThreadInInstagram(context, reel) },
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
    Surface(
        tonalElevation = 4.dp,
        color = Color.Black.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                Text(
                    stringResource(R.string.feed_clear_terminal),
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(padding)
            .padding(24.dp),
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
    onPlayInApp: () -> Unit,
    onOpenInInstagram: () -> Unit,
    onOpenThreadInInstagram: () -> Unit,
    onQueueHeart: () -> Unit,
    onQueueLaugh: () -> Unit,
    onQueueReply: (String) -> Unit,
    onCancelPending: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var replyDialogOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A0033), Color(0xFF000000)),
                )
            )
    ) {
        // Top-right 3-dot menu (spec §12).
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.feed_menu_content_desc),
                    tint = Color.White,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feed_menu_open_in_ig)) },
                    enabled = !reel.reelUrl.isNullOrBlank(),
                    onClick = { menuOpen = false; onOpenInInstagram() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feed_menu_open_thread_in_ig)) },
                    onClick = { menuOpen = false; onOpenThreadInInstagram() },
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

        // Hero area (upper 55%): big play button + chips.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .padding(horizontal = 24.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StateChipRow(reel = reel, state = state)
            Spacer(Modifier.height(24.dp))
            if (!reel.reelUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { onPlayInApp() }
                        .padding(32.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.feed_play_in_app),
                        tint = Color.White,
                        modifier = Modifier
                            .width(72.dp)
                            .height(72.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.feed_play_in_app),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.feed_no_url_yet_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Bottom area: metadata + actions.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
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

@Composable
private fun StateChipRow(reel: ReelEntity, state: ReelUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DirectionChip(reel.direction)
        if (state.seen) StateChip(stringResource(R.string.feed_chip_seen), Color(0xFF3F51B5))
        if (state.reactedHeart) StateChip(stringResource(R.string.feed_chip_reacted_heart), Color(0xFFC2185B))
        if (state.reactedLaugh) StateChip(stringResource(R.string.feed_chip_reacted_laugh), Color(0xFFF57C00))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ActionButton(
            emoji = "❤",
            label = if (state.pendingHeart) stringResource(R.string.feed_action_queued)
            else stringResource(R.string.feed_action_react),
            highlighted = state.reactedHeart || state.pendingHeart,
            enabled = !state.pendingHeart,
            onClick = onQueueHeart,
        )
        ActionButton(
            emoji = "😂",
            label = if (state.pendingLaugh) stringResource(R.string.feed_action_queued)
            else stringResource(R.string.feed_action_react),
            highlighted = state.reactedLaugh || state.pendingLaugh,
            enabled = !state.pendingLaugh,
            onClick = onQueueLaugh,
        )
        ActionButton(
            emoji = "💬",
            label = if (state.pendingReply) stringResource(R.string.feed_action_queued)
            else stringResource(R.string.feed_action_reply),
            highlighted = state.replied || state.pendingReply,
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
    val bg = if (highlighted) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f)
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

/**
 * Best-effort "abrir conversa no Instagram" (spec §12/13). We don't have
 * a stable thread_id (see §6.2 of PROJECT_PROGRESS), so we open the IG
 * launcher intent and rely on the a11y service to navigate to the
 * correct thread when the user next fires an action. As a fallback we
 * at least bring IG to the foreground.
 */
private fun openThreadInInstagram(context: Context, reel: ReelEntity) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
    if (launch == null) {
        Toast.makeText(context, R.string.feed_open_failed, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(
            context,
            context.getString(R.string.feed_open_thread_hint, reel.threadTitle),
            Toast.LENGTH_LONG,
        ).show()
    } catch (_: Exception) {
        Toast.makeText(context, R.string.feed_open_failed, Toast.LENGTH_SHORT).show()
    }
}
