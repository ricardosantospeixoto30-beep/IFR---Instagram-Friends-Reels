package com.example.friendsreels

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.friendsreels.service.InstagramReaderService

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        val prefs = getSharedPreferences(InstagramReaderService.PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        onEnableAccessibility = {
                            startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        onOpenInstagram = {
                            val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                            if (launch != null) {
                                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        onLongPressFirstReel = { sendServiceBroadcast(InstagramReaderService.ACTION_LONG_PRESS_FIRST_REEL) },
                        onDumpAllWindows = { sendServiceBroadcast(InstagramReaderService.ACTION_DUMP_ALL_WINDOWS) },
                        onListReels = { sendServiceBroadcast(InstagramReaderService.ACTION_LIST_REELS) },
                        onReactHeart = { sendServiceBroadcast(InstagramReaderService.ACTION_REACT_HEART) },
                        onReactLaugh = { sendServiceBroadcast(InstagramReaderService.ACTION_REACT_LAUGH) },
                        initialIgnoreSent = prefs.getBoolean(
                            InstagramReaderService.PREF_IGNORE_SENT,
                            InstagramReaderService.PREF_IGNORE_SENT_DEFAULT,
                        ),
                        onIgnoreSentChange = { enabled ->
                            prefs.edit()
                                .putBoolean(InstagramReaderService.PREF_IGNORE_SENT, enabled)
                                .apply()
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
    onEnableAccessibility: () -> Unit,
    onOpenInstagram: () -> Unit,
    onLongPressFirstReel: () -> Unit,
    onDumpAllWindows: () -> Unit,
    onListReels: () -> Unit,
    onReactHeart: () -> Unit,
    onReactLaugh: () -> Unit,
    initialIgnoreSent: Boolean,
    onIgnoreSentChange: (Boolean) -> Unit,
) {
    var ignoreSent by remember { mutableStateOf(initialIgnoreSent) }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(R.string.home_empty),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))

            Button(onClick = onEnableAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_enable_accessibility))
            }
            OutlinedButton(onClick = onOpenInstagram, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_open_instagram))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.poc_tools_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.poc_tools_help),
                style = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.toggle_ignore_sent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.toggle_ignore_sent_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = ignoreSent,
                    onCheckedChange = {
                        ignoreSent = it
                        onIgnoreSentChange(it)
                    },
                )
            }

            OutlinedButton(onClick = onListReels, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_list_reels))
            }
            OutlinedButton(onClick = onLongPressFirstReel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_long_press_reel))
            }
            OutlinedButton(onClick = onDumpAllWindows, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_dump_all_windows))
            }
            Button(onClick = onReactHeart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_react_heart))
            }
            Button(onClick = onReactLaugh, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_react_laugh))
            }
        }
    }
}

