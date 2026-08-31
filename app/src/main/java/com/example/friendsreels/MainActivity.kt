package com.example.friendsreels

import android.Manifest
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.friendsreels.data.AppDatabase
import com.example.friendsreels.service.InstagramReaderService
import com.example.friendsreels.ui.settings.SettingsActivity
import com.example.friendsreels.ui.theme.FriendsReelsTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Home screen focused on the vision (spec §3): the user should barely
 * spend time here — the primary CTA is "Abrir o meu feed". Everything
 * PoC-diagnostic (react/reply/copy URL from the current IG conversation)
 * lives behind the Settings screen so it doesn't compete with the
 * primary action.
 */
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        val missingUrlCountFlow: Flow<Int> = try {
            AppDatabase.get(this).reelDao().observeMissingUrlCount()
        } catch (e: Exception) {
            flowOf(0)
        }
        setContent {
            FriendsReelsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val missingUrlCount by missingUrlCountFlow.collectAsState(initial = 0)
                    HomeScreen(
                        missingUrlCount = missingUrlCount,
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
                        onDiscoverReels = {
                            sendServiceBroadcast(InstagramReaderService.ACTION_DISCOVER_REELS)
                        },
                        onDiscoverReelsHistory = {
                            sendServiceBroadcast(InstagramReaderService.ACTION_DISCOVER_REELS_HISTORY)
                        },
                        onPrepareUrlsBatch = {
                            sendServiceBroadcast(InstagramReaderService.ACTION_ENRICH_ALL_MISSING_URLS)
                        },
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
    missingUrlCount: Int,
    onOpenFeed: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onDiscoverReels: () -> Unit,
    onDiscoverReelsHistory: () -> Unit,
    onPrepareUrlsBatch: () -> Unit,
    onOpenInstagram: () -> Unit,
    onOpenSettings: () -> Unit,
) {
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

            Text(
                text = stringResource(R.string.home_discovery_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.home_discovery_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onDiscoverReels, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_discover_reels))
            }
            OutlinedButton(onClick = onDiscoverReelsHistory, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_discover_reels_history))
            }
            if (missingUrlCount > 0) {
                OutlinedButton(onClick = onPrepareUrlsBatch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_prepare_urls_batch, missingUrlCount))
                }
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
}

