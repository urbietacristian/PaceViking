package com.example.paceviking.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The app is always night mode: the workout screen is designed around dark
// phase backgrounds, and following the system theme rendered its clock with
// near-invisible contrast when the system was light.
private val NightColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun PaceVikingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColorScheme,
        typography = Typography,
        content = content
    )
}
