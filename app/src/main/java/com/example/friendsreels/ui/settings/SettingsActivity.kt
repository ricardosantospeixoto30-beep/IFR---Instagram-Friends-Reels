package com.example.friendsreels.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.friendsreels.R
import com.example.friendsreels.service.InstagramReaderService

/**
 * Settings screen (spec §11). Currently exposes:
 *
 * - Ignore Reels I sent myself (`PREF_IGNORE_SENT`).
 * - Conversation selection (spec §8) with 3 modes: Ver tudo /
 *   Apenas selecionadas / Excluir selecionadas — with the discovered
 *   thread list rendered as a checkbox list with Reel counts.
 * - Ferramentas de diagnóstico — direct broadcast buttons that used to
 *   clutter the Home screen (PoC-tools; may go away in v1).
 *
 * The "Inverter direção do swipe" toggle from s35 stays out until we
 * have a visual indicator of direction in the feed (see PROJECT_
 * PROGRESS §6.2). Preference key is still maintained in the service.
 */
class SettingsActivity : ComponentActivity() {

    private val vm: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(InstagramReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        vm = vm,
                        initialIgnoreSent = prefs.getBoolean(
                            InstagramReaderService.PREF_IGNORE_SENT,
                            InstagramReaderService.PREF_IGNORE_SENT_DEFAULT,
                        ),
                        onIgnoreSentChange = { v ->
                            prefs.edit().putBoolean(InstagramReaderService.PREF_IGNORE_SENT, v).apply()
                        },
                        onFinish = { finish() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    vm: SettingsViewModel,
    initialIgnoreSent: Boolean,
    onIgnoreSentChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    var ignoreSent by remember { mutableStateOf(initialIgnoreSent) }
    val context = LocalContext.current

    val selectionMode by vm.selectionMode.collectAsState()
    val trackedTitles by vm.trackedTitles.collectAsState()
    val threadCounts by vm.threadCounts.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            SettingToggle(
                title = stringResource(R.string.settings_ignore_sent_title),
                subtitle = stringResource(R.string.settings_ignore_sent_subtitle),
                value = ignoreSent,
                onChange = {
                    ignoreSent = it
                    onIgnoreSentChange(it)
                },
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_selection_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_selection_subtitle),
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionModeRow(
                current = selectionMode,
                onChange = { vm.setSelectionMode(it) },
            )

            if (selectionMode != InstagramReaderService.SELECTION_MODE_NONE) {
                if (threadCounts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_selection_no_threads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                } else {
                    Text(
                        text = when (selectionMode) {
                            InstagramReaderService.SELECTION_MODE_INCLUDE_ONLY ->
                                stringResource(R.string.settings_selection_include_hint)
                            else -> stringResource(R.string.settings_selection_exclude_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    threadCounts.forEach { tc ->
                        ThreadSelectionRow(
                            title = tc.threadTitle,
                            reelCount = tc.reelCount,
                            checked = tc.threadTitle in trackedTitles,
                            onCheckedChange = { checked ->
                                vm.setTrackedThread(tc.threadTitle, checked)
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            BatchEnrichmentSection(vm = vm)

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_diagnostics_body),
                style = MaterialTheme.typography.bodySmall,
            )
            DiagnosticActionButton(
                labelRes = R.string.btn_discover_reels,
                onClick = { sendServiceBroadcast(context, InstagramReaderService.ACTION_DISCOVER_REELS) },
            )
            DiagnosticActionButton(
                labelRes = R.string.btn_discover_reels_history,
                onClick = { sendServiceBroadcast(context, InstagramReaderService.ACTION_DISCOVER_REELS_HISTORY) },
            )
            DiagnosticActionButton(
                labelRes = R.string.btn_copy_reel_url,
                onClick = { sendServiceBroadcast(context, InstagramReaderService.ACTION_COPY_REEL_URL) },
            )
            DiagnosticActionButton(
                labelRes = R.string.btn_react_heart,
                onClick = { sendServiceBroadcast(context, InstagramReaderService.ACTION_REACT_HEART) },
            )
            DiagnosticActionButton(
                labelRes = R.string.btn_react_laugh,
                onClick = { sendServiceBroadcast(context, InstagramReaderService.ACTION_REACT_LAUGH) },
            )
            DiagnosticActionButton(
                labelRes = R.string.btn_reply_reel,
                onClick = { sendServiceBroadcast(context, InstagramReaderService.ACTION_REPLY_FIRST_REEL_MOCK) },
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_done))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingToggle(
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
        Column(modifier = Modifier.padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Box(contentAlignment = Alignment.Center) {
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SelectionModeRow(
    current: String,
    onChange: (String) -> Unit,
) {
    val options = listOf(
        InstagramReaderService.SELECTION_MODE_NONE to R.string.settings_selection_mode_none,
        InstagramReaderService.SELECTION_MODE_INCLUDE_ONLY to R.string.settings_selection_mode_include,
        InstagramReaderService.SELECTION_MODE_EXCLUDE_SELECTED to R.string.settings_selection_mode_exclude,
    )
    Column {
        options.forEach { (value, labelRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChange(value) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == value,
                    onClick = { onChange(value) },
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ThreadSelectionRow(
    title: String,
    reelCount: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.padding(start = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_selection_reel_count, reelCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticActionButton(labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes))
    }
}

/**
 * Section that shows how many Reels still miss a URL and offers a
 * single-tap "prepare all" button. While a batch is in flight the
 * button flips to a `LinearProgressIndicator` + Cancel button. The
 * last outcome (X preparados, Y falharam) is shown after completion.
 */
@Composable
private fun BatchEnrichmentSection(vm: SettingsViewModel) {
    val context = LocalContext.current
    val missingCount by vm.missingUrlCount.collectAsState()
    val batchState by vm.batchEnrichmentState.collectAsState()

    Text(
        text = stringResource(R.string.settings_batch_enrich_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = stringResource(R.string.settings_batch_enrich_subtitle),
        style = MaterialTheme.typography.bodySmall,
    )

    if (batchState.running) {
        val currentIndex = batchState.currentIndex.coerceAtLeast(1)
        val total = batchState.total.coerceAtLeast(currentIndex)
        Text(
            text = stringResource(
                R.string.settings_batch_enrich_running, currentIndex, total,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { currentIndex.toFloat() / total.toFloat().coerceAtLeast(1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                vm.cancelBatchEnrichment()
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_batch_enrich_cancel_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_batch_enrich_cancel))
        }
    } else {
        if (missingCount == 0) {
            Text(
                text = stringResource(R.string.settings_batch_enrich_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            Text(
                text = stringResource(R.string.settings_batch_enrich_pending, missingCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = {
                vm.startBatchEnrichment()
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_batch_enrich_start_toast),
                    Toast.LENGTH_LONG,
                ).show()
            },
            enabled = missingCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_batch_enrich_start))
        }
        batchState.lastResult?.let { r ->
            val labelRes = if (r.cancelled)
                R.string.settings_batch_enrich_last_result_cancelled
            else
                R.string.settings_batch_enrich_last_result
            Text(
                text = stringResource(labelRes, r.succeeded, r.failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

private fun sendServiceBroadcast(context: Context, action: String) {
    context.sendBroadcast(Intent(action).setPackage(context.packageName))
}
