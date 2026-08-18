package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dark-first palette. The important relationship is background vs surface: the
 * cards have to sit visibly *above* the page or the whole layout reads as one
 * flat sheet. A deep navy page with a lighter navy card gives that separation
 * without needing borders or heavy shadows.
 */
private val Emerald = Color(0xFF3DDC91)
private val EmeraldDim = Color(0xFF16A46B)
private val SkyBlue = Color(0xFF5AA9FF)
private val Rose = Color(0xFFFF6B6B)

private val DarkColours = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF04231A),
    primaryContainer = Color(0xFF12362A),
    onPrimaryContainer = Emerald,
    secondary = SkyBlue,
    onSecondary = Color(0xFF04203D),
    background = Color(0xFF0C1220),
    onBackground = Color(0xFFE7EDF6),
    surface = Color(0xFF162030),
    onSurface = Color(0xFFE7EDF6),
    surfaceVariant = Color(0xFF1E2A3D),
    onSurfaceVariant = Color(0xFFA9B7CA),
    outline = Color(0xFF2C3A4F),
    error = Rose,
)

private val LightColours = lightColorScheme(
    primary = EmeraldDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F5E7),
    onPrimaryContainer = Color(0xFF0A4A33),
    secondary = Color(0xFF2C7BE5),
    onSecondary = Color.White,
    background = Color(0xFFF2F5FA),
    onBackground = Color(0xFF121A26),
    surface = Color.White,
    onSurface = Color(0xFF121A26),
    surfaceVariant = Color(0xFFE7ECF4),
    onSurfaceVariant = Color(0xFF5A6779),
    outline = Color(0xFFD3DAE5),
    error = Color(0xFFD64545),
)

/** Generous radii — the single cheapest thing that stops a UI looking dated. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Only the sizes this app actually leans on are overridden. Hero numbers need
 * to dominate their tile; everything else stays out of the way.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge = base.headlineLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = base.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

@Composable
fun SubTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}

/** Muted body text — used constantly for captions and secondary lines. */
@Composable
fun mutedColour(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Palette for category accents, kept stable so a category keeps its colour. */
fun categoryColour(category: String): Color = when (category) {
    "entertainment" -> Color(0xFF3DDC91)
    "software" -> Color(0xFF5AA9FF)
    "utilities" -> Color(0xFFFFB454)
    "health" -> Color(0xFFFF6B9D)
    "finance" -> Color(0xFFB388FF)
    else -> Color(0xFF8FA3BC)
}
