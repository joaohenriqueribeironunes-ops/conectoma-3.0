package com.conectoma.app.core

import kotlin.math.exp
import kotlin.random.Random

/**
 * Leaky integrate-and-fire simulation over the real FlyWire wiring.
 *
 * Every synapse carries the measured neurotransmitter profile, so inhibition and
 * excitation come from the connectome itself. Plastic weights (STDP) multiply the
 * native wiring strength; phasic dopamine gates the learning rate.
 */
class SpikingNetwork(val connectome: Connectome) {
    val n = connectome.nodeCount
    private val edgeCount = connectome.edgeCount

    val v = FloatArray(n) { REST }
    val current = FloatArray(n)
    val activity = FloatArray(n)
    val lastSpike = IntArray(n) { -1000 }
    val spikeBuffer = IntArray(8192)
    var spikeCount = 0
        private set
    var step = 0
        private set

    /** pre-synaptic eligibility trace used by STDP */
    private val preTrace = FloatArray(n)
    private val postRecent = FloatArray(n)

    var excGain = 2.6f
    var inhGain = 3.4f
    var dopamine = 0f
    var learningRate = 0.08f
    var plasticityEnabled = true

    var totalSpikes = 0L
        private set
    var lastPotentiation = 0
        private set

    fun inject(node: Int, amount: Float) {
        if (node in 0 until n) current[node] += amount
    }

    fun inject(nodes: IntArray, amount: Float) {
        for (node in nodes) current[node] += amount
    }

    fun reset() {
        v.fill(REST)
        current.fill(0f)
        activity.fill(0f)
        lastSpike.fill(-1000)
        step = 0
    }

    fun simulate(steps: Int = 12, dt: Float = 1f) {
        repeat(steps) { step(dt) }
    }

    fun step(dt: Float = 1f) {
        step++
        spikeCount = 0
        val decay = exp(-dt / TAU)
        val traceDecay = exp(-dt / TAU_TRACE)
        for (i in 0 until n) {
            if (preTrace[i] > 0.001f) preTrace[i] *= traceDecay else preTrace[i] = 0f
            if (postRecent[i] > 0.001f) postRecent[i] *= traceDecay else postRecent[i] = 0f
            activity[i] *= 0.92f
        }
        for (i in 0 until n) {
            var vi = REST + (v[i] - REST) * decay + current[i]
            current[i] = 0f
            if (vi >= THRESHOLD && step - lastSpike[i] > REFRACTORY) {
                v[i] = RESET
                lastSpike[i] = step
                activity[i] = 1f
                preTrace[i] = 1f
                postRecent[i] = 1f
                if (spikeCount < spikeBuffer.size) spikeBuffer[spikeCount++] = i
            } else {
                if (vi < REST) vi = REST
                v[i] = vi
            }
        }
        if (spikeCount == 0) return
        totalSpikes += spikeCount

        // propagate into the targets' input current and apply three-factor STDP
        val da = dopamine
        for (s in 0 until spikeCount) {
            val src = spikeBuffer[s]
            val start = connectome.outStart(src)
            val end = connectome.outEnd(src)
            for (e in start until end) {
                val dst = connectome.target(e)
                val w = connectome.strength(e) * connectome.plastic[e]
                if (connectome.isInhibitory(e)) {
                    current[dst] -= inhGain * w
                } else {
                    current[dst] += excGain * w * (0.85f + 0.3f * connectome.achProb(e))
                }
                if (plasticityEnabled && postRecent[dst] > 0.35f) {
                    // pre fires before a post that just fired: long-term depression
                    val dw = -learningRate * 0.45f * (1f + da)
                    adjust(e, dw)
                }
            }
            if (plasticityEnabled) {
                val inStart = connectome.inStart(src)
                val inEnd = connectome.inEnd(src)
                var potentiated = 0
                for (e in inStart until inEnd) {
                    val pre = connectome.source(e)
                    val trace = preTrace[pre]
                    if (trace > 0.25f) {
                        val daProb = connectome.dopamine(e)
                        // three-factor rule: eligibility x dopamine (0.35 base rate)
                        val dw = learningRate * trace * (0.35f + 2.2f * da) * (0.6f + daProb)
                        adjust(e, dw)
                        potentiated++
                    }
                }
                if (potentiated > 0) lastPotentiation = potentiated
            }
        }
    }

    private fun adjust(edge: Int, delta: Float) {
        val p = connectome.plastic[edge] + delta
        connectome.plastic[edge] = if (p < 0.15f) 0.15f else if (p > 4.5f) 4.5f else p
    }

    /** homeostatic rescaling keeps the network from saturating or dying */
    fun homeostasis(stepCount: Int) {
        if (stepCount % 400 != 0) return
        var sum = 0.0
        for (p in connectome.plastic) sum += p
        val mean = (sum / edgeCount).toFloat()
        val target = 1.02f
        if (mean > target * 1.35f || mean < target * 0.72f) {
            val factor = target / mean
            for (i in connectome.plastic.indices) connectome.plastic[i] *= factor
        }
    }

    fun meanActivity(): Float {
        var s = 0f
        for (a in activity) s += a
        return s / n
    }

    /** spark a random sub-population, used by the autonomous learner at rest */
    fun spontaneous(rng: Random, count: Int) {
        repeat(count) {
            val node = rng.nextInt(n)
            current[node] += 3.5f
        }
    }

    companion object {
        const val REST = -65f
        const val RESET = -70f
        const val THRESHOLD = -52f
        const val TAU = 18f
        const val TAU_TRACE = 26f
        const val REFRACTORY = 2
    }
}

/** Phasic dopamine: a reward-prediction-error signal that gates plasticity. */
class DopamineSystem {
    @Volatile
    var level: Float = 0.18f
        private set
    @Volatile
    var phasic: Float = 0f
        private set
    var baseline = 0.18f
    var expectedReward = 0.35f
    var totalBursts = 0
        private set

    fun restoreBursts(count: Int) {
        totalBursts = count
    }
    var lastCause: String = "repouso"
        private set

    /** reward in 0..1; returns the prediction error that was injected */
    fun reward(value: Float, cause: String): Float {
        val rpe = value - expectedReward
        expectedReward += 0.06f * rpe
        if (rpe > 0f) {
            phasic = (0.25f + rpe).coerceAtMost(1f)
            totalBursts++
            lastCause = cause
        } else {
            phasic = (rpe * 0.4f).coerceAtLeast(-0.4f)
        }
        level = (baseline + 0.6f * phasic).coerceIn(0f, 1f)
        return rpe
    }

    fun tick() {
        level += (baseline - level) * 0.04f
        phasic *= 0.93f
    }
}
