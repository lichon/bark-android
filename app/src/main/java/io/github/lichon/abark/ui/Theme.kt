package io.github.lichon.abark.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBFB),
    onPrimary = Color(0xFF00344F),
    secondary = Color(0xFFB9C8DA),
    background = Color(0xFF0E1216),
    surface = Color(0xFF151A1F),
    surfaceVariant = Color(0xFF1E252C),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    secondary = Color(0xFF50606F),
)

@Composable
fun AbarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
