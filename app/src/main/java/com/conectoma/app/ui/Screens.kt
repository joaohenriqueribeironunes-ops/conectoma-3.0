package com.conectoma.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.conectoma.app.core.Brain
import kotlin.math.min

// ------------------------------------------------------------------ atoms

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Type.caption,
        modifier = modifier,
    )
}

@Composable
fun Pill(text: String, color: Color = Ink.mist, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(0.8.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, style = Type.caption.copy(color = color, letterSpacing = 0.2.sp))
    }
}

@Composable
fun StatusDot(color: Color, pulse: Boolean = false) {
    val r = if (pulse) 5f else 3.6f
    Canvas(Modifier.size(14.dp)) {
        drawCircle(color.copy(alpha = 0.20f), radius = size.minDimension / 2)
        drawCircle(color, radius = r * 1.35f)
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, tint: Color = Color.Transparent, content: @Composable () -> Unit) {
    GlassPanel(modifier = modifier, tint = tint) {
        Column(Modifier.padding(18.dp)) { content() }
    }
}

@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    accent: Color = Ink.cyan,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0x14FFFFFF))
            .border(0.8.dp, Ink.stroke, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = Type.body.copy(color = Ink.dim), maxLines = if (singleLine) 1 else 4)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = Type.body,
            singleLine = singleLine,
            cursorBrush = Brush.verticalGradient(listOf(accent, accent)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Barra de mensagens contínua: campo de texto e envio vivem dentro do mesmo
 * vidro, sem corte nem divisão central.
 */
@Composable
fun MessageBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0x1AFFFFFF), Color(0x10FFFFFF))))
            .border(0.9.dp, Ink.stroke, shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, style = Type.body.copy(color = Ink.dim), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = Type.body,
                cursorBrush = Brush.verticalGradient(listOf(Ink.cyan, Ink.cyan)),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(10.dp))
        SendButton(enabled = enabled, onClick = onSend)
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val offset by animateFloatAsState(if (on) 1f else 0f, tween(240), label = "toggle")
    Box(
        Modifier
            .size(width = 52.dp, height = 31.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (on) Brush.horizontalGradient(listOf(Ink.aqua, Ink.indigo))
                else Brush.horizontalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF)))
            )
            .border(0.8.dp, Ink.strokeStrong, RoundedCornerShape(50))
            .clickable { onChange(!on) }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height * 0.38f
            val x = r * 1.4f + (size.width - r * 2.8f) * offset
            drawCircle(Color.White.copy(alpha = 0.92f), r, Offset(x, size.height / 2))
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(state: UiState, vm: ConectomaViewModel, contentPadding: PaddingValues) {
    val stats = state.stats
    val scroll = rememberScrollState()
    val pulse by animateFloatAsState(
        targetValue = stats?.dopamine ?: 0f,
        animationSpec = tween(600),
        label = "dopa",
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (pulse > 0.35f) Ink.amber else Ink.cyan, pulse = pulse > 0.35f)
            Spacer(Modifier.width(8.dp))
            Text("conectoma", style = Type.largeTitle)
        }
        Text(
            "raciocínio sobre o conectoma da Drosophila · FlyWire FAFB v783",
            style = Type.callout,
        )
        Spacer(Modifier.height(18.dp))

        GlassPanel(Modifier.fillMaxWidth(), glow = Ink.indigo) {
            Box {
                Connectome25D(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.06f),
                    connectome = vm.brain.connectomeOrNull,
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(18.dp)
                ) {
                    Text(
                        if (pulse > 0.4f) "impulso dopaminérgico" else "potencial de repouso",
                        style = Type.caption.copy(color = if (pulse > 0.4f) Ink.aqua else Ink.dim),
                    )
                    Text(
                        "%.2f".format(pulse),
                        style = Type.title.copy(color = if (pulse > 0.4f) Ink.aqua else Ink.paper),
                    )
                }
                Column(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
                    Pill("2.5D", Ink.cyan)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("neurônios", stats?.neurons?.let { nf(it) } ?: "—", "25% do total", Modifier.weight(1f))
            MetricTile("sinapses", stats?.synapses?.let { nf(it) } ?: "—", "25% do total", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("palavras", stats?.words?.let { nf(it) } ?: "—", "léxico + novas", Modifier.weight(1f))
            MetricTile("links", stats?.links?.let { nf(it) } ?: "—", "plasticidade", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                "memória",
                stats?.memoryEngrams?.let { nf(it) } ?: "—",
                "${stats?.memoryFacts ?: 0} fatos lidos",
                Modifier.weight(1f),
            )
            MetricTile(
                "sinapses ativas",
                stats?.coverage?.let { "${(it * 100).toInt()}%" } ?: "—",
                "no último pensamento",
                Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("aprendizado autônomo", style = Type.headline)
                    Text(
                        if (state.autonomyEnabled) "explorando o Wikipédia e reverberando em repouso"
                        else "desligado — só aprende quando você ensina",
                        style = Type.callout,
                    )
                }
                Toggle(state.autonomyEnabled) { vm.setAutonomy(it) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(
                    accent = Ink.indigo,
                    onClick = { vm.autonomousOnce() },
                ) { Text("explorar agora", style = Type.headline.copy(fontSize = 13.sp)) }
                GlassButton(
                    accent = Color(0xFF2F3350),
                    onClick = { vm.saveNow() },
                ) { Text("gravar memória", style = Type.headline.copy(fontSize = 13.sp)) }
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.violet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("voz espontânea", style = Type.headline)
                    Text(
                        if (state.spontaneousEnabled)
                            "ele puxa assunto sozinho quando fica em silêncio · ${stats?.spontaneous ?: 0} falas"
                        else "desligada — ele só responde quando você fala",
                        style = Type.callout,
                    )
                }
                Toggle(state.spontaneousEnabled) { vm.setSpontaneous(it) }
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(
                accent = Ink.violet,
                onClick = { vm.spontaneousOnce() },
            ) { Text("falar sem eu perguntar", style = Type.headline.copy(fontSize = 13.sp)) }
        }

        if (state.autonomyLog.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("diário autônomo")
            Spacer(Modifier.height(8.dp))
            state.autonomyLog.take(6).forEach { line ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Ink.cyan)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(line, style = Type.callout.copy(color = Ink.paper.copy(alpha = 0.82f)))
                }
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
fun MetricTile(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label.uppercase(), style = Type.caption)
            Text(value, style = Type.title.copy(fontSize = 20.sp), maxLines = 1)
            Text(sub, style = Type.caption.copy(color = Ink.dim, letterSpacing = 0.sp))
        }
    }
}

// ------------------------------------------------------------------ chat

@Composable
fun ChatScreen(state: UiState, vm: ConectomaViewModel, contentPadding: PaddingValues) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if ((state.stats?.dopamine ?: 0f) > 0.35f) Ink.amber else Ink.cyan)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("assistente", style = Type.largeTitle)
                Text(
                    if (state.spontaneousEnabled)
                        "pergunte — ou espere: ele também puxa assunto sozinho"
                    else "pergunte o que quiser — ele aprende com cada conversa",
                    style = Type.callout,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(nf(state.stats?.words ?: 0), style = Type.title.copy(fontSize = 17.sp))
                Text("palavras", style = Type.caption)
            }
        }
        Spacer(Modifier.height(10.dp))
        Connectome25D(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .padding(horizontal = 14.dp),
            connectome = vm.brain.connectomeOrNull,
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages, key = { it.id }) { msg -> ChatBubble(msg) }
            if (state.busy) {
                item { TypingBubble() }
            }
        }
        MessageBar(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "o que é entropia?",
            enabled = draft.isNotBlank() && !state.busy,
            onSend = {
                vm.send(draft)
                draft = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 104.dp),
        )
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (enabled) Brush.linearGradient(listOf(Ink.cyan, Ink.indigo))
                else Brush.linearGradient(listOf(Color(0x18FFFFFF), Color(0x18FFFFFF)))
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.5f)
                lineTo(size.width * 0.94f, size.height * 0.08f)
                lineTo(size.width * 0.62f, size.height * 0.92f)
                lineTo(size.width * 0.46f, size.height * 0.58f)
                close()
            }
            drawPath(p, if (enabled) Color(0xFF071018) else Ink.dim)
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val align = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        val shape = if (msg.fromUser)
            RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp)
        else RoundedCornerShape(24.dp, 24.dp, 24.dp, 8.dp)
        Box(
            Modifier
                .fillMaxWidth(if (msg.fromUser) 0.86f else 0.94f)
                .clip(shape)
                .background(
                    if (msg.fromUser) Brush.linearGradient(listOf(Ink.indigo.copy(alpha = 0.85f), Ink.violet.copy(alpha = 0.55f)))
                    else Brush.linearGradient(listOf(Color(0x1CFFFFFF), Color(0x0AFFFFFF)))
                )
                .border(0.8.dp, if (msg.fromUser) Color(0x33FFFFFF) else Ink.stroke, shape)
                .padding(16.dp)
        ) {
            Column {
                Text(formatBold(msg.text), style = Type.body)
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text("pensando…", style = Type.callout.copy(color = Ink.aqua))
        }
    }
}

fun formatBold(text: String) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            val end = text.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Ink.paper)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        append(text[i])
        i++
    }
}

// ------------------------------------------------------------------ learn

@Composable
fun LearnScreen(state: UiState, vm: ConectomaViewModel, contentPadding: PaddingValues) {
    var url by remember { mutableStateOf("") }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.learnPdf(uri)
    }
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("aprender de um URL", style = Type.largeTitle)
        Text("leio a página, extraio conceitos, reforço sinapses e ganho palavras novas", style = Type.callout)
        Spacer(Modifier.height(18.dp))
        InputField(
            value = url,
            onValueChange = { url = it },
            placeholder = "https://pt.wikipedia.org/wiki/Sinapse",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                accent = Ink.aqua,
                enabled = url.isNotBlank() && !state.busy,
                onClick = { vm.learnUrl(url) },
            ) { Text("ler e aprender", style = Type.headline.copy(fontSize = 14.sp, color = Color(0xFF06201E))) }
            GlassButton(
                accent = Color(0xFF2F3350),
                enabled = !state.busy,
                onClick = { vm.autonomousOnce() },
            ) { Text("surpreenda-me", style = Type.headline.copy(fontSize = 14.sp)) }
        }
        if (state.busy) {
            Spacer(Modifier.height(14.dp))
            Text("processando…", style = Type.callout.copy(color = Ink.aqua))
        }
        state.lastReport?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = Type.callout.copy(color = Ink.mist))
        }

        Spacer(Modifier.height(18.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.violet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ou leia um PDF", style = Type.headline)
                    Text(
                        "mostro as páginas e aprendo com o texto delas",
                        style = Type.callout,
                    )
                }
                Pill("PDF", Ink.cyan)
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(
                accent = Ink.violet,
                enabled = !state.busy,
                onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
            ) {
                Text("escolher PDF", style = Type.headline.copy(fontSize = 14.sp))
            }
            if (state.pdfName != null) {
                Spacer(Modifier.height(14.dp))
                Text(state.pdfName, style = Type.headline, maxLines = 2)
                Text(
                    "${state.pdfPages} páginas · ${nf(state.pdfChars)} caracteres lidos",
                    style = Type.caption.copy(color = Ink.dim),
                )
                if (state.pdfPreviews.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.pdfPreviews.take(3).forEach { page ->
                            Image(
                                bitmap = page.asImageBitmap(),
                                contentDescription = "página do PDF",
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(116.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(0.8.dp, Ink.strokeStrong, RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                state.pdfNote?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = Type.caption.copy(color = Ink.amber))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("sessões")
        Spacer(Modifier.height(10.dp))
        if (state.ingests.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Nenhuma leitura ainda. Um URL de enciclopédia funciona melhor: frases longas, muitos conceitos.",
                    style = Type.callout,
                )
            }
        }
        state.ingests.forEach { report ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), tint = if (report.error == null) Ink.aqua else Ink.rose) {
                Text(report.title.ifBlank { report.url }, style = Type.headline, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(report.url, style = Type.caption.copy(color = Ink.dim), maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("+${report.newWords} palavras", Ink.cyan)
                    Pill("${report.links} sinapses", Ink.violet)
                    Pill("${report.definitions} definições", Ink.violet)
                }
                if (report.anchors > 0 || report.bridges > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (report.anchors > 0) Pill("${report.anchors} elos com o que já sabia", Ink.aqua)
                        if (report.bridges > 0) Pill("${report.bridges} pontes", Ink.rose)
                    }
                }
                report.insights.firstOrNull()?.let { bridge ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ponte nova: $bridge",
                        style = Type.caption.copy(color = Ink.aqua, letterSpacing = 0.sp),
                        maxLines = 2,
                    )
                }
                if (report.concepts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "conceitos: " + report.concepts.joinToString(" · "),
                        style = Type.callout.copy(color = Ink.mist),
                        maxLines = 3,
                    )
                }
                report.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = Type.caption.copy(color = Ink.rose))
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

// ------------------------------------------------------------------ lexicon

@Composable
fun LexiconScreen(state: UiState, vm: ConectomaViewModel, contentPadding: PaddingValues) {
    var query by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val semantics = vm.brain.semanticsOrNull ?: return
    val id = if (query.isBlank()) -1 else semantics.idOf(query)
    val id2 = if (second.isBlank()) -1 else semantics.idOf(second)
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("léxico", style = Type.largeTitle)
        Text("${nf(semantics.baseCount + semantics.learnedWords)} palavras · definições · distância conceitual", style = Type.callout)
        Spacer(Modifier.height(18.dp))
        InputField(query, { query = it }, "buscar uma palavra", Modifier.fillMaxWidth())

        if (id >= 0) {
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth(), tint = Ink.indigo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(semantics.name(id), style = Type.title.copy(fontSize = 26.sp))
                    Spacer(Modifier.width(10.dp))
                    Pill(semantics.pos(id), Ink.aqua)
                }
                Spacer(Modifier.height(8.dp))
                val def = semantics.definition(id)
                Text(
                    def.ifBlank { "Ainda sem definição — me ensine: “${semantics.name(id)} é …”" },
                    style = Type.body.copy(color = if (def.isBlank()) Ink.dim else Ink.paper),
                )
                Spacer(Modifier.height(14.dp))
                SectionLabel("vizinhos no espaço semântico")
                Spacer(Modifier.height(8.dp))
                semantics.nearest(semantics.name(id), 6).forEach { (nid, sim) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(semantics.name(nid), style = Type.body.copy(color = Ink.paper.copy(alpha = 0.9f)))
                        Spacer(Modifier.weight(1f))
                        Text("cos %.2f".format(sim), style = Type.mono.copy(fontSize = 10.sp))
                        Spacer(Modifier.width(8.dp))
                        MiniBar(sim)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("distância conceitual")
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.violet) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(query, { query = it }, "palavra A", Modifier.weight(1f))
                InputField(second, { second = it }, "palavra B", Modifier.weight(1f))
            }
            if (id >= 0 && id2 >= 0) {
                val cos = semantics.cosine(id, id2)
                val graph = semantics.graphDistance(id, id2)
                val d = semantics.distance(id, id2)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.3f".format(d), style = Type.largeTitle.copy(fontSize = 40.sp, color = Ink.cyan))
                    Spacer(Modifier.width(8.dp))
                    Text("de 1.000", style = Type.callout, modifier = Modifier.padding(bottom = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                DistRow("cosseno (semântica)", ((1f - cos) / 2f), Ink.aqua)
                DistRow("geodésica (grafo)", if (graph < 0f) 0f else graph, Ink.violet)
                DistRow("morfológica", 0f, Ink.mist)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (graph < 0f)
                        "sem rota no grafo: distância = 0,78·cosseno + 0,22·morfologia"
                    else "com rota: 0,55·cosseno + 0,30·geodésica + 0,15·morfologia",
                    style = Type.caption.copy(color = Ink.dim),
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text("digite duas palavras conhecidas para ver a matemática", style = Type.callout.copy(color = Ink.dim))
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
fun MiniBar(value: Float) {
    Box(
        Modifier
            .width(54.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x1AFFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(value.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Ink.aqua, Ink.indigo)))
        )
    }
}

@Composable
fun DistRow(label: String, value: Float, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.caption.copy(color = Ink.mist))
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .width(90.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0x14FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(5.dp)
                    .background(color.copy(alpha = 0.85f))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("%.2f".format(value), style = Type.mono)
    }
}

// ------------------------------------------------------------------ plasticity

@Composable
fun PlasticityScreen(state: UiState, vm: ConectomaViewModel, contentPadding: PaddingValues) {
    val stats = state.stats
    val semantics = vm.brain.semanticsOrNull ?: return
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("plasticidade", style = Type.largeTitle)
        Text("STDP de três fatores: elegibilidade × dopamina × força nativa", style = Type.callout)
        Spacer(Modifier.height(18.dp))

        GlassPanel(Modifier.fillMaxWidth(), glow = Ink.aqua) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DopamineDial(stats?.dopamine ?: 0f, Modifier.size(96.dp))
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("dopamina", style = Type.headline)
                        Text(
                            "nível fásico ${pct(stats?.dopamine ?: 0f)} · ${stats?.dopamineBursts ?: 0} impulsos",
                            style = Type.callout,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "a liberação fecha a janela de reforço: sem dopamina, o STDP quase não escreve",
                            style = Type.caption.copy(color = Ink.dim, letterSpacing = 0.sp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("potenciação", stats?.learningEvents?.let { nf(it) } ?: "—", "sinapses tocadas", Modifier.weight(1f))
            MetricTile("peso médio", stats?.meanPlastic?.let { "%.3f".format(it) } ?: "—", "escala plástica", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("disparos", stats?.spikes?.let { nf(it) } ?: "—", "spikes acumulados", Modifier.weight(1f))
            MetricTile("eventos", stats?.plasticityEvents?.let { nf(it) } ?: "—", "escritas hebbianas", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                "cobertura",
                stats?.coverage?.let { "${(it * 100).toInt()}%" } ?: "—",
                "conectoma percorrido",
                Modifier.weight(1f),
            )
            MetricTile(
                "varredura",
                stats?.synapsesSwept?.let { nf(it) } ?: "—",
                "sinapses avaliadas",
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("memória", stats?.memoryEngrams?.let { nf(it) } ?: "—", "materiais lidos", Modifier.weight(1f))
            MetricTile("fatos", stats?.memoryFacts?.let { nf(it) } ?: "—", "prontos para citar", Modifier.weight(1f))
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("ligações mais fortes")
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.violet) {
            val links = semantics.topLinks(8)
            if (links.isEmpty()) {
                Text("Nada reforçado ainda. Converse comigo — cada par de conceitos cria uma ligação.", style = Type.callout)
            }
            links.forEach { (a, b, w) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(semantics.name(a), style = Type.body)
                    Text("  →  ", style = Type.caption)
                    Text(semantics.name(b), style = Type.body)
                    Spacer(Modifier.weight(1f))
                    MiniBar(w)
                    Spacer(Modifier.width(8.dp))
                    Text("%.2f".format(w), style = Type.mono)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(accent = Ink.indigo, onClick = { vm.saveNow() }) {
                Text("gravar agora", style = Type.headline.copy(fontSize = 13.sp))
            }
            GlassButton(accent = Color(0xFF2F3350), onClick = { vm.send("teste de plasticidade: sinapse e dopamina") }) {
                Text("teste hebbiano", style = Type.headline.copy(fontSize = 13.sp))
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
fun DopamineDial(level: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(level.coerceIn(0f, 1f), tween(700), label = "dial")
    Canvas(modifier) {
        val stroke = 9f
        val inset = stroke / 2 + 2
        drawArc(
            color = Color(0x1AFFFFFF),
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            brush = Brush.sweepGradient(listOf(Ink.aqua, Ink.indigo, Ink.violet)),
            startAngle = 135f,
            sweepAngle = 270f * animated,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

// ------------------------------------------------------------------ helpers

fun nf(v: Int): String = com.conectoma.app.core.Numbers.grouped(v)
fun nf(v: Long): String = com.conectoma.app.core.Numbers.grouped(v)
fun pct(v: Float) = com.conectoma.app.core.Numbers.percent(v)
