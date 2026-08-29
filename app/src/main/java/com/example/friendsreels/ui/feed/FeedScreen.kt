package com.example.friendsreels.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.friendsreels.R
import com.example.friendsreels.data.PendingActionEntity
import com.example.friendsreels.data.ReelEntity
import com.example.friendsreels.service.InstagramReaderService
import com.example.friendsreels.ui.player.ReelPlayerActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen() {
    val vm: FeedViewModel = viewModel()
    val reels by vm.reels.collectAsState()
    val pendingCount by vm.pendingCount.collectAsState()
    val pendingPairs by vm.pendingByReelKind.collectAsState()
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.feed_title)) }) },
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(reels, key = { it.id }) { reel ->
                    ReelCard(
                        reel = reel,
                        pendingKinds = pendingPairs,
                        onPlayInApp = {
                            vm.markSeen(reel.id)
                            openReelInPlayer(context, reel)
                        },
                        onOpenInInstagram = {
                            vm.markSeen(reel.id)
                            openReelInInstagram(context, reel)
                        },
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
                        onQueueReply = {
                            vm.enqueueReply(reel, InstagramReaderService.MOCK_REPLY_TEXT) { r ->
                                toastFor(context, r)
                            }
                        },
                        onCancelPending = { vm.cancelPendingForReel(reel.id) },
                    )
                }
            }
        }
    }
}

private fun toastFor(context: Context, result: FeedViewModel.Result) {
    val res = when (result) {
        FeedViewModel.Result.Queued -> R.string.feed_queued_toast
        FeedViewModel.Result.AlreadyQueued -> R.string.feed_already_queued_toast
    }
    Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
}

@Composable
private fun ApplyPendingBar(
    pendingCount: Int,
    onApply: () -> Unit,
    onClearTerminal: () -> Unit,
) {
    Surface(tonalElevation = 4.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (pendingCount == 0) {
                Text(
                    text = stringResource(R.string.feed_apply_pending_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_apply_pending, pendingCount))
                }
            }
            TextButton(onClick = onClearTerminal, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feed_clear_terminal))
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.feed_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feed_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReelCard(
    reel: ReelEntity,
    pendingKinds: Set<String>,
    onPlayInApp: () -> Unit,
    onOpenInInstagram: () -> Unit,
    onQueueHeart: () -> Unit,
    onQueueLaugh: () -> Unit,
    onQueueReply: () -> Unit,
    onCancelPending: () -> Unit,
) {
    val hasHeart = pendingKinds.contains("${reel.id}:${PendingActionEntity.KIND_REACT_HEART}")
    val hasLaugh = pendingKinds.contains("${reel.id}:${PendingActionEntity.KIND_REACT_LAUGH}")
    val hasReply = pendingKinds.contains("${reel.id}:${PendingActionEntity.KIND_REPLY_TEXT}")
    val hasAnyPending = hasHeart || hasLaugh || hasReply
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionBadge(reel.direction)
                Spacer(Modifier.width(8.dp))
                KindBadge(reel.kind)
                if (hasHeart) { Spacer(Modifier.width(8.dp)); PendingBadge(stringResource(R.string.feed_pending_badge_heart)) }
                if (hasLaugh) { Spacer(Modifier.width(8.dp)); PendingBadge(stringResource(R.string.feed_pending_badge_laugh)) }
                if (hasReply) { Spacer(Modifier.width(8.dp)); PendingBadge(stringResource(R.string.feed_pending_badge_reply)) }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = reel.reelAuthor?.let { "@$it" } ?: stringResource(R.string.feed_unknown_author),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            WhoAndWhereLine(reel)
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatEpoch(reel.discoveredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            if (!reel.reelUrl.isNullOrBlank()) {
                Button(onClick = onPlayInApp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_play_in_app))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onOpenInInstagram, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_open_in_ig_native))
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = reel.reelUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            } else {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_no_url_yet))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueButton(
                    text = stringResource(R.string.feed_queue_heart),
                    enabled = !hasHeart,
                    onClick = onQueueHeart,
                    modifier = Modifier.weight(1f),
                )
                QueueButton(
                    text = stringResource(R.string.feed_queue_laugh),
                    enabled = !hasLaugh,
                    onClick = onQueueLaugh,
                    modifier = Modifier.weight(1f),
                )
                QueueButton(
                    text = stringResource(R.string.feed_queue_reply),
                    enabled = !hasReply,
                    onClick = onQueueReply,
                    modifier = Modifier.weight(1f),
                )
            }
            if (hasAnyPending) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onCancelPending, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feed_cancel_pending))
                }
            }
        }
    }
}

@Composable
private fun QueueButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PendingBadge(label: String) {
    Surface(color = Color(0xFFFF9800), shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun WhoAndWhereLine(reel: ReelEntity) {
    val text: String = when (reel.direction) {
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
    val color = when (reel.direction) {
        "SENT" -> Color(0xFFCE93D8)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun DirectionBadge(direction: String) {
    val (label, bg) = when (direction) {
        "RECEIVED" -> "recebido" to Color(0xFF2E7D32)
        "SENT" -> "enviado" to Color(0xFF7B1FA2)
        else -> direction to Color.Gray
    }
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
private fun KindBadge(kind: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = kind,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
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

