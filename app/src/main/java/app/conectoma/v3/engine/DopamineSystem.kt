package app.conectoma.v3.engine

data class ImpulseEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val reason: String,
    val dopamine: Float,
    val semantic: Float,
    val math: Float,
    val understood: Boolean
)

class DopamineSystem {
    val history = ArrayDeque<ImpulseEvent>(64)
    @Volatile var lastPulse: ImpulseEvent? = null
        private set
    @Volatile var totalImpulses: Long = 0
        private set
    @Volatile var understoodCount: Long = 0
        private set

    fun evaluate(
        graph: ConnectomeGraph,
        assemblies: List<IntArray>,
        hebbDelta: Float,
        semantic: Float,
        math: Float,
        reason: String
    ): ImpulseEvent {
        val understood = semantic >= ConnectomeConstants.UNDERSTAND_SEMANTIC &&
            math >= ConnectomeConstants.UNDERSTAND_MATH &&
            hebbDelta >= ConnectomeConstants.DOPAMINE_THRESHOLD
        val da = if (understood) {
            (0.55f + 0.45f * graph.sigmoid(hebbDelta * 8f)).coerceAtMost(1f)
        } else {
            (hebbDelta * 0.35f).coerceIn(0f, 0.35f)
        }
        for (asm in assemblies) {
            for (id in asm) {
                if (NeuronClass.ofNeuron(id) == NeuronClass.DOPAMINERGIC || understood) {
                    graph.dopamine[id] = (graph.dopamine[id] + da).coerceAtMost(1.8f)
                }
            }
        }
        val event = ImpulseEvent(
            reason = reason,
            dopamine = da,
            semantic = semantic,
            math = math,
            understood = understood
        )
        lastPulse = event
        totalImpulses++
        if (understood) understoodCount++
        if (history.size >= 50) history.removeFirst()
        history.addLast(event)
        return event
    }
}
