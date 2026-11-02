package com.conectoma.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.conectoma.app.ui.AuroraBackdrop
import com.conectoma.app.ui.BrainScreen
import com.conectoma.app.ui.ChatScreen
import com.conectoma.app.ui.ConectomaViewModel
import com.conectoma.app.ui.Ink
import com.conectoma.app.ui.LearnScreen
import com.conectoma.app.ui.Type
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {

    private var pendingUrl: String? = null
    private var pendingText: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        readIntent(intent)
        setContent {
            val vm: ConectomaViewModel = viewModel()
            val state by vm.state.collectAsState()
            AppRoot(state, vm, ::requestNotificationPermission)
            LaunchedEffect(Unit) {
                val url = pendingUrl
                if (url != null) {
                    pendingUrl = null
                    vm.learnUrl(url)
                }
                val text = pendingText
                if (text != null) {
                    pendingText = null
                    vm.send(text)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    if (text.startsWith("http")) pendingUrl = text.trim() else pendingText = text.trim()
                }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.dataString ?: return
                if (data.startsWith("conectoma://learn?u=")) {
                    pendingUrl = java.net.URLDecoder.decode(data.substringAfter("u="), "UTF-8")
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Tab(val label: String) {
    CONECTOMA("assistente"), APRENDER("aprender"), LEXICO("cérebro")
}

@Composable
private fun AppRoot(state: com.conectoma.app.ui.UiState, vm: ConectomaViewModel, askNotif: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    var lastPulse by remember { mutableIntStateOf(state.pulse) }
    LaunchedEffect(state.pulse) {
        if (state.pulse > lastPulse) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
        lastPulse = state.pulse
    }
    LaunchedEffect(state.autonomyEnabled) {
        if (state.autonomyEnabled) askNotif()
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop()
        val contentPadding = PaddingValues(top = 54.dp)
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (slideInHorizontally(tween(320)) { it / 14 } + fadeIn(tween(320))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 18 } + fadeOut(tween(180)))
                },
                label = "tabs",
            ) { current ->
                when (current) {
                    0 -> ChatScreen(state, vm, contentPadding)
                    1 -> LearnScreen(state, vm, contentPadding)
                    else -> BrainScreen(state, vm, contentPadding)
                }
            }
        }
        if (!state.ready) {
            LoadingOverlay(state.stage, state.progress, state.error, onRetry = { vm.load() })
        }
        GlassTabBar(
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun GlassTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tabs = Tab.values()
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(66.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))))
            .border(0.9.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x10FFFFFF))), RoundedCornerShape(30.dp))
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, t ->
                val active = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabIcon(index, active)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        t.label,
                        style = Type.caption.copy(
                            fontSize = 9.sp,
                            color = if (active) Ink.cyan else Ink.dim,
                            letterSpacing = 0.1.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun TabIcon(index: Int, active: Boolean) {
    val color = if (active) Ink.cyan else Ink.mist.copy(alpha = 0.62f)
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = if (active) 2.1f else 1.7f, cap = StrokeCap.Round)
        when (index) {
            0 -> {
                val pts = listOf(
                    Offset(w * 0.5f, h * 0.16f), Offset(w * 0.84f, h * 0.38f),
                    Offset(w * 0.72f, h * 0.82f), Offset(w * 0.28f, h * 0.82f),
                    Offset(w * 0.16f, h * 0.38f),
                )
                for (i in pts.indices) drawLine(color, pts[i], pts[(i + 1) % pts.size], 1.2f)
                pts.forEach { drawCircle(if (active) Ink.cyan else color, 1.9f, it) }
            }
            1 -> {
                drawLine(color, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.62f), 2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.46f), Offset(w * 0.5f, h * 0.64f), 2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.68f, h * 0.46f), Offset(w * 0.5f, h * 0.64f), 2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.8f), 2f, StrokeCap.Round)
            }
            2 -> {
                drawCircle(color, w * 0.36f, Offset(w * 0.5f, h * 0.5f), style = Stroke(1.6f))
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.5f)
                    cubicTo(w * 0.38f, h * 0.24f, w * 0.62f, h * 0.76f, w * 0.76f, h * 0.5f)
                }
                drawPath(p, color, style = stroke)
            }
            3 -> {
                drawLine(color, Offset(w * 0.24f, h * 0.78f), Offset(w * 0.5f, h * 0.22f), 2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.76f, h * 0.78f), Offset(w * 0.5f, h * 0.22f), 2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.34f, h * 0.6f), Offset(w * 0.66f, h * 0.6f), 2f, StrokeCap.Round)
            }
            else -> {
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.5f)
                    cubicTo(w * 0.32f, h * 0.12f, w * 0.66f, h * 0.88f, w * 0.88f, h * 0.5f)
                }
                drawPath(p, color, style = stroke)
            }
        }
    }
}

@Composable
private fun LoadingOverlay(stage: String, progress: Float, error: String?, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20A0C18))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(120.dp)) {
                    drawCircle(Ink.cyan.copy(alpha = 0.08f), size.minDimension / 2)
                    drawCircle(Ink.cyan.copy(alpha = 0.16f), size.minDimension / 2.6f)
                    drawArc(
                        color = Ink.cyan,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.coerceIn(0f, 1f),
                        useCenter = false,
                        style = Stroke(width = 4f, cap = StrokeCap.Round),
                    )
                }
                Text("${(progress * 100).toInt()}%", style = Type.caption.copy(color = Ink.cyan))
            }
            Spacer(Modifier.height(20.dp))
            Text(stage, style = Type.body.copy(color = Ink.mist))
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text("erro: $error", style = Type.caption.copy(color = Ink.rose))
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ink.indigo.copy(alpha = 0.6f))
                        .clickable { onRetry() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text("tentar de novo", style = Type.callout.copy(color = Ink.paper)) }
            }
        }
    }
}
