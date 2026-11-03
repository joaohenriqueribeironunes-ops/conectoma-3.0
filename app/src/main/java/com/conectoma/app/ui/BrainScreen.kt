package com.conectoma.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything about the substrate, in one plain-language screen. */
@Composable
fun BrainScreen(state: UiState, vm: ConectomaViewModel, contentPadding: PaddingValues) {
    val stats = state.stats
    val semantics = vm.brain.semanticsOrNull
    var query by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    if (semantics == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {}
        return
    }
    val id = if (query.isBlank()) -1 else semantics.idOf(query)
    val id2 = if (second.isBlank()) -1 else semantics.idOf(second)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(contentPadding)
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("cérebro", style = Type.largeTitle)
        Text("o que ele já sabe e o quanto mudou", style = Type.callout)
        Spacer(Modifier.height(18.dp))

        GlassPanel(Modifier.fillMaxWidth(), glow = Ink.amber) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                DopamineDial(stats?.dopamine ?: 0f, Modifier.size(92.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("dopamina", style = Type.headline)
                    Text(
                        "nível ${pct(stats?.dopamine ?: 0f)} · ${stats?.dopamineBursts ?: 0} impulsos registrados",
                        style = Type.callout,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "cada aprendizado dispara um impulso que reforça as conexões usadas",
                        style = Type.caption.copy(color = Ink.dim, letterSpacing = 0.sp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("motor autônomo", style = Type.headline)
                    Text(
                        if (state.autonomyEnabled)
                            "trabalhando sozinho · ${stats?.lastTask ?: "iniciando"}"
                        else "parado — ele só aprende quando você manda",
                        style = Type.callout,
                    )
                }
                Toggle(state.autonomyEnabled) { vm.setAutonomy(it) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("${stats?.autonomyTasks ?: 0} tarefas", Ink.cyan)
                Pill("${stats?.autonomyReads ?: 0} leituras", Ink.violet)
                Pill("${stats?.autonomyBridges ?: 0} pontes", Ink.amber)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("${stats?.autonomyInsights ?: 0} insights", Ink.aqua)
                Pill("${stats?.links ?: 0} ligações totais", Ink.mist)
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(
                accent = Ink.indigo,
                enabled = !state.busy,
                onClick = { vm.autonomousOnce() },
            ) {
                Text("rodar uma tarefa agora", style = Type.headline.copy(fontSize = 13.sp))
            }
            if (state.autonomyLog.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.autonomyLog.take(3).forEach { line ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .size(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Ink.aqua)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(line, style = Type.caption.copy(color = Ink.paper.copy(alpha = 0.82f), letterSpacing = 0.sp))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("neurônios", stats?.neurons?.let { nf(it) } ?: "—", "vivos no conectoma", Modifier.weight(1f))
            MetricTile("sinapses", stats?.synapses?.let { nf(it) } ?: "—", "conexões reais", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("palavras", stats?.words?.let { nf(it) } ?: "—", "conhecidas", Modifier.weight(1f))
            MetricTile("ligações", stats?.links?.let { nf(it) } ?: "—", "criadas por você", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("memória", stats?.memoryEngrams?.let { nf(it) } ?: "—", "materiais lidos", Modifier.weight(1f))
            MetricTile("fatos", stats?.memoryFacts?.let { nf(it) } ?: "—", "prontos para citar", Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("memória episódica", style = Type.headline)
                    Text(
                        "o que ele leu fica guardado e volta quando o assunto aparece",
                        style = Type.callout,
                    )
                }
                Pill("${stats?.memoryFacts ?: 0} fatos", Ink.aqua)
            }
            val titles = runCatching { vm.brain.memory.titles.take(5) }.getOrDefault(emptyList())
            if (titles.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Nada lido ainda. Cole um URL ou escolha um PDF na aba aprender.", style = Type.callout.copy(color = Ink.dim))
            }
            titles.forEach { title ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Ink.aqua)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(title, style = Type.body.copy(color = Ink.paper.copy(alpha = 0.88f)), maxLines = 2)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.violet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("circuitos cognitivos", style = Type.headline)
                    Text(
                        "complexo central vira memória de trabalho; corpo cogumelo vira associação semântica",
                        style = Type.callout,
                    )
                }
                Pill("${stats?.wmOccupancy ?: 0}/32", Ink.violet)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("CX ${stats?.cxNeurons ?: 0}", Ink.cyan)
                Pill("MB ${stats?.mbNeurons ?: 0}", Ink.aqua)
                Pill("guarda ${stats?.antiEpilepsyTrips ?: 0}", Ink.amber)
            }
            val decision = stats?.cognitiveDecision ?: ""
            if (decision.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "última decisão: $decision · confiança ${pct(stats?.cognitiveConfidence ?: 0f)}",
                    style = Type.body.copy(color = Ink.paper.copy(alpha = 0.88f)),
                )
            }
            val nm = stats?.neuromodulators ?: ""
            if (nm.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(nm, style = Type.mono.copy(color = Ink.dim))
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("conexões que você fortaleceu")
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.violet) {
            val links = semantics.topLinks(8)
            if (links.isEmpty()) {
                Text("Ainda nenhuma. Converse comigo: cada ideia ligada a outra vira uma conexão.", style = Type.callout)
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

        Spacer(Modifier.height(18.dp))
        SectionLabel("dicionário")
        Spacer(Modifier.height(10.dp))
        InputField(query, { query = it }, "procure uma palavra", Modifier.fillMaxWidth())

        if (id >= 0) {
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), tint = Ink.indigo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(semantics.name(id), style = Type.title.copy(fontSize = 25.sp))
                    Spacer(Modifier.width(10.dp))
                    Pill(semantics.pos(id), Ink.aqua)
                }
                Spacer(Modifier.height(8.dp))
                val def = semantics.definition(id)
                Text(
                    def.ifBlank { "Ainda não tenho o significado. Me ensine: “${semantics.name(id)} é …”" },
                    style = Type.body.copy(color = if (def.isBlank()) Ink.dim else Ink.paper),
                )
                Spacer(Modifier.height(12.dp))
                SectionLabel("parecidas")
                Spacer(Modifier.height(6.dp))
                val similar = remember(id) { semantics.nearest(semantics.name(id), 5) }
                similar.forEach { (nid, sim) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(semantics.name(nid), style = Type.body.copy(color = Ink.paper.copy(alpha = 0.9f)))
                        Spacer(Modifier.weight(1f))
                        Text("%.2f".format(sim), style = Type.mono.copy(fontSize = 10.sp))
                        Spacer(Modifier.width(8.dp))
                        MiniBar(sim)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("o quanto dois conceitos estão distantes")
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth(), tint = Ink.aqua) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputField(query, { query = it }, "palavra A", Modifier.weight(1f))
                InputField(second, { second = it }, "palavra B", Modifier.weight(1f))
            }
            if (id >= 0 && id2 >= 0) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.3f".format(semantics.distance(id, id2)), style = Type.largeTitle.copy(fontSize = 38.sp, color = Ink.cyan))
                    Spacer(Modifier.width(8.dp))
                    Text("distância conceitual", style = Type.callout, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                DistRow("significado", (1f - semantics.cosine(id, id2)) / 2f, Ink.aqua)
                val graph = semantics.graphDistance(id, id2)
                DistRow("caminho no conectoma", if (graph < 0f) 0f else graph, Ink.violet)
                DistRow("forma da palavra", semantics.shapeDistance(id, id2), Ink.amber)
            } else {
                Spacer(Modifier.height(10.dp))
                Text("digite duas palavras que eu conheço", style = Type.callout.copy(color = Ink.dim))
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(accent = Ink.indigo, onClick = { vm.saveNow() }) {
                Text("salvar memória", style = Type.headline.copy(fontSize = 13.sp))
            }
            GlassButton(accent = Color2.slate, onClick = { vm.thinkAloud() }) {
                Text("pensar sozinho", style = Type.headline.copy(fontSize = 13.sp))
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}

private object Color2 {
    val slate = androidx.compose.ui.graphics.Color(0xFF2F3350)
}
