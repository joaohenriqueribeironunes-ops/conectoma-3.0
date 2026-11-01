package app.conectoma.v3.engine

/**
 * Scale grounded in the FlyWire adult Drosophila connectome
 * (Dorkenwald et al., Nature 2024): 139,255 neurons and ~54.5M chemical synapses.
 * This runtime uses one quarter of those neurons and 3.75M directed synaptic edges —
 * a sparse mobile graph in the same statistical family (rich-club, local clustering).
 */
object ConnectomeConstants {
    const val FLYWIRE_NEURONS = 139_255
    const val NEURONS = FLYWIRE_NEURONS / 4 // 34_813
    const val SYNAPSES = 3_750_000
    const val RICH_CLUB_FRACTION = 0.30f
    const val LOCAL_SPAN = 900
    const val LOCAL_PROBABILITY = 0.71f
    const val GRAPH_SEED = 2024_0758L
    const val HEBB_RATE = 0.08f
    const val DECAY = 0.0015f
    const val UNDERSTAND_SEMANTIC = 0.58f
    const val UNDERSTAND_MATH = 0.50f
    const val DOPAMINE_THRESHOLD = 0.12f
    const val EMBED_DIM = 64
    const val MAX_ASSEMBLY = 48
    const val AUTONOMOUS_INTERVAL_MIN = 25L
}
