package com.conectoma.app.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Camada cognitiva construida sobre o conectoma real.
 *
 * Arquitetura:
 *
 *   texto -> SemanticInput -> neuronios sensoriais (inputNodes)
 *                                   |
 *                         rede LIF do conectoma
 *                                   |
 *        +--------------------------+--------------------------+
 *        |                          |                          |
 *   Complexo central          Corpo cogumelo             Saida motora
 *   (working memory)          (associacao/MLP)           (rate decoder)
 *        |                          |                          |
 *        +------- neuromodulacao ---+--------------------------+
 *                                   |
 *                            decisao + texto
 *
 * A cognicao nao chama rede externa nem LLM: e algebra de spikes, tracos
 * sinapticos e acumulacao de evidencia.
 */
class CognitionEngine(
    val mapping: ConnectomeMapping,
    val network: SpikingNetwork,
    val semantics: SemanticEngine,
    val dopamine: DopamineSystem,
    val memory: MemoryEngine? = null,
    val modulators: Neuromodulator = Neuromodulator(),
    val workingMemory: WorkingMemory = WorkingMemory(32),
) {
    init {
        network.neuromodulators = modulators
    }

    data class Signal(
        val wordId: Int,
        val score: Float,
        val similarity: Float,
        val via: String,
    )

    data class Decoded(
        val wordId: Int,
        val rate: Float,
        val score: Float,
        val via: String,
    )

    data class Decision(
        val winner: Int,
        val runnerUp: Int,
        val confidence: Float,
        val path: List<Int>,
        val reason: String,
    )

    data class Trace(
        val workingMemory: List<WorkingMemory.Slot>,
        val decoded: List<Decoded>,
        val decision: Decision,
        val ach: Float,
        val gaba: Float,
        val da: Float,
        val serotonin: Float,
        val norepinephrine: Float,
        val guardTrips: Int,
        val wmSpikes: Int,
        val networkSpikes: Int,
        val cxActivity: Float,
        val mbActivity: Float,
    )

    var lastTrace: Trace? = null
        private set

    /**
     * Prepara a neuromodulacao antes da propagacao. Novidade = conceitos que
     * ainda nao estao na mesa de trabalho; carga = tamanho do foco.
     */
    fun beginTurn(focus: List<Int>) {
        workingMemory.beginTurn()
        val overlap = focus.count { workingMemory.activationOf(it) > 0.05f }
        val novelty = if (focus.isEmpty()) 0.5f else 1f - overlap.toFloat() / focus.size
        val load = (focus.size / 8f).coerceIn(0f, 1f)
        modulators.onTurnStart(novelty, load, dopamine.level)
    }

    /**
     * Chamado dentro do loop de simulacao, entre passos da rede. Le a atividade
     * atual, avanca a dinamica de Izhikevich e devolve corrente para os nos de
     * contexto no proximo passo. Isso fecha o laco WM <-> conectoma.
     */
    fun tickWorkingMemory() {
        val cx = meanActivity(mapping.workingMemory)
        val mb = meanActivity(mapping.associationNodes)
        val input = FloatArray(workingMemory.capacity) { i ->
            val conceptId = workingMemory.concept[i]
            val semanticDrive = if (conceptId >= 0) {
                (semantics.frequency(conceptId).coerceAtMost(80) / 80f) * 2.2f
            } else 0f
            (cx * 15f + mb * 11f + workingMemory.activation[i] * 5f + semanticDrive -
                modulators.gaba * 4.5f)
        }
        workingMemory.step(input, modulators)
        // realimentacao: slots ativos injetam no complexo central e no contexto
        for (slot in workingMemory.top(8)) {
            val node = mapping.workingMemory[abs(slot.conceptId) % mapping.workingMemory.size]
            network.inject(node, 1.6f * slot.activation)
            network.inject(mapping.contextNodes[abs(slot.conceptId) % mapping.contextNodes.size], 0.8f * slot.activation)
        }
    }

    /** avanca a WM usando a atividade congelada da rede, sem injetar corrente */
    private fun stabilizeWorkingMemory(ticks: Int) {
        val cx = meanActivity(mapping.workingMemory)
        val mb = meanActivity(mapping.associationNodes)
        repeat(ticks) {
            val input = FloatArray(workingMemory.capacity) { i ->
                val conceptId = workingMemory.concept[i]
                val semanticDrive = if (conceptId >= 0) {
                    (semantics.frequency(conceptId).coerceAtMost(80) / 80f) * 2.2f
                } else 0f
                (cx * 15f + mb * 11f + workingMemory.activation[i] * 5f + semanticDrive -
                    modulators.gaba * 4.5f)
            }
            workingMemory.step(input, modulators)
        }
    }

    /**
     * Fecha um turno cognitivo: atualiza WM, reordena hipoteses por evidencia
     * (conectoma + memoria de trabalho + memoria episodica), decide e decodifica
     * a resposta em taxa de disparo.
     */
    fun observe(
        focus: List<Int>,
        hits: List<Signal>,
        facts: List<MemoryEngine.Fact>,
        spikes: Int,
    ): Trace {
        // mantem a modulacao definida em beginTurn; so registra a carga do turno
        modulators.clampAll()
        // a mesa guarda o foco atual; os hits entram depois da decisao
        workingMemory.encode(focus.take(4), 0.90f)

        // estabiliza a mesa de trabalho sem realimentar a rede (a simulacao acabou)
        stabilizeWorkingMemory(4)
        workingMemory.decay()

        val focusContext = focus.map { it to workingMemory.activationOf(it) }
            .filter { it.second > 0.03f }
        val reranked = hits.map { h ->
            // proximidade do hit com o foco que esta na mesa de trabalho
            val wm = focusContext.maxOfOrNull { (focusId, activation) ->
                semantics.similarity(h.wordId, focusId) * activation
            } ?: 0f
            val memoryBoost = if (facts.any { it.text.contains(semantics.name(h.wordId), ignoreCase = true) }) 0.12f else 0f
            val semanticBoost = h.similarity * 0.20f
            h.copy(score = h.score * (1f + 0.35f * wm) + memoryBoost + semanticBoost)
        }.sortedByDescending { it.score }

        val winner = reranked.firstOrNull()?.wordId ?: focus.firstOrNull() ?: -1
        val runner = reranked.getOrNull(1)?.wordId ?: -1
        val scores = reranked.take(6).map { it.score }
        val topScore = scores.maxOrNull() ?: 0f
        val spread = (topScore - (scores.minOrNull() ?: 0f)).coerceAtLeast(1e-3f)
        val temperature = (spread * 0.45f).coerceAtLeast(0.04f)
        val expSum = if (scores.isEmpty()) 1.0 else {
            scores.sumOf { exp(((it - topScore) / temperature).toDouble()) }
        }
        val confidence = if (scores.isEmpty() || topScore <= 0f) 0f else {
            (1.0 / expSum).toFloat().coerceIn(0f, 1f)
        }
        val start = focus.firstOrNull() ?: winner
        val path = semanticPath(start, winner, 4)
        val reason = when {
            winner < 0 -> "sem hipótese suficiente"
            confidence > 0.45f -> "evidência convergente no conectoma"
            confidence > 0.22f -> "evidência moderada; rota curta entre os conceitos"
            else -> "hipótese fraca; associei por proximidade semântica"
        }

        // o resultado tambem vira contexto para o proximo turno
        workingMemory.encode(reranked.take(2).map { it.wordId }, 0.30f)

        val denom = max(topScore, 1e-4f)
        val decoded = reranked.take(6).map { h ->
            Decoded(
                wordId = h.wordId,
                rate = (h.score / denom).coerceIn(0f, 1f),
                score = h.score,
                via = h.via,
            )
        }
        val trace = Trace(
            workingMemory = workingMemory.top(6),
            decoded = decoded,
            decision = Decision(winner, runner, confidence, path, reason),
            ach = modulators.acetylcholine,
            gaba = modulators.gaba,
            da = modulators.dopamine,
            serotonin = modulators.serotonin,
            norepinephrine = modulators.norepinephrine,
            guardTrips = modulators.guardTrips,
            wmSpikes = workingMemory.spikes,
            networkSpikes = spikes,
            cxActivity = meanActivity(mapping.workingMemory),
            mbActivity = meanActivity(mapping.associationNodes),
        )
        lastTrace = trace
        return trace
    }

    /** texto curto que explica o estado interno sem poluir a resposta */
    fun describe(trace: Trace, maxSlots: Int = 3): String {
        val sb = StringBuilder()
        sb.append("**Cognição.** ")
        if (trace.workingMemory.isNotEmpty()) {
            sb.append("WM: ")
            sb.append(
                trace.workingMemory.take(maxSlots).joinToString(" · ") {
                    "${semantics.name(it.conceptId)} (${fmt(it.activation)})"
                }
            )
            sb.append(" · ")
        }
        if (trace.decision.winner >= 0) {
            sb.append("decisão: **").append(semantics.name(trace.decision.winner)).append("**")
            sb.append(" (").append(fmt(trace.decision.confidence)).append(")")
            if (trace.decision.path.size >= 2) {
                sb.append(" · rota ")
                sb.append(trace.decision.path.joinToString(" → ") { semantics.name(it) })
            }
        } else {
            sb.append("sem hipótese")
        }
        sb.append("\n**Neuromodulação.** ")
        sb.append(String.format(
            java.util.Locale.US,
            "ACh %.2f · GABA %.2f · DA %.2f · 5-HT %.2f · NA %.2f",
            trace.ach, trace.gaba, trace.da, trace.serotonin, trace.norepinephrine,
        ))
        if (trace.guardTrips > 0) sb.append(" · guarda anti-epilepsia ").append(trace.guardTrips)
        return sb.toString().trim()
    }

    /** BFS ponderado simples: caminho de menor numero de saltos no grafo semantico */
    fun semanticPath(from: Int, to: Int, maxHops: Int = 4): List<Int> {
        if (from < 0 || to < 0) return emptyList()
        if (from == to) return listOf(from)
        val prev = HashMap<Int, Int>(256)
        val queue = ArrayDeque<Int>()
        prev[from] = -1
        queue.addLast(from)
        var hops = 0
        while (queue.isNotEmpty() && hops < maxHops) {
            repeat(queue.size) {
                val cur = queue.removeFirst()
                val neighbours = semantics.neighborsOf(cur)
                    .sortedByDescending { it.second }
                    .take(32)
                for ((next, _) in neighbours) {
                    if (prev.containsKey(next)) continue
                    prev[next] = cur
                    if (next == to) return reconstruct(prev, from, to)
                    queue.addLast(next)
                }
            }
            hops++
        }
        return emptyList()
    }

    private fun reconstruct(prev: Map<Int, Int>, from: Int, to: Int): List<Int> {
        val path = ArrayList<Int>(8)
        var cur = to
        while (cur >= 0 && cur != from) {
            path.add(cur)
            cur = prev[cur] ?: -1
        }
        path.add(from)
        path.reverse()
        return path
    }

    private fun meanActivity(nodes: IntArray): Float {
        if (nodes.isEmpty()) return 0f
        var sum = 0f
        val act = network.activity
        for (node in nodes) {
            if (node in act.indices) sum += act[node]
        }
        return sum / nodes.size
    }

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", v.coerceIn(0f, 1f))
}
