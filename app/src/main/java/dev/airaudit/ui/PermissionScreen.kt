package dev.airaudit.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.airaudit.core.Permissions
import dev.airaudit.ui.theme.Palette

@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.hasEssential(context)) onGranted() else denied = true
    }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("AirAudit", style = MaterialTheme.typography.displaySmall, color = Palette.Paper)
        Text(
            "A passive scanner for the Wi-Fi, Bluetooth and cell signals around you.",
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.Haze,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(32.dp))
        PermissionLine(
            "Precise location",
            "Android only shows nearby Wi-Fi networks, Bluetooth devices and cell towers to apps with precise location. AirAudit never records where you are.",
        )
        PermissionLine("Nearby devices", "Lets AirAudit hear Bluetooth advertisements and measure Wi-Fi distances.")
        Spacer(Modifier.height(16.dp))
        Text(
            "AirAudit only listens. It never connects to, logs in to or disrupts the networks and devices it finds.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Haze,
        )
        Button(onClick = { launcher.launch(Permissions.all()) }, modifier = Modifier.padding(top = 24.dp)) {
            Text("Allow access")
        }
        if (denied) {
            Text(
                "Precise location wasn't granted. Choose \"Precise\" when Android asks, or allow it in the app's settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Medium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Open app settings") }
        }
    }
}

@Composable
private fun PermissionLine(title: String, body: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 8.dp)) {
        SeverityRule(Palette.Ultraviolet)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Paper)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Haze, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
