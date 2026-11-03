package com.nanihadesuka.irisx.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic status colors, used only to say what the connection is doing. */
object StatusColors {
    val Connected = Color(0xFF5AD19A)
    val Busy = Color(0xFF6FB1FF)
    val Away = Color(0xFF85858F)
    val Attention = Color(0xFFFFB347)
}

/** Dark only and never dynamic: photos always sit on the same neutral background. */
private val Dark = darkColorScheme(
    primary = Color(0xFFF2B66D),
    onPrimary = Color(0xFF2B1A05),
    primaryContainer = Color(0xFF4A3214),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFF8FC7C1),
    onSecondary = Color(0xFF06302C),
    secondaryContainer = Color(0xFF2A2A30),
    onSecondaryContainer = Color(0xFFF2B66D),
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF0B0B0D),
    onSurface = Color(0xFFEDEDF0),
    onSurfaceVariant = Color(0xFFA3A3AD),
    surfaceContainerLow = Color(0xFF121215),
    surfaceContainer = Color(0xFF17171B),
    surfaceContainerHigh = Color(0xFF212126),
    surfaceContainerHighest = Color(0xFF2A2A30),
    outline = Color(0xFF55555E),
    outlineVariant = Color(0xFF2E2E34),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun IrisXTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = AppTypography, shapes = AppShapes, content = content)
}
