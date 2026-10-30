package com.pulsewave.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pulsewave.music.model.AccentPreset

val ObsidianBg = Color(0xFF090910)
val ObsidianSurface = Color(0xFF131320)
val ObsidianCard = Color(0xFF1B1B2D)
val ObsidianElevated = Color(0xFF25253C)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)

private val PulseTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = TextPrimary
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = TextPrimary
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = TextSecondary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary
    )
)

@Composable
fun PulseWaveTheme(
    accentPreset: AccentPreset,
    dynamicTrackColor: Color?,
    useDynamicCover: Boolean,
    content: @Composable () -> Unit
) {
    val targetPrimary = if (useDynamicCover && dynamicTrackColor != null) {
        dynamicTrackColor
    } else {
        Color(accentPreset.primaryHex)
    }
    val animatedPrimary by animateColorAsState(
        targetValue = targetPrimary,
        animationSpec = tween(durationMillis = 650),
        label = "primaryAccent"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = Color(accentPreset.secondaryHex),
        animationSpec = tween(durationMillis = 650),
        label = "secondaryAccent"
    )

    val scheme = darkColorScheme(
        primary = animatedPrimary,
        onPrimary = Color.White,
        secondary = animatedSecondary,
        onSecondary = Color.White,
        background = ObsidianBg,
        onBackground = TextPrimary,
        surface = ObsidianSurface,
        onSurface = TextPrimary,
        surfaceVariant = ObsidianCard,
        onSurfaceVariant = TextSecondary
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = PulseTypography,
        content = content
    )
}

fun formatDurationSec(seconds: Long): String {
    if (seconds <= 0L) return "LIVE"
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%d:%02d", mins, secs)
    }
}

fun formatMillis(ms: Long): String = formatDurationSec((ms.coerceAtLeast(0L)) / 1000L)
