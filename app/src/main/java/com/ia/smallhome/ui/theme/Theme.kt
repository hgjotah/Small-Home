package com.ia.smallhome.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SmallHomeColors = darkColorScheme(
    primary = Primary,
    onPrimary = Background,
    primaryContainer = Color(0xFF123B35),
    onPrimaryContainer = Primary,
    secondary = Secondary,
    tertiary = AiPurple,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceSecondary,
    onSurfaceVariant = TextSecondary,
    error = Error,
    onError = Background,
    outline = Color(0xFF2B3947),
)

@Composable
fun SmallHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmallHomeColors,
        typography = Typography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}
