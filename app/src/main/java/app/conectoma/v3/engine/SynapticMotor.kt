package app.conectoma.v3.engine

data class LearningTrace(
    val left: String,
    val right: String,
    val semantic: Float,
    val math: Float,
    val hop: Float,
    val hebb: Float,
    val impulse: ImpulseEvent,
    val imageUrl: String? = null
)

/**
 * Fires neuron assemblies together, measures conceptual/math distance,
 * and applies Hebbian plasticity when the CNS "feels" a connection.
 */
class SynapticMotor(
    val graph: ConnectomeGraph,
    val dictionary: DictionaryEngine,
    val dopamine: DopamineSystem
) {
    fun learnPair(left: String, right: String, reason: String): LearningTrace {
        val a = graph.assemblyForToken(left)
        val b = graph.assemblyForToken(right)
        graph.coActivate(a, 1.2f)
        graph.coActivate(b, 1.2f)
        graph.stepImpulse(3)
        val (sem, math) = dictionary.semantic.alignment(
            left + " " + (dictionary.lookup(left)?.meaning ?: left),
            right + " " + (dictionary.lookup(right)?.meaning ?: right)
        )
        val hop = 1f - graph.conceptualHopDistance(a, b)
        val mathMix = 0.6f * math + 0.4f * hop
        val rate = ConnectomeConstants.HEBB_RATE * (0.35f + 0.65f * ((sem + mathMix) * 0.5f))
        val hebb = graph.hebbian(a, b, rate)
        val impulse = dopamine.evaluate(
            graph = graph,
            assemblies = listOf(a, b),
            hebbDelta = hebb,
            semantic = sem,
            math = mathMix,
            reason = reason
        )
        dictionary.remember(left, dictionary.lookup(left)?.meaning ?: "conceito em uso", "sessão")
        dictionary.remember(right, dictionary.lookup(right)?.meaning ?: "conceito em uso", "sessão")
        return LearningTrace(left, right, sem, mathMix, hop, hebb, impulse)
    }

    fun learnUtterance(text: String): List<LearningTrace> {
        val tokens = dictionary.ingestText(text, "fala").map { it.word }.distinct()
        if (tokens.size < 2) {
            val only = tokens.firstOrNull() ?: return emptyList()
            return listOf(learnPair(only, "aprender", "disposição: isolado ainda busca par"))
        }
        val traces = ArrayList<LearningTrace>()
        for (i in 0 until tokens.size - 1) {
            traces += learnPair(tokens[i], tokens[i + 1], "fala: ${text.take(60)}")
        }
        if (tokens.size >= 3) {
            traces += learnPair(tokens.first(), tokens.last(), "arco semântico da frase")
        }
        return traces
    }

    fun learnFromUrl(lesson: UrlLesson): List<LearningTrace> {
        val tokens = dictionary.semantic.tokenize(lesson.excerpt).take(18).toList()
        val traces = ArrayList<LearningTrace>()
        for (i in 0 until tokens.size - 1) {
            traces += learnPair(tokens[i], tokens[i + 1], "url ${lesson.url}")
        }
        return traces
    }
}
