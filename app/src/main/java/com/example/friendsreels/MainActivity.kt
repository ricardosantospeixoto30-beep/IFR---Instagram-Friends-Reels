package com.example.friendsreels

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.friendsreels.service.InstagramReaderService
import com.example.friendsreels.ui.settings.SettingsActivity
import com.example.friendsreels.ui.theme.FriendsReelsTheme

/**
 * Home screen focused on the vision (spec §3): the primary CTA is "Abrir o
 * meu feed". The discovery/prepare + data-management actions the user repeats
 * while testing now live here too (s57) so they don't have to dig into
 * Settings every time; the full/advanced controls still live in Settings.
 */
class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        setContent {
            FriendsReelsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        vm = vm,
                        onOpenFeed = {
                            startActivity(
                                Intent(this, com.example.friendsreels.ui.feed.FeedActivity::class.java)
                            )
                        },
                        onEnableAccessibility = {
                            startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        onBroadcast = { action -> sendServiceBroadcast(action) },
                        onOpenInstagram = {
                            val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                            if (launch != null) {
                                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        onOpenSettings = {
                            startActivity(Intent(this, SettingsActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    private fun sendServiceBroadcast(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun HomeScreen(
    vm: HomeViewModel,
    onOpenFeed: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onBroadcast: (String) -> Unit,
    onOpenInstagram: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val missingUrlCount by vm.missingUrlCount.collectAsState()
    val totalReelCount by vm.totalReelCount.collectAsState()
    val conversations by vm.conversations.collectAsState()
    var scanToEnd by remember { mutableStateOf(vm.isScanToEnd()) }
    var showForget by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Button(onClick = onOpenFeed, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_primary_open_feed))
            }
            Text(
                text = stringResource(R.string.home_primary_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // --- Single-pass discover + prepare (s57) ---
            Text(
                text = stringResource(R.string.home_singlepass_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_singlepass_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    onBroadcast(InstagramReaderService.ACTION_DISCOVER_PREPARE_ALL)
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_singlepass_start_toast),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_singlepass_start))
            }
            OutlinedButton(
                onClick = {
                    onBroadcast(InstagramReaderService.ACTION_DISCOVER_PREPARE_CURRENT)
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_singlepass_current_toast),
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_singlepass_current_start))
            }
            TextButton(
                onClick = {
                    onBroadcast(InstagramReaderService.ACTION_DISCOVER_PREPARE_CANCEL)
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_singlepass_cancel_toast),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_singlepass_cancel))
            }
            HomeToggle(
                title = stringResource(R.string.settings_scan_to_end_title),
                subtitle = stringResource(R.string.settings_scan_to_end_subtitle),
                value = scanToEnd,
                onChange = {
                    scanToEnd = it
                    vm.setScanToEnd(it)
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // --- Quick discovery (legacy fast paths) ---
            Text(
                text = stringResource(R.string.home_discovery_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.home_discovery_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { onBroadcast(InstagramReaderService.ACTION_DISCOVER_REELS) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_discover_reels))
            }
            OutlinedButton(
                onClick = { onBroadcast(InstagramReaderService.ACTION_DISCOVER_REELS_HISTORY) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_discover_reels_history))
            }
            if (missingUrlCount > 0) {
                OutlinedButton(
                    onClick = { onBroadcast(InstagramReaderService.ACTION_ENRICH_ALL_MISSING_URLS) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_prepare_urls_batch, missingUrlCount))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // --- Data management (s57) ---
            Text(
                text = stringResource(R.string.home_manage_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.home_manage_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { showForget = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_forget_conversations_button))
            }
            OutlinedButton(
                onClick = { showResetConfirm = true },
                enabled = totalReelCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_reset_button))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.home_setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = onEnableAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_enable_accessibility))
            }
            OutlinedButton(onClick = onOpenInstagram, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_open_instagram))
            }
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_open_settings))
            }
        }
    }

    if (showForget) {
        ForgetConversationsDialog(
            conversations = conversations,
            onDismiss = { showForget = false },
            onForget = { targets ->
                vm.forgetConversations(targets)
                showForget = false
                Toast.makeText(context, context.getString(R.string.forget_done_toast), Toast.LENGTH_SHORT).show()
            },
            onForgetAll = {
                vm.forgetAllConversations()
                showForget = false
                Toast.makeText(context, context.getString(R.string.forget_done_toast), Toast.LENGTH_SHORT).show()
            },
            onNothingSelected = {
                Toast.makeText(context, context.getString(R.string.forget_nothing_toast), Toast.LENGTH_SHORT).show()
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_message, totalReelCount)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllReels()
                    showResetConfirm = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_reset_done_toast),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.settings_reset_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.settings_reset_confirm_no))
                }
            },
        )
    }
}

@Composable
private fun HomeToggle(
    title: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Forget-conversations picker: all / only-selected / all-except-selected. */
@Composable
private fun ForgetConversationsDialog(
    conversations: List<String>,
    onDismiss: () -> Unit,
    onForget: (List<String>) -> Unit,
    onForgetAll: () -> Unit,
    onNothingSelected: () -> Unit,
) {
    // Modes: "ALL", "ONLY", "EXCEPT".
    var mode by remember { mutableStateOf("ALL") }
    val selected = remember { mutableStateListOf<String>() }

    val targets: List<String> = when (mode) {
        "ONLY" -> selected.toList()
        "EXCEPT" -> conversations.filter { it !in selected }
        else -> conversations
    }
    val summary = when (mode) {
        "ALL" -> stringResource(R.string.forget_summary_all, conversations.size)
        else -> stringResource(R.string.forget_summary_count, targets.size)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forget_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.forget_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                ForgetModeRow("ALL", stringResource(R.string.forget_mode_all), mode) { mode = it }
                ForgetModeRow("ONLY", stringResource(R.string.forget_mode_only), mode) { mode = it }
                ForgetModeRow("EXCEPT", stringResource(R.string.forget_mode_except), mode) { mode = it }

                if (mode != "ALL") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (conversations.isEmpty()) {
                        Text(
                            text = stringResource(R.string.forget_no_conversations),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = {
                                selected.clear()
                                selected.addAll(conversations)
                            }) { Text(stringResource(R.string.forget_select_all)) }
                            TextButton(onClick = { selected.clear() }) {
                                Text(stringResource(R.string.forget_clear_selection))
                            }
                        }
                        conversations.forEach { title ->
                            val checked = title in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        onValueChange = { on ->
                                            if (on) { if (title !in selected) selected.add(title) }
                                            else selected.remove(title)
                                        },
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }

                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (mode) {
                    "ALL" -> if (conversations.isEmpty()) onNothingSelected() else onForgetAll()
                    else -> if (targets.isEmpty()) onNothingSelected() else onForget(targets)
                }
            }) { Text(stringResource(R.string.forget_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.forget_cancel)) }
        },
    )
}

@Composable
private fun ForgetModeRow(
    value: String,
    label: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = current == value, onValueChange = { onSelect(value) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == value, onClick = { onSelect(value) })
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
