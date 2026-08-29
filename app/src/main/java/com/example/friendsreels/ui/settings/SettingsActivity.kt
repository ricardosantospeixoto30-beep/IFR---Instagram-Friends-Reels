package com.example.friendsreels.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.friendsreels.R
import com.example.friendsreels.service.InstagramReaderService

/**
 * Settings screen (spec §11). Currently exposes:
 *
 * - Ignorar Reels enviados por mim (`PREF_IGNORE_SENT`).
 * - Inverter direção do swipe no feed (`PREF_INVERT_SWIPE`).
 * - Placeholder para "seleção de conversas" (spec §8) — próxima iteração.
 *
 * Also provides shortcut buttons for the diagnostic actions that used to
 * clutter the Home screen: 🔍 Descobrir, 📥 Descobrir histórico, 🔗 Copiar
 * URL do 1.º Reel, ❤ / 😂 / 👀 no 1.º Reel visível. These are POC-tools
 * and probably going away in v1; kept behind the settings screen so the
 * Home stays focused on the vision.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(InstagramReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        initialIgnoreSent = prefs.getBoolean(
                            InstagramReaderService.PREF_IGNORE_SENT,
                            InstagramReaderService.PREF_IGNORE_SENT_DEFAULT,
                        ),
                        onIgnoreSentChange = { v ->
                            prefs.edit().putBoolean(InstagramReaderService.PREF_IGNORE_SENT, v).apply()
                        },
                        initialInvertSwipe = prefs.getBoolean(
                            InstagramReaderService.PREF_INVERT_SWIPE,
                            InstagramReaderService.PREF_INVERT_SWIPE_DEFAULT,
                        ),
                        onInvertSwipeChange = { v ->
                            prefs.edit().putBoolean(InstagramReaderService.PREF_INVERT_SWIPE, v).apply()
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
    initialIgnoreSent: Boolean,
    onIgnoreSentChange: (Boolean) -> Unit,
    initialInvertSwipe: Boolean,
    onInvertSwipeChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
) {
    var ignoreSent by remember { mutableStateOf(initialIgnoreSent) }
    var invertSwipe by remember { mutableStateOf(initialInvertSwipe) }
    val context = LocalContext.current

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
            SettingToggle(
                title = stringResource(R.string.settings_invert_swipe_title),
                subtitle = stringResource(R.string.settings_invert_swipe_subtitle),
                value = invertSwipe,
                onChange = {
                    invertSwipe = it
                    onInvertSwipeChange(it)
                },
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_placeholder_selection_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_placeholder_selection_body),
                style = MaterialTheme.typography.bodySmall,
            )

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
private fun DiagnosticActionButton(labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes))
    }
}

private fun sendServiceBroadcast(context: Context, action: String) {
    context.sendBroadcast(Intent(action).setPackage(context.packageName))
}
