package com.conectoma.app.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * The synaptic motor: concepts become neuronal assemblies inside the fly connectome,
 * a thought is a volley of spikes, and the read-out is which assemblies lit up.
 * Assemblies are drawn from the concept vector so semantically close words share
 * neurons — that shared substrate is what lets the wiring carry meaning.
 */
class ReasoningEngine(
    private val connectome: Connectome,
    private val network: SpikingNetwork,
    private val semantics: SemanticEngine,
    private val dopamine: DopamineSystem,
) {
    private val assemblyCache = HashMap<Int, IntArray>(4096)
    private val groupCache = HashMap<Int, IntArray>()

    /** fixed neuron population for a (dimension, sign) semantic axis */
    private fun axisGroup(axis: Int): IntArray = groupCache.getOrPut(axis) {
        val rnd = Random(7919L * (axis + 1))
        val out = IntArray(9)
        for (i in out.indices) out[i] = rnd.nextInt(connectome.nodeCount)
        out
    }

    fun assembly(wordId: Int): IntArray = assemblyCache.getOrPut(wordId) {
        val v = semantics.vector(wordId)
        val dim = v.size
        val top = (0 until dim).sortedByDescending { abs(v[it]) }.take(3)
        val set = LinkedHashSet<Int>(40)
        for (d in top) {
            if (abs(v[d]) < 1e-4f) break
            val group = axisGroup(d * 2 + if (v[d] >= 0) 0 else 1)
            for (n in group) set.add(n)
        }
        val rnd = Random(wordId.toLong() * 2654435761L)
        while (set.size < 34) set.add(rnd.nextInt(connectome.nodeCount))
        set.toIntArray()
    }

    data class Thought(
        val hits: List<Hit>,
        val spikes: Int,
        val potentiated: Int,
        val meanActivity: Float,
    )

    data class Hit(val wordId: Int, val score: Float, val similarity: Float, val via: String)

    /**
     * Inject the focus concepts and let the network reverberate.
     * Candidates are ranked by how strongly their assembly fired.
     */
    fun think(focus: List<Int>, candidates: List<Int>, steps: Int = 26, current: Float = 46f): Thought {
        network.reset()
        for ((i, id) in focus.withIndex()) {
            val amp = current * (if (i == 0) 1f else 0.78f)
            network.inject(assembly(id), amp)
        }
        var spikes = 0
        for (s in 0 until steps) {
            network.step(1f)
            network.homeostasis(s + 1)
            spikes += network.spikeCount
        }
        val act = network.activity
        val hits = ArrayList<Hit>(candidates.size)
        for (cand in candidates) {
            if (cand < 0) continue
            val assembly = assembly(cand)
            var sum = 0f
            for (node in assembly) sum += act[node]
            val firing = sum / assembly.size
            val sim = focus.maxOfOrNull { semantics.similarity(it, cand) } ?: 0f
            val link = focus.maxOfOrNull { semantics.linkWeight(it, cand) } ?: 0f
            val score = firing * 1.1f + sim * 1.0f + link * 0.5f
            hits.add(Hit(cand, score, sim, if (firing > 0.2f) "conectoma" else "semântica"))
        }
        hits.sortByDescending { it.score }
        return Thought(hits, spikes, network.lastPotentiation, network.meanActivity())
    }

    data class Response(
        val text: String,
        val hits: List<Hit>,
        val dopamine: Float,
        val reward: Float,
        val newLinks: Int,
        val potentiated: Int,
        val spikes: Int,
        val learnedWords: Int,
        val elapsedMs: Long,
        val kind: Kind,
    )

    enum class Kind { APRENDIZADO, RESPOSTA, PERGUNTA, REFLEXAO }

    /**
     * One conversational turn: parse, associate, learn, and modulate dopamine.
     */
    fun respond(input: String, knownOnly: Boolean = false): Response {
        val t0 = System.currentTimeMillis()
        val tokens = Tokenizer.tokenize(input)
        val content = Tokenizer.contentWords(input)
        val unknown = ArrayList<String>()
        val focus = ArrayList<Int>()
        for (w in content.take(14)) {
            var id = semantics.idOf(w)
            if (id < 0) {
                unknown.add(w)
                if (!knownOnly) id = semantics.idOrCreate(w, focus.takeLast(3))
            }
            if (id >= 0 && id !in focus) focus.add(id)
        }
        if (focus.isEmpty()) {
            return Response(
                "Não consegui extrair conceitos dessa frase. Tente nomear algo: “o que é entropia?”",
                emptyList(), dopamine.level, 0f, 0, 0, 0, semantics.learnedWords,
                System.currentTimeMillis() - t0, Kind.PERGUNTA
            )
        }
        // the subject is the most specific word, not simply the first one
        focus.sortBy { if (semantics.frequency(it) <= 0) 1 else semantics.frequency(it) }

        val candidates = LinkedHashSet<Int>()
        for (f in focus.take(4)) {
            semantics.neighborsOf(f).take(24).forEach { candidates.add(it.first) }
            mostSimilar(f, 12).forEach { candidates.add(it) }
        }
        semantics.topLinks(40).forEach { candidates.add(it.second) }
        val cands = candidates.filter { it >= 0 && it < semantics.baseCount || it in focus }.take(280)

        val thought = think(focus, cands, steps = 22)
        val top = thought.hits.filter { it.wordId !in focus }.take(6)

        // ---------------- learning
        var newLinks = 0
        var gainSum = 0f
        var gainCount = 0
        for (h in top.take(3)) {
            val before = semantics.linkWeight(focus[0], h.wordId)
            semantics.reinforce(focus[0], h.wordId, 0.22f)
            if (semantics.linkWeight(focus[0], h.wordId) > before + 0.004f) newLinks++
            gainSum += (h.similarity - 0.35f).coerceIn(0f, 0.6f)
            gainCount++
        }
        for (i in focus.indices) {
            for (j in i + 1 until focus.size) semantics.reinforce(focus[i], focus[j], 0.16f)
        }
        // definition hypothesis: "X é Y"
        val lowered = input.lowercase()
        val freshDef = learnDefinitionFrom(lowered, focus)
        val meanGain = if (gainCount > 0) gainSum / gainCount else 0f
        val reward = (0.14f + 0.52f * meanGain + 0.12f * newLinks + if (freshDef) 0.22f else 0f)
            .coerceIn(0f, 1f)
        dopamine.reward(reward, if (freshDef) "definição nova" else "associação semântica")
        network.dopamine = dopamine.phasic.coerceAtLeast(0f)

        val text = buildResponse(input, focus, top, unknown, freshDef, thought)
        val kind = when {
            freshDef -> Kind.APRENDIZADO
            input.trim().endsWith("?") -> Kind.RESPOSTA
            else -> Kind.REFLEXAO
        }
        return Response(
            text, top, dopamine.phasic, reward, newLinks, thought.potentiated, thought.spikes,
            semantics.learnedWords, System.currentTimeMillis() - t0, kind
        )
    }

    private fun learnDefinitionFrom(lowered: String, focus: List<Int>): Boolean {
        val patterns = listOf(" é ", " são ", " significa ", " quer dizer ", " é definido como ")
        for (p in patterns) {
            val idx = lowered.indexOf(p)
            if (idx <= 1) continue
            val left = lowered.substring(0, idx).trim()
            val right = lowered.substring(idx + p.length).trim()
            if (right.length < 4 || left.length > 40) continue
            val subject = Tokenizer.contentWords(left).lastOrNull() ?: continue
            val sid = semantics.idOf(subject)
            if (sid >= 0 && semantics.definition(sid).isBlank()) {
                semantics.setDefinition(sid, right.replaceFirstChar { it.uppercase() })
                return true
            }
            if (sid >= 0 && right.length > 12 && !semantics.definition(sid).contains(right.take(20))) {
                semantics.setDefinition(sid, right.replaceFirstChar { it.uppercase() })
                return true
            }
        }
        return false
    }

    private fun buildResponse(
        input: String,
        focus: List<Int>,
        top: List<Hit>,
        unknown: List<String>,
        freshDef: Boolean,
        thought: Thought,
    ): String {
        val head = semantics.name(focus[0])
        val sb = StringBuilder()
        val isQuestion = input.trim().endsWith("?")
        if (freshDef) {
            sb.append("Gravado. ").append(head).append(" agora carrega essa definição no conectoma.\n\n")
        }
        val ownDefinition = semantics.definition(focus[0])
        val relMarkers = listOf("se liga", "relaciona", "relação", "conexão", "conexao", "tem a ver", "parecid", "diferença")
        if (!freshDef && focus.size >= 2 && relMarkers.any { input.lowercase().contains(it) }) {
            val a = focus[0]
            val b = focus[1]
            val d = semantics.distance(a, b)
            sb.append("**").append(semantics.name(a)).append("** e **").append(semantics.name(b))
            sb.append("** estão a ").append(fmt(d)).append(" de distância conceitual. ")
            sb.append(
                when {
                    d < 0.35f -> "São vizinhos no conectoma."
                    d > 0.7f -> "Ficam em regiões distantes — conexão difícil, mas interessante."
                    else -> "Existe um caminho curto entre eles."
                }
            )
            val defA = semantics.definition(a).take(160)
            val defB = semantics.definition(b).take(160)
            if (defA.isNotBlank()) sb.append("\n\n").append(semantics.name(a)).append(": ").append(defA)
            if (defB.isNotBlank()) sb.append("\n\n").append(semantics.name(b)).append(": ").append(defB)
            val nbA = semantics.neighborsOf(a).map { it.first }.toSet()
            val shared = semantics.neighborsOf(b).map { it.first }.filter { it in nbA }.take(3)
            if (shared.isNotEmpty()) {
                sb.append("\n\nEm comum: ").append(shared.joinToString(", ") { semantics.name(it) }).append(".")
            }
            return sb.toString()
        }
        if (!freshDef && isQuestion && ownDefinition.isNotBlank()) {
            sb.append("**").append(head).append("** — ").append(ownDefinition.take(300)).append("\n\n")
            if (top.isNotEmpty()) {
                sb.append("Liga-se a ").append(top.take(4).joinToString(", ") { semantics.name(it.wordId) }).append(".")
                sb.append("\n\n")
                val def2 = semantics.definition(top[0].wordId)
                if (def2.isNotBlank()) sb.append(semantics.name(top[0].wordId)).append(": ").append(def2.take(180))
            }
            return sb.toString()
        }
        if (top.isEmpty()) {
            sb.append("Não encontrei vizinhos para \"").append(head).append("\". ")
            sb.append("Me dê mais contexto — ou um URL — e eu abro novas rotas sinápticas.")
            return sb.toString()
        }
        val lead = top[0]
        val def = semantics.definition(lead.wordId)
        sb.append(if (input.trim().endsWith("?")) "" else "Ao pensar em ").append(head)
        sb.append(if (input.trim().endsWith("?")) "a rota mais curta no conectoma leva a " else " o conectoma acendeu ")
        sb.append("**").append(semantics.name(lead.wordId)).append("**")
        sb.append(" (distância conceitual ").append(fmt(semantics.distance(focus[0], lead.wordId))).append(")")
        if (def.isNotBlank()) sb.append(": ").append(def.take(220))
        sb.append("\n\n")
        sb.append("Outras sinapses ativadas: ")
        sb.append(top.drop(1).take(4).joinToString(", ") { semantics.name(it.wordId) })
        sb.append(".")
        if (thought.spikes > 0) {
            sb.append("\n\n").append(thought.spikes).append(" disparos · ")
            sb.append(thought.potentiated).append(" sinapses reforçadas (STDP)")
        }
        if (unknown.isNotEmpty()) {
            sb.append("\n\nAprendi ").append(unknown.take(4).joinToString(", ")).append(" agora mesmo — ")
            sb.append("posicionei no espaço semântico pelo contexto da frase.")
        }
        if (top.size >= 2) {
            sb.append("\n\nPosso aprofundar essa conexão se você quiser.")
        }
        return sb.toString()
    }

    private fun mostSimilar(id: Int, k: Int): List<Int> {
        val scored = ArrayList<Pair<Int, Float>>(semantics.baseCount)
        for (i in 0 until semantics.baseCount) {
            if (i == id) continue
            scored.add(i to semantics.cosine(id, i))
        }
        scored.sortByDescending { it.second }
        return scored.take(k).map { it.first }
    }

    /** deliberate practice: wander through far-apart concepts and try to bridge them */
    fun explore(rng: Random): Exploration {
        val a = rng.nextInt(semantics.baseCount)
        var b = rng.nextInt(semantics.baseCount)
        var guard = 0
        while (guard++ < 12 && (semantics.cosine(a, b) > 0.32f || b == a)) {
            b = rng.nextInt(semantics.baseCount)
        }
        val cands = LinkedHashSet<Int>()
        semantics.neighborsOf(a).take(20).forEach { cands.add(it.first) }
        semantics.neighborsOf(b).take(20).forEach { cands.add(it.first) }
        mostSimilar(a, 16).forEach { cands.add(it) }
        mostSimilar(b, 16).forEach { cands.add(it) }
        val list = cands.filter { it != a && it != b }.take(180)
        val thought = think(listOf(a, b), list, steps = 20)
        val best = thought.hits.firstOrNull() ?: return Exploration(a, b, -1, 0f, 0f, 0)
        val bridge = best.similarity
        val novelty = 1f - semantics.linkWeight(a, b)
        val insight = (bridge * 0.6f + novelty * 0.4f)
        if (best.wordId >= 0) {
            semantics.reinforce(a, best.wordId, 0.12f)
            semantics.reinforce(b, best.wordId, 0.12f)
        }
        if (insight > 0.45f) {
            semantics.reinforce(a, b, 0.1f)
            dopamine.reward(insight.coerceAtMost(0.9f), "ponte autônoma")
            network.dopamine = dopamine.phasic.coerceAtLeast(0f)
        }
        return Exploration(a, b, best.wordId, bridge, insight, thought.spikes)
    }

    data class Exploration(
        val from: Int, val to: Int, val bridge: Int,
        val similarity: Float, val insight: Float, val spikes: Int,
    )

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", min(v, 1f))
}
