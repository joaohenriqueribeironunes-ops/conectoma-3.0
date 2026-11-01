package app.conectoma.v3.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val GlassWhite = Color(0xCCF7F8FC)
val GlassDark = Color(0xCC1C1C22)
val Specular = Color(0x66FFFFFF)
val Edge = Color(0x33000000)
val Dopamine = Color(0xFFFF3B7A)
val Nerve = Color(0xFF5AC8FA)
val Ivory = Color(0xFFF2F2F7)
val Ink = Color(0xFF1C1C1E)
val Label = Color(0xFF8E8E93)

private val Light = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    background = Ivory,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    secondary = Nerve
)

private val Dark = darkColorScheme(
    primary = Color(0xFF64D2FF),
    onPrimary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF2F2F7),
    secondary = Dopamine
)

private val Type = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp)
)

@Composable
fun ConectomaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Type,
        content = content
    )
}

@Composable
fun LiquidWallpaper(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "lg")
    val shift by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse),
        label = "shift"
    )
    val dark = isSystemInDarkTheme()
    val c1 = if (dark) Color(0xFF0B1020) else Color(0xFFE8EEF8)
    val c2 = if (dark) Color(0xFF1A1030) else Color(0xFFD9E7FF)
    val c3 = if (dark) Color(0xFF241018) else Color(0xFFF3E4EE)
    Box(
        modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(c1, c2, c3),
                start = Offset(0f, 400f * shift),
                end = Offset(1200f, 1800f - 400f * shift),
                tileMode = TileMode.Clamp
            )
        )
    )
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (dark) GlassDark else GlassWhite)
            .border(0.6.dp, if (dark) Color(0x44FFFFFF) else Edge, shape)
            .border(1.2.dp, Specular.copy(alpha = 0.35f), shape)
    ) {
        content()
    }
}

fun Modifier.liquidBlur(): Modifier = this.blur(28.dp)
