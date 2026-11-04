package com.conectoma.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paleta principal: Verde Grafite (base esverdeada de grafite) + Aurora
 * (acentos verde-teal em degradê). [amber] e [rose] são reservados
 * exclusivamente para sinalização e alertas.
 */
object Ink {
    // ---- Verde Grafite: fundo e superfícies
    val void = Color(0xFF050A08)
    val deep = Color(0xFF0A1210)
    // ---- Aurora: acentos de trabalho (nunca amarelo)
    val indigo = Color(0xFF2FBF8F)
    val indigoDeep = Color(0xFF177A5A)
    val violet = Color(0xFF6FE0C2)
    val cyan = Color(0xFF5FE3D6)
    val aqua = Color(0xFF3FC8C0)
    // ---- sinalização e alertas
    val amber = Color(0xFFF5C24B)
    val rose = Color(0xFFFF7A9A)
    // ---- texto e vidro
    val paper = Color(0xFFF0F7F3)
    val mist = Color(0xFFA3B8AE)
    val dim = Color(0xFF6F857B)
    val glassTop = Color(0x1FFFFFFF)
    val glassBottom = Color(0x0AFFFFFF)
    val stroke = Color(0x26FFFFFF)
    val strokeStrong = Color(0x40FFFFFF)
}

/**
 * Escala tipográfica no padrão OneUI 7: títulos grandes e em negrito,
 * hierarquia clara (título → seção → corpo → apoio), corpo com entrelinha
 * confortável e legendas calmas — o texto é parte do design, não enfeite.
 */
object Type {
    private val sans = FontFamily.SansSerif
    val largeTitle = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 32.sp,
        letterSpacing = (-0.8).sp, color = Ink.paper,
    )
    val title = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Bold, fontSize = 21.sp,
        letterSpacing = (-0.4).sp, color = Ink.paper,
    )
    val headline = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        letterSpacing = (-0.2).sp, color = Ink.paper,
    )
    val body = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.5.sp,
        letterSpacing = 0.sp, color = Ink.paper, lineHeight = 22.sp,
    )
    val callout = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.5.sp,
        letterSpacing = 0.1.sp, color = Ink.mist, lineHeight = 19.sp,
    )
    val caption = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
        letterSpacing = 0.2.sp, color = Ink.dim,
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp,
        letterSpacing = 0.2.sp, color = Ink.aqua,
    )
}

/** Translucent pane with a specular top edge — the "liquid glass" base. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Int = 30,
    tint: Color = Color.Transparent,
    glow: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner.dp)
    Box(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        if (tint == Color.Transparent) Ink.glassTop else tint.copy(alpha = 0.22f),
                        if (tint == Color.Transparent) Ink.glassBottom else tint.copy(alpha = 0.07f),
                    )
                )
            )
            .border(0.9.dp, Brush.linearGradient(listOf(Ink.strokeStrong, Ink.stroke, Color(0x0AFFFFFF))), shape)
    ) {
        // top specular highlight
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0x4DFFFFFF), Color.Transparent)
                ),
                start = Offset(size.width * 0.12f, 1f),
                end = Offset(size.width * 0.88f, 1f),
                strokeWidth = 1.2f,
            )
        }
        content()
    }
}

/** Pill OneUI: cantos totalmente arredondados, sem borda dura. */
@Composable
fun GlassButton(
    modifier: Modifier = Modifier,
    accent: Color = Ink.indigo,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (enabled) Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.55f)))
                else Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x11FFFFFF)))
            )
            .border(0.9.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x14FFFFFF))), shape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        content = content,
    )
}

/** Slow drifting aurora behind everything. */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Ink.void, Ink.deep, Color(0xFF07100D))))
        val w = size.width
        val h = size.height
        fun blob(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        blob(w * (0.28f + 0.06f * kotlin.math.sin(phase)), h * 0.18f, w * 0.95f, Ink.indigo, 0.30f)
        blob(w * (0.82f + 0.05f * kotlin.math.cos(phase * 0.8f)), h * 0.34f, w * 0.7f, Ink.violet, 0.16f)
        blob(w * 0.5f, h * (0.92f + 0.03f * kotlin.math.sin(phase * 0.6f)), w * 0.85f, Ink.indigoDeep, 0.26f)
        blob(w * 0.15f, h * 0.72f, w * 0.4f, Ink.aqua, 0.05f)
    }
}

/** subtle 1px ring used around live canvases */
fun Modifier.ringBorder(color: Color = Ink.stroke, corner: Int = 40): Modifier =
    this.border(1.dp, color, RoundedCornerShape(corner.dp))
