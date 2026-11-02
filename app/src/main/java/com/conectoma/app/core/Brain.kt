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
    val dopamine = DopamineSystem()

    /** serializa todo acesso ao grafo semântico (chat, ingestão e autonomia) */
    private val learningLock = Any()
    private val recentConcepts = ArrayList<Int>(64)
    private var pendingConsolidation = false

    /** safe accessors: the UI can ask before loading finishes */
    val connectomeOrNull: Connectome? get() = if (this::connectome.isInitialized) connectome else null
    val networkOrNull: SpikingNetwork? get() = if (this::network.isInitialized) network else null
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

    suspend fun initialize(onStage: (String, Float) -> Unit) = withContext(Dispatchers.Default) {
        try {
            onStage("abrindo o conectoma", 0.05f)
            connectome = Connectome.load(context)
            onStage("ligando ${connectome.nodeCount} neurônios", 0.45f)
            network = SpikingNetwork(connectome)
            onStage("carregando o léxico", 0.62f)
            semantics = SemanticEngine(context.assets.open("lexicon.bin").use { it.readBytes() })
            reasoning = ReasoningEngine(connectome, network, semantics, dopamine)
            consolidation = ConsolidationEngine(semantics)
            autonomy = AutonomousEngine(semantics)
            onStage("reconstruindo a memória", 0.82f)
            restore()
            onStage("pronto", 1f)
            ready = true
        } catch (t: Throwable) {
            loadError = t.message ?: t::class.java.simpleName
            ready = false
        }
    }

    // ------------------------------------------------------------------ dialogue
    fun ask(text: String): ReasoningEngine.Response = synchronized(learningLock) {
        val response = reasoning.respond(text)
        totalLearningEvents++
        totalSpikes += response.spikes
        remember(response)
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
        synchronized(learningLock) { absorbLocked(title, url, text) }

    private fun absorbLocked(title: String, url: String, text: String): IngestReport {
        val sentences = Tokenizer.sentences(text).take(700)
        var newWords = 0
        var links = 0
        var definitions = 0
        val concepts = LinkedHashSet<Int>()
        val seenPairs = HashSet<Long>()

        for (sentence in sentences) {
            val words = Tokenizer.contentWords(sentence).take(24)
            if (words.size < 2) continue
            val ids = ArrayList<Int>(words.size)
            for (w in words) {
                var id = semantics.idOf(w)
                if (id < 0 && newWords < 220) {
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
            var pairs = 0
            for (i in ids.indices) {
                for (j in i + 1 until minOf(ids.size, i + 1 + window)) {
                    if (pairs >= 10) break
                    val a = ids[i]
                    val b = ids[j]
                    val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                    if (!seenPairs.add(key)) continue
                    val before = semantics.linkWeight(a, b)
                    semantics.reinforce(a, b, 0.11f)
                    if (semantics.linkWeight(a, b) > before + 0.003f) links++
                    pairs++
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

        // a short reverberation through the new material
        val focus = concepts.take(6)
        if (focus.size >= 2) {
            val cands = focus.flatMap { semantics.neighborsOf(it).take(6).map { n -> n.first } }.distinct().take(80)
            reasoning.think(focus, cands, steps = 14)
        }

        // costura o material novo no conhecimento antigo
        val conceptList = concepts.toList()
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
    )

    fun stats(): Stats {
        var sum = 0.0
        for (p in connectome.plastic) sum += p
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
        )
    }

    // ------------------------------------------------------------------ persistence
    private fun remember(response: ReasoningEngine.Response) {
        if (totalLearningEvents % 4 == 0) {
            runCatching { save() }
        }
    }

    fun save() {
        val st = stats()
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
            .putString(
                "status_line",
                "${fmt(st.neurons)} neurônios · ${fmt(st.synapses)} sinapses · ${fmt(st.words)} palavras"
            )
            .apply()
        runCatching { savePlastic() }
    }

    private fun fmt(v: Int): String = String.format(java.util.Locale("pt", "BR"), "%,d", v).replace(',', '.')

    private fun savePlastic() {
        val file = File(context.filesDir, "plastic.bin")
        val changed = ArrayList<Int>(4096)
        val values = ArrayList<Float>(4096)
        val plastic = connectome.plastic
        for (i in plastic.indices) {
            val p = plastic[i]
            if (p < 0.94f || p > 1.06f) {
                changed.add(i)
                values.add(p)
                if (changed.size >= 250_000) break
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
        totalLearningEvents = prefs.getInt("events", 0)
        dopamine.restoreBursts(prefs.getInt("bursts", 0))
        autonomy.restore(
            tasks = prefs.getInt("autoTasks", 0),
            reads = prefs.getInt("autoReads", 0),
            bridges = prefs.getInt("autoBridges", 0),
            consolidations = prefs.getInt("autoConsolidations", 0),
            insights = prefs.getInt("autoInsights", 0),
        )
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
