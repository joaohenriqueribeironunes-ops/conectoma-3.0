package com.conectoma.app.core

import android.content.Context
import com.conectoma.app.net.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Owns every engine and keeps them coherent: the connectome substrate, the spiking
 * network, the semantic space and the dopamine system.
 */
class Brain private constructor(val context: Context) {

    lateinit var connectome: Connectome
        private set
    lateinit var network: SpikingNetwork
        private set
    lateinit var semantics: SemanticEngine
        private set
    lateinit var reasoning: ReasoningEngine
        private set
    lateinit var consolidation: ConsolidationEngine
        private set
    lateinit var autonomy: AutonomousEngine
        private set
    lateinit var memory: MemoryEngine
        private set
    lateinit var mapping: ConnectomeMapping
        private set
    lateinit var cognition: CognitionEngine
        private set
    val dopamine = DopamineSystem()

    /** serializa todo acesso ao grafo semântico (chat, ingestão e autonomia) */
    private val learningLock = Any()
    private val recentConcepts = ArrayList<Int>(64)
    private var pendingConsolidation = false

    /** safe accessors: the UI can ask before loading finishes */
    val connectomeOrNull: Connectome? get() = if (this::connectome.isInitialized) connectome else null
    val semanticsOrNull: SemanticEngine? get() = if (this::semantics.isInitialized) semantics else null

    private val prefs = context.getSharedPreferences("conectoma", Context.MODE_PRIVATE)
    private var rng = Random(System.currentTimeMillis())
    private val learnedTopics = LinkedHashSet<String>()

    var ready = false
        private set
    var loadError: String? = null
        private set

    var totalLearningEvents = 0
        private set
    var totalSpikes = 0L
        private set

    /** evita inicialização dupla (app + serviço autônomo pedindo ao mesmo tempo) */
    private val initLock = Any()
    @Volatile
    private var initializing = false

    /**
     * Idempotente: uma segunda chamada durante (ou depois de) a primeira não
     * reconstrói os motores — reconstruir perderia a memória em curso e era a
     * principal fonte de falha de runtime no boot.
     */
    suspend fun initialize(onStage: (String, Float) -> Unit) = withContext(Dispatchers.Default) {
        val shouldRun = synchronized(initLock) {
            if (ready || initializing) false else { initializing = true; true }
        }
        if (!shouldRun) return@withContext
        try {
            onStage("abrindo o conectoma", 0.05f)
            connectome = Connectome.load(context)
            onStage("ligando ${connectome.nodeCount} neurônios", 0.45f)
            network = SpikingNetwork(connectome)
            onStage("carregando o léxico", 0.62f)
            semantics = SemanticEngine(context.assets.open("lexicon.bin").use { it.readBytes() })
            onStage("mapeando circuitos cognitivos", 0.70f)
            memory = MemoryEngine(semantics)
            mapping = ConnectomeMapping(connectome)
            cognition = CognitionEngine(mapping, network, semantics, dopamine, memory)
            reasoning = ReasoningEngine(connectome, network, semantics, dopamine, memory, cognition)
            consolidation = ConsolidationEngine(semantics)
            autonomy = AutonomousEngine(semantics)
            onStage("reconstruindo a memória", 0.82f)
            restore()
            onStage("pronto", 1f)
            ready = true
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.java.simpleName
            ready = false
        } finally {
            synchronized(initLock) { initializing = false }
        }
    }

    // ------------------------------------------------------------------ dialogue
    fun ask(text: String): ReasoningEngine.Response = synchronized(learningLock) {
        if (!ready || !this::reasoning.isInitialized) {
            return ReasoningEngine.Response(
                text = "Ainda estou acordando — tente de novo em instantes.",
                hits = emptyList(),
                dopamine = dopamine.level,
                reward = 0f,
                newLinks = 0,
                potentiated = 0,
                spikes = 0,
                learnedWords = 0,
                elapsedMs = 0L,
                kind = ReasoningEngine.Kind.PERGUNTA,
            )
        }
        val response = reasoning.respond(text)
        totalLearningEvents++
        totalSpikes += response.spikes
        lastCoverage = response.coverage
        lastSynapsesSwept = response.synapsesSwept
        remember(response)
        // cada conversa também vira memória: guarda o contexto para respostas futuras mais espontâneas
        runCatching {
            val clean = text.trim()
            val subjectIds = Tokenizer.contentWords(clean).mapNotNull { w ->
                semantics.idOf(w).takeIf { it >= 0 }
            }
            val ids = (subjectIds + response.hits.map { it.wordId }).filter { it >= 0 }.distinct()
            val meaningful = ids.isNotEmpty() && (
                clean.length > 70 || clean.contains(" é ") || clean.contains(" são ") ||
                    clean.contains(" significa ") || clean.contains(" quer dizer ")
                )
            if (meaningful) {
                memory.remember("conversa", "chat://turn", clean, ids)
                runCatching { saveMemory() }
            }
        }
        response
    }

    // ------------------------------------------------------------------ web learning
    data class IngestReport(
        val title: String,
        val url: String,
        val chars: Int,
        val newWords: Int,
        val links: Int,
        val definitions: Int,
        val concepts: List<String>,
        val reward: Float,
        val error: String? = null,
        val anchors: Int = 0,
        val bridges: Int = 0,
        val insights: List<String> = emptyList(),
    )

    suspend fun ingest(url: String): IngestReport = withContext(Dispatchers.IO) {
        try {
            val page = Web.fetch(url)
            val report = absorb(page.title, page.url, page.text)
            report
        } catch (t: Throwable) {
            IngestReport("falhou", url, 0, 0, 0, 0, emptyList(), 0f, t.message ?: "erro de rede")
        }
    }

    suspend fun ingestText(title: String, url: String, text: String): IngestReport =
        withContext(Dispatchers.Default) { absorb(title, url, text) }

    /** the core text-learning pass: co-occurrence, definitions, assemblies */
    private fun absorb(title: String, url: String, text: String): IngestReport =
        synchronized(learningLock) {
            if (!ready || !this::semantics.isInitialized) {
                return IngestReport(title, url, 0, 0, 0, 0, emptyList(), 0f, "o conectoma ainda está carregando")
            }
            absorbLocked(title, url, text)
        }

    /**
     * Deep-read integral: cada frase e cada palavra do material entram na rede,
     * sem teto de frases, de palavras por frase ou de palavras novas.
     */
    private fun absorbLocked(title: String, url: String, text: String): IngestReport {
        val sentences = Tokenizer.sentences(text)
        var newWords = 0
        var links = 0
        var definitions = 0
        val concepts = LinkedHashSet<Int>()
        val seenPairs = HashSet<Long>()

        for (sentence in sentences) {
            val words = Tokenizer.contentWords(sentence)
            if (words.size < 2) continue
            val ids = ArrayList<Int>(words.size)
            for (w in words) {
                var id = semantics.idOf(w)
                if (id < 0) {
                    id = semantics.idOrCreate(w, ids.takeLast(2))
                    if (id >= 0) newWords++
                }
                if (id >= 0) {
                    ids.add(id)
                    concepts.add(id)
                }
            }
            if (ids.size < 2) continue
            val window = 3
            for (i in ids.indices) {
                for (j in i + 1 until minOf(ids.size, i + 1 + window)) {
                    val a = ids[i]
                    val b = ids[j]
                    val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                    if (!seenPairs.add(key)) continue
                    val before = semantics.linkWeight(a, b)
                    semantics.reinforce(a, b, 0.11f)
                    if (semantics.linkWeight(a, b) > before + 0.003f) links++
                }
            }
            definitions += extractDefinitions(sentence, ids)
        }

        val novelty = (newWords / 40f).coerceAtMost(1f)
        val reinforcement = (links / 260f).coerceAtMost(1f)
        val reward = (0.25f + 0.45f * novelty + 0.3f * reinforcement).coerceIn(0f, 1f)
        dopamine.reward(reward, "leitura de $title")
        network.dopamine = dopamine.phasic.coerceAtLeast(0f)
        totalLearningEvents++
        totalSpikes += network.totalSpikes

        // reverberação profunda pelo material novo para integrar melhor
        val focus = concepts.take(24)
        if (focus.size >= 2) {
            val cands = focus.flatMap { semantics.neighborsOf(it).map { n -> n.first } }
                .distinct()
                .filter { semantics.exists(it) }
                .take(600)
            val thought = reasoning.think(focus, cands, steps = 34)
            lastCoverage = thought.coverage
            lastSynapsesSwept = thought.synapsesSwept
        }

        // guarda o material aprendido na memória episódica
        val conceptList = concepts.toList()
        runCatching { memory.remember(title, url, text, conceptList) }
        runCatching { saveMemory() }
        if (title.isNotBlank()) learnedTopics.add(title.take(120))
        while (learnedTopics.size > 120) learnedTopics.remove(learnedTopics.first())

        // costura o material novo no conhecimento antigo
        recentConcepts.addAll(conceptList)
        while (recentConcepts.size > 160) recentConcepts.removeAt(0)
        pendingConsolidation = conceptList.size >= 3
        val con = consolidation.consolidate(conceptList)
        if (con.insights.isNotEmpty()) dopamine.reward(0.4f + con.score, "consolidação")

        return IngestReport(
            title, url, text.length, newWords, links, definitions,
            concepts.map { semantics.name(it) }.take(12), reward,
            anchors = con.anchors,
            bridges = con.bridges,
            insights = con.insights.map { "${it.first} ↔ ${it.second}" },
        )
    }

    private fun extractDefinitions(sentence: String, ids: List<Int>): Int {
        val patterns = listOf(" é ", " são ", " significa ", " refere-se a ", " consiste em ", " quer dizer ")
        for (p in patterns) {
            val idx = sentence.indexOf(p)
            if (idx <= 2) continue
            val left = sentence.substring(0, idx).trim()
            val right = sentence.substring(idx + p.length).trim()
            if (right.length < 12) continue
            val subject = Tokenizer.contentWords(left).lastOrNull() ?: continue
            val sid = semantics.idOf(subject)
            if (sid >= 0 && semantics.definition(sid).length < 20) {
                semantics.setDefinition(sid, right.take(280))
                return 1
            }
        }
        return 0
    }

    // ------------------------------------------------------------------ autonomy
    data class Autonomy(
        val topic: String,
        val learnedChars: Int,
        val newWords: Int,
        val insight: Float,
        val description: String,
        val exploration: ReasoningEngine.Exploration?,
        val task: String = "",
    )

    suspend fun autonomousStep(allowNetwork: Boolean): Autonomy = withContext(Dispatchers.Default) {
        if (!ready || !this@Brain::autonomy.isInitialized) {
            return@withContext Autonomy("", 0, 0, 0f, "o conectoma ainda está carregando", null, "")
        }
        val task = autonomy.nextTask(pendingConsolidation, allowNetwork, rng)
        autonomy.record(task)
        var topic = ""
        var chars = 0
        var newWords = 0
        var insight = 0f
        var exploration: ReasoningEngine.Exploration? = null

        val description = when (task) {
            is AutonomousEngine.Task.Consolidate -> {
                val con = synchronized(learningLock) {
                    val report = consolidation.consolidate(recentConcepts.toList())
                    pendingConsolidation = false
                    report
                }
                autonomy.recordInsights(con.insights.size)
                insight = con.score
                val example = con.insights.firstOrNull()
                if (example != null) {
                    "consolidou: ${con.summary} (ex.: ${example.first} ↔ ${example.second})"
                } else {
                    "consolidou: ${con.summary}"
                }
            }
            is AutonomousEngine.Task.ReadGap -> {
                val page = if (allowNetwork) {
                    runCatching { Web.wikipediaSummary(task.word) }.getOrNull()
                } else null
                if (page == null) {
                    "tentou estudar “${task.word}” mas estava sem rede"
                } else {
                    val report = absorb(page.title, page.url, page.text)
                    topic = page.title
                    chars = report.chars
                    newWords = report.newWords
                    insight = report.reward
                    "estudou “${page.title}”: ${report.newWords} palavras novas e " +
                        "${report.anchors} ligações ao que já sabia"
                }
            }
            is AutonomousEngine.Task.Bridge -> {
                val ex = synchronized(learningLock) { reasoning.explore(rng) }
                exploration = ex
                insight = ex.insight
                if (ex.bridge >= 0) {
                    "aproximou “${semantics.name(ex.from)}” de “${semantics.name(ex.bridge)}”"
                } else {
                    "tentou aproximar “${semantics.name(ex.from)}” de “${semantics.name(ex.to)}”"
                }
            }
        }
        Autonomy(topic, chars, newWords, insight, description, exploration, task.label)
    }

    // ------------------------------------------------------------------ spontaneous voice
    var spontaneousCount = 0
        private set

    /** uma fala que nasce do estado interno, sem pergunta do usuário */
    fun spontaneous(): ReasoningEngine.Spontaneous? = synchronized(learningLock) {
        if (!ready || !this::reasoning.isInitialized) return null
        val s = runCatching { reasoning.spontaneous(rng, recentConcepts.toList()) }.getOrNull() ?: return null
        spontaneousCount++
        prefs.edit().putInt("spontaneous", spontaneousCount).apply()
        s
    }

    // ------------------------------------------------------------------ stats
    data class Stats(
        val neurons: Int,
        val synapses: Int,
        val words: Int,
        val learnedWords: Int,
        val links: Int,
        val plasticityEvents: Int,
        val dopamineBursts: Int,
        val spikes: Long,
        val dopamine: Float,
        val meanPlastic: Float,
        val learningEvents: Int,
        val autonomyTasks: Int,
        val autonomyReads: Int,
        val autonomyBridges: Int,
        val autonomyInsights: Int,
        val lastTask: String,
        val memoryEngrams: Int,
        val memoryFacts: Int,
        val spontaneous: Int,
        val coverage: Float,
        val synapsesSwept: Int,
        val cxNeurons: Int = 0,
        val mbNeurons: Int = 0,
        val wmOccupancy: Int = 0,
        val neuromodulators: String = "",
        val cognitiveDecision: String = "",
        val cognitiveConfidence: Float = 0f,
        val antiEpilepsyTrips: Int = 0,
    )

    /** null antes do boot pronto: nenhum acesso a `lateinit` estoura em runtime. */
    fun stats(): Stats? {
        if (!ready || !this::connectome.isInitialized || !this::semantics.isInitialized) return null
        var sum = 0.0
        for (p in connectome.plastic) sum += p
        val trace = if (this::cognition.isInitialized) cognition.lastTrace else null
        return Stats(
            neurons = connectome.nodeCount,
            synapses = connectome.edgeCount,
            words = semantics.baseCount + semantics.learnedWords,
            learnedWords = semantics.learnedWords,
            links = semantics.strengthenedLinks,
            plasticityEvents = semantics.plasticityEvents,
            dopamineBursts = dopamine.totalBursts,
            spikes = network.totalSpikes,
            dopamine = dopamine.level,
            meanPlastic = (sum / connectome.edgeCount).toFloat(),
            learningEvents = totalLearningEvents,
            autonomyTasks = autonomy.tasks,
            autonomyReads = autonomy.reads,
            autonomyBridges = autonomy.bridges,
            autonomyInsights = autonomy.insights,
            lastTask = autonomy.lastLabel,
            memoryEngrams = if (this::memory.isInitialized) memory.size else 0,
            memoryFacts = if (this::memory.isInitialized) memory.factCount else 0,
            spontaneous = spontaneousCount,
            coverage = lastCoverage,
            synapsesSwept = lastSynapsesSwept,
            cxNeurons = if (this::mapping.isInitialized) mapping.cxCluster.size else 0,
            mbNeurons = if (this::mapping.isInitialized) mapping.mbCluster.size else 0,
            wmOccupancy = if (this::cognition.isInitialized) cognition.workingMemory.occupancy() else 0,
            neuromodulators = if (this::cognition.isInitialized) cognition.modulators.snapshot() else "",
            cognitiveDecision = trace?.let { if (it.decision.winner >= 0) semantics.name(it.decision.winner) else "" } ?: "",
            cognitiveConfidence = trace?.decision?.confidence ?: 0f,
            antiEpilepsyTrips = trace?.guardTrips ?: 0,
        )
    }

    @Volatile
    var lastCoverage: Float = 0f
        private set
    @Volatile
    var lastSynapsesSwept: Int = 0
        private set

    // ------------------------------------------------------------------ persistence
    private fun remember(response: ReasoningEngine.Response) {
        if (totalLearningEvents % 4 == 0) {
            runCatching { save() }
        }
    }

    fun save() {
        if (!ready) return
        val st = stats() ?: return
        prefs.edit()
            .putString("semantics", semantics.serialiseLearned())
            .putInt("events", totalLearningEvents)
            .putLong("spikes", network.totalSpikes)
            .putInt("bursts", dopamine.totalBursts)
            .putString("topics", learnedTopics.joinToString("\u0001"))
            .putInt("autoTasks", autonomy.tasks)
            .putInt("autoReads", autonomy.reads)
            .putInt("autoBridges", autonomy.bridges)
            .putInt("autoConsolidations", autonomy.consolidations)
            .putInt("autoInsights", autonomy.insights)
            .putInt("spontaneous", spontaneousCount)
            .putString("recent", recentConcepts.takeLast(64).joinToString("\u0001") { semantics.name(it) })
            .putString(
                "status_line",
                "${fmt(st.neurons)} neurônios · ${fmt(st.synapses)} sinapses · ${fmt(st.words)} palavras"
            )
            .apply()
        runCatching { saveMemory() }
        runCatching { savePlastic() }
    }

    private fun saveMemory() {
        File(context.filesDir, "memory.bin").writeText(memory.serialise())
    }

    private fun loadMemory() {
        val file = File(context.filesDir, "memory.bin")
        if (!file.exists()) return
        runCatching { memory.restore(file.readText()) }
    }

    private fun fmt(v: Int): String = String.format(java.util.Locale("pt", "BR"), "%,d", v).replace(',', '.')

    private fun savePlastic() {
        val file = File(context.filesDir, "plastic.bin")
        val changed = ArrayList<Int>(4096)
        val values = ArrayList<Float>(4096)
        val plastic = connectome.plastic
        for (i in plastic.indices) {
            val p = plastic[i]
            if (p < 0.90f || p > 1.10f) {
                changed.add(i)
                values.add(p)
                if (changed.size >= 600_000) break
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(changed.size)
            for (i in changed.indices) {
                out.writeInt(changed[i])
                out.writeShort((values[i] * 4096f).toInt().coerceIn(-32768, 32767))
            }
        }
    }

    private fun loadPlastic() {
        val file = File(context.filesDir, "plastic.bin")
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val n = input.readInt()
                val plastic = connectome.plastic
                repeat(n) {
                    val idx = input.readInt()
                    val v = input.readShort() / 4096f
                    if (idx in plastic.indices) plastic[idx] = v
                }
            }
        }
    }

    private fun restore() {
        prefs.getString("semantics", null)?.let { semantics.restoreLearned(it) }
        prefs.getString("topics", "")?.split("\u0001")?.filter { it.isNotBlank() }?.forEach { learnedTopics.add(it) }
        prefs.getString("recent", "")?.split("\u0001")?.filter { it.isNotBlank() }?.forEach {
            val id = semantics.idOf(it)
            if (id >= 0) recentConcepts.add(id)
        }
        while (recentConcepts.size > 160) recentConcepts.removeAt(0)
        totalLearningEvents = prefs.getInt("events", 0)
        spontaneousCount = prefs.getInt("spontaneous", 0)
        dopamine.restoreBursts(prefs.getInt("bursts", 0))
        autonomy.restore(
            tasks = prefs.getInt("autoTasks", 0),
            reads = prefs.getInt("autoReads", 0),
            bridges = prefs.getInt("autoBridges", 0),
            consolidations = prefs.getInt("autoConsolidations", 0),
            insights = prefs.getInt("autoInsights", 0),
        )
        loadMemory()
        loadPlastic()
    }

    companion object {
        @Volatile
        private var instance: Brain? = null

        fun get(context: Context): Brain =
            instance ?: synchronized(this) {
                instance ?: Brain(context.applicationContext).also { instance = it }
            }
    }
}
