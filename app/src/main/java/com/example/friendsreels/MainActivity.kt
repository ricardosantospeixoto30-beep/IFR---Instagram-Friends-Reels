package com.example.friendsreels

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.friendsreels.service.InstagramReaderService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        onReactHeart = { sendServiceBroadcast(InstagramReaderService.ACTION_REACT_HEART) },
                        onReactLaugh = { sendServiceBroadcast(InstagramReaderService.ACTION_REACT_LAUGH) },
                    )
                }
            }
        }
    }

    private fun sendServiceBroadcast(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }
}

@Composable
private fun HomeScreen(
    onEnableAccessibility: () -> Unit,
    onOpenInstagram: () -> Unit,
    onLongPressFirstReel: () -> Unit,
    onDumpAllWindows: () -> Unit,
    onReactHeart: () -> Unit,
    onReactLaugh: () -> Unit,
) {
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
