package dev.airaudit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.airaudit.R
import dev.airaudit.core.Severity

/**
 * Deep "ink" blue instead of black, an ultraviolet accent for "you" and actions, and a red→green scale
 * that only ever means risk. Nothing else is allowed to use those risk colours.
 */
object Palette {
    val Ink = Color(0xFF0F1D2B)
    val Deck = Color(0xFF162838)
    val DeckHigh = Color(0xFF1E3447)
    val Rule = Color(0xFF29425A)
    val Paper = Color(0xFFE4ECF2)
    val Haze = Color(0xFF8CA2B5)
    val Ultraviolet = Color(0xFFA594FF)
    val Critical = Color(0xFFFF5964)
    val High = Color(0xFFFF9248)
    val Medium = Color(0xFFF2C94C)
    val Low = Color(0xFFB5D35A)
    val Secure = Color(0xFF4CD19A)
}

fun severityColor(severity: Severity?, fallback: Color = Palette.Secure): Color = when (severity) {
    Severity.CRITICAL -> Palette.Critical
    Severity.HIGH -> Palette.High
    Severity.MEDIUM -> Palette.Medium
    Severity.LOW -> Palette.Low
    Severity.INFO, null -> fallback
}

/** Barlow Semi Condensed: a road-sign-derived face, so long names and numbers fit on narrow rows. */
val Barlow = FontFamily(
    Font(R.font.barlow_semi_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
)

/** Monospace only for identifiers and hex, where character alignment actually helps. */
val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))
val MonoText = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, lineHeight = 19.sp)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Barlow,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = "tnum", // tabular figures, so changing dBm values don't jitter
)

private val AppTypography = Typography(
    displayLarge = style(56, 60, FontWeight.SemiBold),
    displayMedium = style(44, 48, FontWeight.SemiBold),
    displaySmall = style(36, 40, FontWeight.SemiBold),
    headlineLarge = style(30, 36, FontWeight.Medium),
    headlineMedium = style(26, 32, FontWeight.Medium),
    headlineSmall = style(23, 28, FontWeight.Medium),
    titleLarge = style(21, 26, FontWeight.SemiBold),
    titleMedium = style(17, 22, FontWeight.Medium),
    titleSmall = style(15, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = style(17, 24, FontWeight.Normal),
    bodyMedium = style(15, 21, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal, 0.1),
    labelLarge = style(15, 20, FontWeight.Medium, 0.2),
    labelMedium = style(13, 16, FontWeight.Medium, 0.2),
    labelSmall = style(12, 16, FontWeight.Medium, 0.3),
)

private val Scheme = darkColorScheme(
    primary = Palette.Ultraviolet,
    onPrimary = Palette.Ink,
    primaryContainer = Color(0xFF2E2A5C),
    onPrimaryContainer = Palette.Paper,
    secondary = Palette.Haze,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.DeckHigh,
    onSecondaryContainer = Palette.Paper,
    background = Palette.Ink,
    onBackground = Palette.Paper,
    surface = Palette.Ink,
    onSurface = Palette.Paper,
    surfaceVariant = Palette.Deck,
    onSurfaceVariant = Palette.Haze,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Deck,
    surfaceContainer = Palette.Deck,
    surfaceContainerHigh = Palette.DeckHigh,
    surfaceContainerHighest = Palette.DeckHigh,
    outline = Palette.Rule,
    outlineVariant = Palette.Rule,
    error = Palette.Critical,
    onError = Palette.Ink,
)

@Composable
fun AirAuditTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
