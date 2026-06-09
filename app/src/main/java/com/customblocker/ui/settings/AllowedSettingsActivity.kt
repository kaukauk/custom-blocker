package com.customblocker.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.customblocker.service.accessibility.SettingsAccessGate
import com.customblocker.service.accessibility.SettingsGuard
import com.customblocker.ui.theme.CustomBlockerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The "Allowed Settings" launcher shown when the user opens the device Settings app while Strict
 * Mode L3 is locked. Rather than fighting the real Settings app, it offers a curated set of safe
 * shortcuts; tapping one opens a [SettingsAccessGate] permit and deep-links into that real Settings
 * screen, so the accessibility service lets the user stay there instead of ejecting them.
 */
@AndroidEntryPoint
class AllowedSettingsActivity : ComponentActivity() {

    @Inject lateinit var gate: SettingsAccessGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            CustomBlockerTheme {
                AllowedSettingsScreen(onOpen = ::openShortcut)
            }
        }
    }

    private fun openShortcut(shortcut: SettingsGuard.AllowedShortcut) {
        // Permit first so the screen is already allowed by the time Settings comes to the foreground.
        gate.permit(System.currentTimeMillis())
        val intent = Intent(shortcut.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            gate.clear()
            Toast.makeText(this, "Couldn't open ${shortcut.label}", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllowedSettingsScreen(onOpen: (SettingsGuard.AllowedShortcut) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Allowed settings") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Strict Mode is locked, so the rest of Settings is blocked. These screens are " +
                        "still available:",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(SettingsGuard.AllowedShortcut.entries) { shortcut ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(shortcut) },
                ) {
                    Text(
                        shortcut.label,
                        Modifier.fillMaxWidth().padding(20.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
