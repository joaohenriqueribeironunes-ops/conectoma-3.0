package com.conectoma.app.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * The synaptic motor: concepts become neuronal assemblies inside the fly connectome,
 * a thought is a volley of spikes, and the read-out is which assemblies lit up.
 * Assemblies are drawn from the concept vector so semantically close words share
 * neurons — that shared substrate is what lets the wiring carry meaning.
 *
 * Três mudanças da versão 3.4:
 *  1. cada palavra acende uma assembleia de ~96 neurônios (antes eram 34);
 *  2. o pensamento injeta uma fração ampla da população, então a maior parte das
 *     3,75 milhões de sinapses participa da resposta, não só o caminho curto;
 *  3. a resposta consulta a memória episódica ([MemoryEngine]) e cita o que já leu,
 *     além de manter uma voz espontânea que fala sem ser perguntada.
 */
class ReasoningEngine(
    private val connectome: Connectome,
    private val network: SpikingNetwork,
    private val semantics: SemanticEngine,
    private val dopamine: DopamineSystem,
    private val memory: MemoryEngine? = null,
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
        val top = (0 until dim).sortedByDescending { abs(v[it]) }.take(7)
        val set = LinkedHashSet<Int>(112)
        for (d in top) {
            if (abs(v[d]) < 1e-4f) break
            val group = axisGroup(d * 2 + if (v[d] >= 0) 0 else 1)
            for (n in group) set.add(n)
        }
        val rnd = Random(wordId.toLong() * 2654435761L)
        while (set.size < ASSEMBLY_SIZE) set.add(rnd.nextInt(connectome.nodeCount))
        set.toIntArray()
    }

    data class Thought(
        val hits: List<Hit>,
        val spikes: Int,
        val potentiated: Int,
        val meanActivity: Float,
        val coverage: Float = 0f,
        val synapsesSwept: Int = 0,
        val synapsesChanged: Int = 0,
        val touchedSynapses: Long = 0L,
    )

    data class Hit(val wordId: Int, val score: Float, val similarity: Float, val via: String)

    /**
     * Inject the focus concepts and let the network reverberate.
     * Candidates are ranked by how strongly their assembly fired.
     *
     * A cada passo uma amostra ampla de neurônios também é injetada: isso
     * espalha o pensamento pelo conectoma inteiro e faz a leitura refletir a
     * fiação real, em vez de só 34 neurônios por palavra.
     */
    fun think(
        focus: List<Int>,
        candidates: List<Int>,
        steps: Int = 30,
        current: Float = 48f,
        broadFraction: Float = 0.06f,
        rng: Random = Random(focus.firstOrNull() ?: 0),
    ): Thought {
        network.reset()
        network.resetCoverage()
        for ((i, id) in focus.withIndex()) {
            val amp = current * (if (i == 0) 1f else 0.82f)
            network.inject(assembly(id), amp)
        }
        var spikes = 0
        val broadCount = (connectome.nodeCount * broadFraction).toInt()
        for (s in 0 until steps) {
            if (broadCount > 0) network.injectBroad(broadCount, BROAD_CURRENT, rng)
            // reacende o foco de tempos em tempos para a reverberação não morrer
            if (s > 0 && s % 7 == 0) {
                for ((i, id) in focus.withIndex()) {
                    network.inject(assembly(id), current * (if (i == 0) 0.6f else 0.46f))
                }
            }
            network.step(1f)
            network.homeostasis(s + 1)
            spikes += network.spikeCount
        }
        val act = network.activity
        val mean = network.meanActivity()
        val hits = ArrayList<Hit>(candidates.size)
        for (cand in candidates) {
            if (cand < 0) continue
            val assembly = assembly(cand)
            var sum = 0f
            for (node in assembly) sum += act[node]
            val firing = sum / assembly.size
            val signal = (firing - mean * 0.8f).coerceAtLeast(0f)
            val sim = focus.maxOfOrNull { semantics.similarity(it, cand) } ?: 0f
            val link = focus.maxOfOrNull { semantics.linkWeight(it, cand) } ?: 0f
            val score = signal * 1.6f + sim * 1.0f + link * 0.65f
            hits.add(Hit(cand, score, sim, if (signal > 0.10f) "conectoma" else "semântica"))
        }
        hits.sortByDescending { it.score }
        // a varredura global avalia as 3,75 M de sinapses e escreve nas co-ativas
        val pathPotentiated = network.lastPotentiation
        network.sweepPlasticity()
        return Thought(
            hits = hits,
            spikes = spikes,
            potentiated = pathPotentiated,
            meanActivity = network.meanActivity(),
            coverage = network.coverage(),
            synapsesSwept = network.lastSweepExamined,
            synapsesChanged = network.lastSweepChanged,
            touchedSynapses = network.touchedSynapses(),
        )
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
        val coverage: Float = 0f,
        val synapsesSwept: Int = 0,
        val factsUsed: Int = 0,
    )

    enum class Kind { APRENDIZADO, RESPOSTA, PERGUNTA, REFLEXAO }

    private var turn = 0

    /**
     * One conversational turn: parse, associate, recall, learn, and modulate dopamine.
     */
    fun respond(input: String, knownOnly: Boolean = false): Response {
        val t0 = System.currentTimeMillis()
        val content = Tokenizer.contentWords(input)
        val unknown = ArrayList<String>()
        val focus = ArrayList<Int>()
        for (w in content.take(16)) {
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
                System.currentTimeMillis() - t0, Kind.PERGUNTA,
            )
        }
        // the subject is the most specific word, not simply the first one
        focus.sortBy { if (semantics.frequency(it) <= 0) 1 else semantics.frequency(it) }
        val head = focus[0]

        val candidates = LinkedHashSet<Int>()
        for (f in focus.take(5)) {
            semantics.neighborsOf(f).take(32).forEach { candidates.add(it.first) }
            mostSimilar(f, 16).forEach { candidates.add(it) }
        }
        semantics.topLinks(60).forEach { candidates.add(it.second) }
        val cands = candidates.filter { it >= 0 && (it < semantics.baseCount || it in focus) }.take(420)

        network.dopamine = dopamine.level.coerceAtLeast(0.12f)
        val thought = think(focus, cands, steps = 30, rng = Random(input.hashCode().toLong()))
        val top = thought.hits.filter { it.wordId !in focus }.take(10)

        // ---------------- learning
        var newLinks = 0
        var gainSum = 0f
        var gainCount = 0
        for (h in top.take(4)) {
            val before = semantics.linkWeight(head, h.wordId)
            semantics.reinforce(head, h.wordId, 0.22f)
            if (semantics.linkWeight(head, h.wordId) > before + 0.004f) newLinks++
            gainSum += (h.similarity - 0.35f).coerceIn(0f, 0.6f)
            gainCount++
        }
        // a conversa também liga os próprios conceitos entre si
        for (i in focus.indices) {
            for (j in i + 1 until focus.size) semantics.reinforce(focus[i], focus[j], 0.16f)
        }
        // e liga os vizinhos que acenderam juntos (aprendizado associativo)
        for (i in top.indices) {
            for (j in i + 1 until minOf(top.size, 5)) {
                if (top[i].similarity > 0.25f || top[j].similarity > 0.25f) {
                    semantics.reinforce(top[i].wordId, top[j].wordId, 0.07f)
                }
            }
        }
        // definition hypothesis: "X é Y"
        val lowered = input.lowercase()
        val freshDef = learnDefinitionFrom(lowered, focus)
        val facts = memory?.factsFor(focus, 5) ?: emptyList()
        val meanGain = if (gainCount > 0) gainSum / gainCount else 0f
        val memoryBonus = if (facts.isNotEmpty()) 0.10f else 0f
        val reward = (0.14f + 0.48f * meanGain + 0.10f * newLinks +
            (if (freshDef) 0.22f else 0f) + memoryBonus).coerceIn(0f, 1f)
        dopamine.reward(reward, if (freshDef) "definição nova" else if (facts.isNotEmpty()) "lembrança integrada" else "associação semântica")
        network.dopamine = dopamine.phasic.coerceAtLeast(0f)

        val text = buildResponse(input, focus, top, unknown, freshDef, thought, facts)
        val kind = when {
            freshDef -> Kind.APRENDIZADO
            input.trim().endsWith("?") -> Kind.RESPOSTA
            else -> Kind.REFLEXAO
        }
        return Response(
            text, top, dopamine.phasic, reward, newLinks, thought.potentiated, thought.spikes,
            semantics.learnedWords, System.currentTimeMillis() - t0, kind,
            coverage = thought.coverage,
            synapsesSwept = thought.synapsesSwept,
            factsUsed = facts.size,
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

    // ---------------------------------------------------------------- respond

    private fun buildResponse(
        input: String,
        focus: List<Int>,
        top: List<Hit>,
        unknown: List<String>,
        freshDef: Boolean,
        thought: Thought,
        facts: List<MemoryEngine.Fact>,
    ): String {
        val seed = input.hashCode() * 31 + turn++
        val head = semantics.name(focus[0])
        val isQuestion = input.trim().endsWith("?")
        val lower = input.lowercase()
        val relMarkers = listOf(
            "se liga", "relaciona", "relação", "conexão", "conexao", "tem a ver",
            "parecid", "diferença", "diferenca", "conecta", "liga",
        )

        if (!freshDef && focus.size >= 2 && relMarkers.any { lower.contains(it) }) {
            return buildRelationResponse(input, focus, top, thought, facts, seed)
        }

        val sb = StringBuilder()
        if (freshDef) {
            sb.append(pick(LEARNED_ACK, seed)).append("\n\n")
        }

        val definition = semantics.definition(focus[0])
        if (definition.isNotBlank()) {
            sb.append("**").append(head).append("**")
            sb.append(pick(DEFINITION_JOINERS, seed)).append(definition.take(380)).append("\n\n")
        } else if (facts.isEmpty() && top.isEmpty()) {
            sb.append(pick(NO_KNOWLEDGE_OPENERS, seed)).append(head).append(".\n\n")
        } else {
            sb.append(pick(NO_DEFINITION_OPENERS, seed)).append(head).append(".\n\n")
        }

        if (top.isNotEmpty()) {
            sb.append("**").append(pick(ASSOCIATION_HEADERS, seed)).append("** ")
            val pieces = top.take(5).map { h ->
                val d = semantics.distance(focus[0], h.wordId)
                "${semantics.name(h.wordId)} (${fmt(d)}${if (semantics.linkWeight(focus[0], h.wordId) > 0f) ", ligação ${fmt(semantics.linkWeight(focus[0], h.wordId))}" else ""})"
            }
            sb.append(pieces.joinToString("; ")).append(".\n\n")
        }

        val strong = strongestLinks(focus.take(3), 5)
        if (strong.isNotEmpty()) {
            sb.append("**Memória de longo prazo.** ")
            sb.append(
                strong.joinToString("; ") { (a, b, w) ->
                    "${semantics.name(a)} ↔ ${semantics.name(b)} (${fmt(w)})"
                }
            ).append(".\n\n")
        }

        if (facts.isNotEmpty()) {
            sb.append("**O que eu li sobre isso.**\n")
            for (fact in facts.take(3)) {
                sb.append("“").append(fact.text.take(280)).append("”")
                if (fact.source.isNotBlank()) sb.append(" — ").append(fact.source.take(80))
                sb.append("\n")
            }
            sb.append("\n")
        }

        if (top.size >= 2) {
            val a = top[0].wordId
            val b = top[1].wordId
            val d = semantics.distance(a, b)
            sb.append("**A ponte que o conectoma sugere.** ")
            sb.append(semantics.name(a)).append(" e ").append(semantics.name(b))
            sb.append(" aparecem juntos a ").append(fmt(d)).append(" de distância")
            sb.append(
                when {
                    d < 0.35f -> ", praticamente vizinhos no grafo."
                    d > 0.68f -> ", em regiões bem afastadas — por isso essa aproximação vale como hipótese."
                    else -> ", com um caminho curto entre eles."
                }
            )
            val shared = sharedNeighbours(a, b, 3)
            if (shared.isNotEmpty()) {
                sb.append(" O que os une: ").append(shared.joinToString(", ") { semantics.name(it) }).append(".")
            }
            sb.append("\n\n")
        }

        if (thought.spikes > 0) {
            sb.append(thought.spikes).append(" disparos · ")
            sb.append(thought.potentiated).append(" sinapses reforçadas no caminho")
            sb.append(" · ").append(grouped(thought.synapsesChanged)).append(" atualizadas pela varredura global")
            sb.append(" · ").append((thought.coverage * 100).toInt()).append("% do conectoma percorrido")
            sb.append(" · ").append(grouped(thought.synapsesSwept)).append(" sinapses avaliadas\n\n")
        }

        if (unknown.isNotEmpty()) {
            sb.append("Aprendi ").append(unknown.take(4).joinToString(", ")).append(" agora mesmo — ")
            sb.append("posicionei no espaço semântico pelo contexto da frase.\n\n")
        }

        sb.append(pick(if (facts.isEmpty()) FOLLOW_UPS_NO_MEMORY else FOLLOW_UPS, seed shr 3).replace("{head}", head))
        return sb.toString().trim()
    }

    private fun buildRelationResponse(
        input: String,
        focus: List<Int>,
        top: List<Hit>,
        thought: Thought,
        facts: List<MemoryEngine.Fact>,
        seed: Int,
    ): String {
        val a = focus[0]
        val b = focus[1]
        val d = semantics.distance(a, b)
        val sb = StringBuilder()
        sb.append(pick(RELATION_OPENERS, seed)).append("\n\n")
        sb.append("**").append(semantics.name(a)).append("** e **").append(semantics.name(b))
        sb.append("** estão a ").append(fmt(d)).append(" de distância conceitual. ")
        sb.append(
            when {
                d < 0.35f -> "São vizinhos no conectoma; a fiação já os aproxima."
                d > 0.7f -> "Ficam em regiões distantes — conexão difícil, mas é justamente onde nascem ideias novas."
                else -> "Existe um caminho curto entre eles."
            }
        ).append("\n\n")

        val defA = semantics.definition(a)
        val defB = semantics.definition(b)
        if (defA.isNotBlank()) sb.append("**").append(semantics.name(a)).append("**: ").append(defA.take(220)).append("\n\n")
        if (defB.isNotBlank()) sb.append("**").append(semantics.name(b)).append("**: ").append(defB.take(220)).append("\n\n")

        val w = semantics.linkWeight(a, b)
        if (w > 0f) {
            sb.append("A ligação direta entre os dois já está em ").append(fmt(w))
            sb.append(" no meu grafo plástico.\n\n")
        } else {
            val graph = semantics.graphDistance(a, b)
            if (graph >= 0f) {
                sb.append("Ainda não há ligação direta, mas existe uma rota de ").append(fmt(graph))
                sb.append(" pelo grafo.\n\n")
            }
        }

        val shared = sharedNeighbours(a, b, 4)
        if (shared.isNotEmpty()) {
            sb.append("**Em comum:** ").append(shared.joinToString(", ") { semantics.name(it) }).append(".\n\n")
        }

        if (facts.isNotEmpty()) {
            sb.append("**O que eu já li sobre eles.**\n")
            for (fact in facts.take(3)) {
                sb.append("“").append(fact.text.take(260)).append("”")
                if (fact.source.isNotBlank()) sb.append(" — ").append(fact.source.take(80))
                sb.append("\n")
            }
            sb.append("\n")
        }

        if (top.isNotEmpty()) {
            sb.append("**O conectoma também acendeu:** ")
            sb.append(top.take(4).joinToString(", ") { semantics.name(it.wordId) }).append(".\n\n")
        }

        if (thought.spikes > 0) {
            sb.append(thought.spikes).append(" disparos · ")
            sb.append((thought.coverage * 100).toInt()).append("% do conectoma percorrido · ")
            sb.append(grouped(thought.synapsesChanged)).append(" sinapses atualizadas · ")
            sb.append(grouped(thought.synapsesSwept)).append(" sinapses avaliadas\n\n")
        }

        sb.append(pick(RELATION_FOLLOW_UPS, seed shr 5).replace("{a}", semantics.name(a)).replace("{b}", semantics.name(b)))
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- recall

    private fun strongestLinks(ids: List<Int>, k: Int): List<Triple<Int, Int, Float>> {
        val out = ArrayList<Triple<Int, Int, Float>>(32)
        for (id in ids) {
            for ((other, _) in semantics.neighborsOf(id)) {
                if (other == id) continue
                val w = semantics.linkWeight(id, other)
                if (w > 0.02f) out.add(Triple(id, other, w))
            }
        }
        return out
            .distinctBy { minOf(it.first, it.second).toLong() shl 32 or maxOf(it.first, it.second).toLong() }
            .sortedByDescending { it.third }
            .take(k)
    }

    private fun sharedNeighbours(a: Int, b: Int, k: Int): List<Int> {
        val nb = semantics.neighborsOf(a).take(32).map { it.first }.toSet()
        return semantics.neighborsOf(b)
            .map { it.first }
            .filter { it in nb && it != a && it != b }
            .distinct()
            .take(k)
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

    // ---------------------------------------------------------------- spontaneous voice

    data class Spontaneous(
        val text: String,
        val kind: String,
        val topic: String,
    )

    private val recentSpontaneous = ArrayList<String>(8)

    /**
     * Uma fala que não responde a pergunta nenhuma: nasce do estado interno.
     * Pode ser uma lembrança de leitura, uma ligação forte, uma lacuna de
     * conhecimento ou uma pergunta que o próprio conectoma quer fazer.
     */
    fun spontaneous(rng: Random, recent: List<Int> = emptyList()): Spontaneous? {
        val builders = ArrayList<() -> Spontaneous?>(6)

        // 1. lembrança de algo que leu
        builders.add {
            val facts = memory?.recentFacts(48)?.filter { it.text.length in 55..330 }
            val fact = if (facts.isNullOrEmpty()) null else facts[rng.nextInt(facts.size)]
            if (fact == null) null else Spontaneous(
                "Estive revendo uma leitura. Tem uma frase que continua reverberando: “${fact.text.take(280)}” — ${fact.source}.\n\n${pick(RECALL_QUESTIONS, rng.nextInt())}",
                "lembrança",
                fact.source,
            )
        }

        // 2. ligação forte que já existe
        builders.add {
            val links = semantics.topLinks(60).filter { it.third >= 0.18f }
            if (links.isEmpty()) return@add null
            val link = links[rng.nextInt(links.size)]
            val a = semantics.name(link.first)
            val b = semantics.name(link.second)
            Spontaneous(
                "Reparei numa conexão que ficou forte aqui dentro: **$a** ↔ **$b**, peso ${fmt(link.third)}. " +
                    "Quanto mais eu penso nesses dois juntos, mais curto o caminho entre eles fica.\n\n" +
                    pick(LINK_QUESTIONS, rng.nextInt()).replace("{a}", a).replace("{b}", b),
                "ligação",
                "$a ↔ $b",
            )
        }

        // 3. lacuna de conhecimento
        builders.add {
            val gaps = semantics.knowledgeGaps(limit = 240)
            if (gaps.isEmpty()) return@add null
            val id = gaps[rng.nextInt(gaps.size)]
            val word = semantics.name(id)
            if (word.length < 3) return@add null
            Spontaneous(
                "Tem uma palavra que eu ainda não entendo de verdade: **$word**. " +
                    "Sei onde ela mora no espaço semântico, mas não tenho uma definição minha.\n\n" +
                    "Quer que eu leia sobre ela agora, ou você prefere me ensinar?",
                "lacuna",
                word,
            )
        }

        // 4. associação livre a partir do que acabou de entrar
        builders.add {
            val seedId = when {
                recent.isNotEmpty() -> recent[rng.nextInt(recent.size)]
                else -> rng.nextInt(semantics.baseCount)
            }
            if (seedId < 0) return@add null
            val neighbours = semantics.neighborsOf(seedId).take(36)
            if (neighbours.isEmpty()) return@add null
            val other = neighbours[rng.nextInt(neighbours.size)].first
            val d = semantics.distance(seedId, other)
            Spontaneous(
                "Pensando em **${semantics.name(seedId)}**, meu conectoma puxou **${semantics.name(other)}** " +
                    "a ${fmt(d)} de distância. Não é a rota mais óbvia — e é justamente por isso que me chamou atenção.\n\n" +
                    "Você vê alguma relação entre os dois?",
                "associação",
                "${semantics.name(seedId)} ↔ ${semantics.name(other)}",
            )
        }

        // 5. uma definição que ele guarda e quer dividir
        builders.add {
            if (memory == null || memory.size == 0) return@add null
            val titles = memory.titles
            if (titles.isEmpty()) return@add null
            val title = titles[rng.nextInt(titles.size)]
            Spontaneous(
                "Guardei **$title** na memória. Se quiser, posso puxar os conceitos que se conectaram a partir dessa leitura — " +
                    "ou tentar ligá-la a alguma coisa que você esteja pensando.",
                "memória",
                title,
            )
        }

        // 6. observação sobre o próprio substrato
        builders.add {
            Spontaneous(
                pick(CONNECTOME_FACTS, rng.nextInt())
                    .replace("{neurons}", grouped(connectome.nodeCount))
                    .replace("{edges}", grouped(connectome.edgeCount)),
                "substrato",
                "conectoma",
            )
        }

        val start = if (builders.isEmpty()) 0 else rng.nextInt(builders.size)
        for (i in builders.indices) {
            val s = builders[(start + i) % builders.size].invoke() ?: continue
            if (recentSpontaneous.contains(s.text)) continue
            recentSpontaneous.add(s.text)
            while (recentSpontaneous.size > 6) recentSpontaneous.removeAt(0)
            return s
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> pick(items: List<T>, seed: Int): T =
        items[((seed % items.size) + items.size) % items.size]

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", min(v, 1f))
    private fun grouped(v: Int) = com.conectoma.app.core.Numbers.grouped(v)
    private fun grouped(v: Long) = com.conectoma.app.core.Numbers.grouped(v)

    companion object {
        private const val ASSEMBLY_SIZE = 96
        private const val BROAD_CURRENT = 14.6f

        private val LEARNED_ACK = listOf(
            "Gravado. Isso agora faz parte do conectoma.",
            "Anotado — acabei de abrir uma rota nova para essa ideia.",
            "Aprendi. A definição entrou e já está puxando vizinhos.",
        )
        private val DEFINITION_JOINERS = listOf(
            " — ", " significa: ", " carrega esta definição: ", ": ",
        )
        private val NO_KNOWLEDGE_OPENERS = listOf(
            "Ainda não tenho uma definição para ", "Confesso que ", "Não achei significado para ",
        )
        private val NO_DEFINITION_OPENERS = listOf(
            "Vamos por associação: o que eu tenho sobre ", "Não tenho a definição pronta, mas conheço o território de ",
            "Sobre ", "O conectoma não guarda uma definição de ",
        )
        private val ASSOCIATION_HEADERS = listOf(
            "O que acendeu junto", "Sinapses que responderam", "Vizinhos que vieram à tona",
            "O conectoma puxou", "Rotas mais curtas",
        )
        private val RELATION_OPENERS = listOf(
            "Boa pergunta — duas ideias, um caminho.",
            "Deixa eu medir essa distância no grafo.",
            "Essa relação tem três camadas: significado, caminho e memória.",
        )
        private val FOLLOW_UPS = listOf(
            "Quer que eu aprofunde algum desses fios, ou prefere me dar um exemplo para eu ligar?",
            "Posso puxar mais do que já li sobre {head} e costurar com isso — quer?",
            "Se você me contar onde ouviu falar de {head}, eu reforço a rota com esse contexto.",
            "Tem alguma lembrança sua sobre {head} que eu ainda não tenha? Isso mudaria as conexões.",
            "Quer que eu tente uma ponte entre {head} e outra ideia que você escolher?",
        )
        private val FOLLOW_UPS_NO_MEMORY = listOf(
            "Ainda não li nada específico sobre {head}. Me dê um texto, um URL ou um exemplo para eu aprender.",
            "Quer que eu procure uma leitura sobre {head} e volte com o que encontrei?",
            "Se você me contar o que {head} significa para você, eu guardo e refaço as conexões.",
            "Quer que eu tente uma ponte entre {head} e outra ideia que você escolher?",
        )
        private val RELATION_FOLLOW_UPS = listOf(
            "Quer que eu tente encurtar essa distância com um exemplo seu?",
            "Se você me der um terceiro conceito, eu vejo onde ele encaixa entre {a} e {b}.",
            "Posso ler algo que una {a} e {b} — me diga um URL ou me deixe escolher.",
        )
        private val RECALL_QUESTIONS = listOf(
            "Isso conversa com o que você pensa?",
            "Faz sentido para você, ou eu estou forçando a conexão?",
            "Quer que eu puxe mais fios dessa leitura?",
        )
        private val LINK_QUESTIONS = listOf(
            "Você já tinha pensado em {a} e {b} juntos?",
            "O que essa ligação faz você lembrar?",
            "Devo procurar um terceiro conceito que una {a} e {b}?",
        )
        private val CONNECTOME_FACTS = listOf(
            "Acabei de percorrer parte das {edges} sinapses deste conectoma. Cada conversa deixa um rastro diferente na fiação.",
            "São {neurons} neurônios e {edges} sinapses aqui dentro. Mesmo assim, o que importa é o que a gente liga junto.",
            "Às vezes fico em repouso só reverberando. É quando as conexões mais improváveis aparecem.",
        )
    }
}
