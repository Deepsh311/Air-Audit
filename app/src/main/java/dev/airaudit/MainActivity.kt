package dev.airaudit

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import dev.airaudit.ui.AirAuditApp
import dev.airaudit.ui.theme.AirAuditTheme
import dev.airaudit.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            AirAuditTheme {
                Surface(color = Palette.Ink, contentColor = Palette.Paper) { AirAuditApp() }
            }
        }
    }
}
