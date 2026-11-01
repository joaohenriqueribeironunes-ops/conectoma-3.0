package app.conectoma.v3.engine

import java.util.Random
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compact connectome: 3.75M synapses as parallel arrays (no per-synapse objects).
 * Topology is a seeded small-world / rich-club graph. Plastic weights live in [weights].
 */
class ConnectomeGraph(
    val neuronCount: Int = ConnectomeConstants.NEURONS,
    val synapseCount: Int = ConnectomeConstants.SYNAPSES,
    private val seed: Long = ConnectomeConstants.GRAPH_SEED
) {
    lateinit var src: IntArray
        private set
    lateinit var dst: IntArray
        private set
    lateinit var weights: FloatArray
        private set
    lateinit var transmitter: ByteArray
        private set
    val voltage = FloatArray(neuronCount)
    val dopamine = FloatArray(neuronCount)
    @Volatile var generated = false
        private set
    @Volatile var generatedCount = 0
        private set

    private val outStart = IntArray(neuronCount + 1)

    fun generate(onProgress: (Int) -> Unit = {}) {
        src = IntArray(synapseCount)
        dst = IntArray(synapseCount)
        weights = FloatArray(synapseCount)
        transmitter = ByteArray(synapseCount)
        val rng = Random(seed)
        val rich = (neuronCount * ConnectomeConstants.RICH_CLUB_FRACTION).toInt()
        val n = synapseCount
        var i = 0
        while (i < n) {
            val srcId = if (rng.nextFloat() < 0.55f) rng.nextInt(rich) else rng.nextInt(neuronCount)
            val local = rng.nextFloat() < ConnectomeConstants.LOCAL_PROBABILITY
            val span = if (local) ConnectomeConstants.LOCAL_SPAN else neuronCount
            var dstId = (srcId + 1 + rng.nextInt(span)) % neuronCount
            if (dstId == srcId) dstId = (dstId + 1) % neuronCount
            src[i] = srcId
            dst[i] = dstId
            val base = 0.08f + rng.nextFloat() * 0.35f
            weights[i] = base
            transmitter[i] = when {
                NeuronClass.ofNeuron(srcId) == NeuronClass.DOPAMINERGIC -> Transmitter.DA.ordinal.toByte()
                rng.nextFloat() < 0.12f -> Transmitter.GABA.ordinal.toByte()
                rng.nextFloat() < 0.10f -> Transmitter.GLUT.ordinal.toByte()
                else -> Transmitter.ACH.ordinal.toByte()
            }
            i++
            if (i % 250_000 == 0) {
                generatedCount = i
                onProgress(i)
            }
        }
        generatedCount = n
        indexOutgoing()
        generated = true
    }

    private fun indexOutgoing() {
        val counts = IntArray(neuronCount)
        for (i in 0 until synapseCount) counts[src[i]]++
        outStart[0] = 0
        for (n in 0 until neuronCount) outStart[n + 1] = outStart[n] + counts[n]
        val placed = IntArray(neuronCount)
        val orderSrc = IntArray(synapseCount)
        val orderDst = IntArray(synapseCount)
        val orderW = FloatArray(synapseCount)
        val orderT = ByteArray(synapseCount)
        for (i in 0 until synapseCount) {
            val s = src[i]
            val pos = outStart[s] + placed[s]
            placed[s]++
            orderSrc[pos] = s
            orderDst[pos] = dst[i]
            orderW[pos] = weights[i]
            orderT[pos] = transmitter[i]
        }
        System.arraycopy(orderSrc, 0, src, 0, synapseCount)
        System.arraycopy(orderDst, 0, dst, 0, synapseCount)
        System.arraycopy(orderW, 0, weights, 0, synapseCount)
        System.arraycopy(orderT, 0, transmitter, 0, synapseCount)
    }

    fun assemblyForToken(token: String, size: Int = ConnectomeConstants.MAX_ASSEMBLY): IntArray {
        val h = token.lowercase().hashCode().toLong() and 0x7fffffff
        val start = (h % (neuronCount - size).toLong()).toInt()
        return IntArray(size) { idx ->
            val kenyonBias = 2_400 + ((start + idx * 97) % 5_800)
            if (idx % 3 == 0) kenyonBias else (start + idx * 17) % neuronCount
        }
    }

    fun coActivate(neurons: IntArray, impulse: Float = 1f) {
        for (id in neurons) {
            voltage[id] = (voltage[id] + impulse).coerceAtMost(4f)
        }
    }

    fun stepImpulse(spread: Int = 2) {
        repeat(spread) {
            val snapshot = voltage.copyOf()
            for (n in snapshot.indices) {
                if (snapshot[n] < 0.35f) continue
                val from = outStart[n]
                val to = outStart[n + 1]
                var s = from
                while (s < to) {
                    val w = weights[s]
                    val t = Transmitter.ofIndex(transmitter[s].toInt())
                    val sign = if (t.excitatory) 1f else -0.35f
                    val extra = if (t == Transmitter.DA) dopamine[n] * 0.4f else 0f
                    val d = dst[s]
                    voltage[d] = (voltage[d] + snapshot[n] * w * sign * 0.18f + extra).coerceIn(-1.5f, 4f)
                    s++
                }
            }
            for (i in voltage.indices) voltage[i] *= 0.72f
        }
    }

    fun hebbian(a: IntArray, b: IntArray, rate: Float = ConnectomeConstants.HEBB_RATE): Float {
        val setB = HashSet<Int>(b.size * 2)
        b.forEach { setB.add(it) }
        var delta = 0f
        var touched = 0
        for (n in a) {
            val from = outStart[n]
            val to = outStart[n + 1]
            var s = from
            while (s < to) {
                if (setB.contains(dst[s]) || voltage[dst[s]] > 0.4f) {
                    val before = weights[s]
                    val dw = rate * (1f - before)
                    weights[s] = (before + dw).coerceAtMost(1f)
                    delta += dw
                    touched++
                }
                s++
            }
        }
        return if (touched == 0) 0f else delta / sqrt(touched.toFloat())
    }

    fun decay() {
        for (i in weights.indices) {
            weights[i] = (weights[i] - ConnectomeConstants.DECAY * (weights[i] - 0.12f)).coerceAtLeast(0.02f)
        }
        for (i in dopamine.indices) dopamine[i] *= 0.91f
        for (i in voltage.indices) voltage[i] *= 0.85f
    }

    fun meanWeight(): Float {
        if (!generated) return 0f
        var s = 0.0
        for (w in weights) s += w
        return (s / weights.size).toFloat()
    }

    fun activeCount(threshold: Float = 0.45f): Int {
        var c = 0
        for (v in voltage) if (v >= threshold) c++
        return c
    }

    fun conceptualHopDistance(a: IntArray, b: IntArray, maxDepth: Int = 4): Float {
        val goal = HashSet<Int>(b.size * 2)
        b.forEach { goal.add(it) }
        val seen = HashSet<Int>()
        var frontier = a.toMutableList()
        frontier.forEach { seen.add(it) }
        var depth = 0
        while (depth <= maxDepth && frontier.isNotEmpty()) {
            if (frontier.any { goal.contains(it) }) {
                return depth.toFloat() / maxDepth
            }
            val next = ArrayList<Int>()
            for (n in frontier) {
                val from = outStart[n]
                val to = min(outStart[n + 1], from + 12)
                var s = from
                while (s < to) {
                    val d = dst[s]
                    if (seen.add(d)) next.add(d)
                    s++
                }
            }
            frontier = next
            depth++
        }
        return 1f
    }

    fun sigmoid(x: Float): Float = (1f / (1f + exp(-x))).toFloat()
}
