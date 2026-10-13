package com.thirdcoastsailing.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// One navy/orange scheme for all app UI, day and night.
// (Day/night only changes the chart colors: tan land by day, charcoal at night.)
private val TcsColors = darkColorScheme(
    primary = SignalOrange,
    onPrimary = Ink,
    primaryContainer = OrangeDim,
    onPrimaryContainer = Foam,
    secondary = Mist,
    onSecondary = Navy,
    background = Navy,
    onBackground = Foam,
    surface = Navy,
    onSurface = Foam,
    surfaceVariant = NavyLight,
    onSurfaceVariant = Mist,
    surfaceContainer = Navy,
    surfaceContainerHigh = NavyLight,
    surfaceContainerLow = NavyDeep,
    outline = NavyLine,
    error = Alarm,
)

@Composable
fun ThirdCoastSailingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TcsColors, typography = Typography, content = content)
}
