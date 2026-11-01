package app.conectoma.v3.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.conectoma.v3.MainActivity
import app.conectoma.v3.engine.ConnectomeConstants
import app.conectoma.v3.ui.theme.Dopamine
import app.conectoma.v3.ui.theme.GlassCard
import app.conectoma.v3.ui.theme.Label
import app.conectoma.v3.ui.theme.LiquidWallpaper
import app.conectoma.v3.ui.theme.Nerve
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ConectomaRoot(
    onListen: () -> Unit,
    onStopListen: () -> Unit,
    vm: ConectomaViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(activity) {
        val host = activity as? MainActivity
        host?.onSpeech = { vm.speak(it) }
        host?.onSpeechState = { vm.setListening(it) }
        onDispose {
            host?.onSpeech = null
            host?.onSpeechState = null
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        LiquidWallpaper()
        if (state.booting) {
            BootPane(state.bootProgress)
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                            .navigationBarsPadding()
                            .clip(RoundedCornerShape(28.dp)),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                    ) {
                        val items = listOf("Rede", "Aprender", "URL", "Léxico", "OS")
                        val icons = listOf(
                            Icons.Outlined.Hub,
                            Icons.Outlined.Mic,
                            Icons.Outlined.Link,
                            Icons.Outlined.MenuBook,
                            Icons.Outlined.PhoneAndroid
                        )
                        items.forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icons[i], contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { pad ->
                when (tab) {
                    0 -> HomePane(state, pad)
                    1 -> LearnPane(state, pad, vm, onListen, onStopListen)
                    2 -> UrlPane(state, pad, vm)
                    3 -> LexiconPane(state, pad, vm)
                    4 -> HyperPane(state, pad)
                }
            }
        }
        DopamineFlash(state)
    }
}

@Composable
private fun BootPane(progress: Int) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Conectoma 3.0", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tecendo ${ConnectomeConstants.SYNAPSES} sinapses\n(1/4 dos neurônios FlyWire)",
            color = Label
        )
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = Nerve,
            trackColor = Color.White.copy(alpha = 0.35f),
            strokeCap = StrokeCap.Round
        )
        Spacer(Modifier.height(8.dp))
        Text("$progress%", fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomePane(state: UiState, pad: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(pad).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Conectoma", style = MaterialTheme.typography.displayLarge)
            Text("disposto a aprender", color = Label)
        }
        item { ConnectomeCanvas(state.active, state.pulse?.dopamine ?: 0f) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatChip("neurônios", state.neurons.toString(), Modifier.weight(1f))
                StatChip("sinapses", "3,75M", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                StatChip("léxico", state.lexicon.toString(), Modifier.weight(1f))
                StatChip("compreendeu", state.understood.toString(), Modifier.weight(1f))
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("neuroplasticidade", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("peso médio ${"%.3f".format(state.meanWeight)} · assembleias ativas ${state.active}")
                    Text(
                        "Hebb: disparo conjunto reforça a aresta quando a distância semântica e a matemática fecham.",
                        color = Label
                    )
                }
            }
        }
        items(state.log.reversed()) { line ->
            Text(line, color = Label, style = MaterialTheme.typography.bodyLarge)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LearnPane(
    state: UiState,
    pad: PaddingValues,
    vm: ConectomaViewModel,
    onListen: () -> Unit,
    onStopListen: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(pad).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Fale com o SNC", style = MaterialTheme.typography.headlineMedium)
            Text("cada entendimento dispara um impulso dopaminérgico", color = Label)
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("escreva ou fale um conceito…") },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White.copy(alpha = 0.45f),
                    focusedContainerColor = Color.White.copy(alpha = 0.65f)
                )
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.teach(text); text = "" },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Nerve)
                ) { Text("ensinar") }
                Button(
                    onClick = { if (state.listening) onStopListen() else onListen() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.listening) Dopamine else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Outlined.Mic, null)
                    Text(if (state.listening) "  ouvindo" else "  falar")
                }
                TextButton(onClick = { vm.autonomousOnce() }) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Text("  autônomo")
                }
            }
        }
        items(state.traces) { t ->
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${t.left}  ↔  ${t.right}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "semântica ${"%.2f".format(t.semantic)} · matemática ${"%.2f".format(t.math)} · hebb ${"%.2f".format(t.hebb)}",
                        color = Label
                    )
                    Text(
                        if (t.impulse.understood) "impulso nervoso + DA ${"%.2f".format(t.impulse.dopamine)}"
                        else "ensaio — ainda aproximando assembleias",
                        color = if (t.impulse.understood) Dopamine else Label
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun UrlPane(state: UiState, pad: PaddingValues, vm: ConectomaViewModel) {
    var url by remember { mutableStateOf("https://pt.wikipedia.org/wiki/Conectoma") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(pad).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Aprender por URL", style = MaterialTheme.typography.headlineMedium)
        Text("o conectoma lê a página, tokeniza e fortalece caminhos", color = Label)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp)
        )
        Button(
            onClick = { vm.learnUrl(url) },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Nerve)
        ) { Text("integrar página") }
        state.traces.firstOrNull()?.let {
            Text("último par ${it.left} ↔ ${it.right}", color = Label)
        }
    }
}

@Composable
private fun LexiconPane(state: UiState, pad: PaddingValues, vm: ConectomaViewModel) {
    var q by remember { mutableStateOf(state.query) }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(pad).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Dicionário vivo", style = MaterialTheme.typography.headlineMedium)
            Text("${state.lexicon} verbetes — cresce com fala, URLs e ciclos autônomos", color = Label)
        }
        item {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("buscar ou ensinar uma palavra") },
                shape = RoundedCornerShape(22.dp)
            )
            Button(
                onClick = { vm.lookup(q) },
                modifier = Modifier.padding(top = 8.dp),
                shape = CircleShape
            ) { Text("conectar") }
        }
        state.lookup?.let { lex ->
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(lex.word, style = MaterialTheme.typography.titleLarge)
                        Text(lex.meaning)
                        Text("${lex.domain} · ${lex.visits} visitas", color = Label)
                    }
                }
            }
        }
        items(state.neighbors) { (lex, sim) ->
            Text("${lex.word}  ·  sim ${"%.2f".format(sim)}  — ${lex.meaning.take(90)}", color = Label)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HyperPane(state: UiState, pad: PaddingValues) {
    val h = state.hyper
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(pad).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("HyperOS 1", style = MaterialTheme.typography.headlineMedium)
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${h.manufacturer} ${h.model}")
                Text("Android ${h.androidRelease}  ·  SDK ${h.sdk}")
                Text("HyperOS: ${h.hyperOsName ?: "não reportado"} ${h.hyperOsVersion ?: ""}")
                Text("MIUI: ${h.miuiVersion ?: "—"}")
                Text(if (h.isHyperOs) "ambiente HyperOS detectado" else "compatível em qualquer Android 8+")
                Text(state.hyperHint, color = Label)
            }
        }
        Text(
            "Integração: targetSdk 34, minSdk 26 (acima do piso 23 da Xiaomi), meta-data de HyperOS, " +
                "boot MIUI, WorkManager para aprendizado autônomo, blur Liquid Glass no Compose.",
            color = Label
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = Label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun ConnectomeCanvas(active: Int, da: Float) {
    val dark = isSystemInDarkTheme()
    GlassCard(Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val n = 42
            val cx = size.width / 2
            val cy = size.height / 2
            val r = min(cx, cy) * 0.82f
            for (i in 0 until n) {
                val a1 = i * (Math.PI * 2 / n)
                val a2 = (i * 7) * (Math.PI * 2 / n)
                val p1 = Offset(cx + (r * cos(a1)).toFloat(), cy + (r * 0.72f * sin(a1)).toFloat())
                val p2 = Offset(cx + (r * 0.55f * cos(a2)).toFloat(), cy + (r * 0.4f * sin(a2)).toFloat())
                drawLine(
                    color = Nerve.copy(alpha = 0.18f + 0.25f * da),
                    start = p1,
                    end = p2,
                    strokeWidth = 2f
                )
            }
            for (i in 0 until n) {
                val a = i * (Math.PI * 2 / n)
                val p = Offset(cx + (r * cos(a)).toFloat(), cy + (r * 0.72f * sin(a)).toFloat())
                val glow = if (i < active.coerceAtMost(n)) Dopamine else if (dark) Color.White else Color(0xFF1C1C1E)
                drawCircle(glow.copy(alpha = 0.9f), radius = 4.5f, center = p)
            }
            drawCircle(
                color = Nerve.copy(alpha = 0.35f),
                radius = 18f + da * 28f,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
        }
    }
}

@Composable
private fun DopamineFlash(state: UiState) {
    val show = state.pulse?.understood == true
    val alpha by animateFloatAsState(if (show) 1f else 0f, tween(450), label = "da")
    AnimatedVisibility(visible = show && alpha > 0.05f, enter = fadeIn() + scaleIn()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            GlassCard(Modifier.padding(top = 56.dp)) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        progress = { state.pulse?.dopamine ?: 0f },
                        modifier = Modifier.size(22.dp),
                        color = Dopamine,
                        strokeWidth = 3.dp
                    )
                    Text("  impulso dopaminérgico — o SNC sentiu que aprendeu", fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
