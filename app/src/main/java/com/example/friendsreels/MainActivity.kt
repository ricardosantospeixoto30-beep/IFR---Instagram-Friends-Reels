package com.example.friendsreels

import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainScreen() }
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled(ctx)) }

    Scaffold(
        topBar = { SmallTopAppBar(title = { Text("Friends Reels Inbox – PoC") }) },
        content = { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        ctx.sendBroadcast(Intent(InstagramAutomationService.ACTION_START_SYNC))
                        Toast.makeText(ctx, "Sync started – check Logcat", Toast.LENGTH_SHORT).show()
                    },
                    enabled = serviceEnabled
                ) {
                    Text(stringResource(R.string.btn_sync))
                }
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                ) {
                    Text(stringResource(R.string.btn_enable_service))
                }
                Text(text = if (serviceEnabled) stringResource(R.string.msg_service_enabled) else stringResource(R.string.msg_service_disabled))
            }
        }
    )
}

private fun isServiceEnabled(context: android.content.Context): Boolean {
    val am = android.view.accessibility.AccessibilityManager.getInstance(context)
    val enabled = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabled.any { it.serviceInfo?.packageName == context.packageName }
}
