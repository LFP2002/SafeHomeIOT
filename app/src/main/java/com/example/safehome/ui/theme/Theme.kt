package com.example.safehome.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = SafeHomeBlue,
    onPrimary = Color.White,
    secondary = SafeHomeCoral,
    onSecondary = SafeHomeInk,
    background = SafeHomeCanvas,
    onBackground = SafeHomeInk,
    surface = SafeHomeSurface,
    onSurface = SafeHomeInk,
    outline = SafeHomeOutline,
    error = SafeHomeCritical
)

private val SafeHomeShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(40.dp)
)

@Composable
fun SafeHomeTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = SafeHomeShapes,
        content = content
    )
}
