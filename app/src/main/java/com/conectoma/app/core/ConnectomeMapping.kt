package com.conectoma.app.core

import java.util.BitSet
import kotlin.random.Random

/**
 * Functional abstraction layer: translates the Drosophila wiring into
 * operational cognitive modules.
 *
 * Evidence used by the mapping:
 *  - Mushroom body (MBON, MBIN, Kenyon cells, DAN/PAM): associative learning,
 *    value and long-term memory.
 *  - Central complex (CX, CB.FB*, CB.EB*, CB.PB*, CB.NO*): sensory integration,
 *    internal state, navigation and context maintenance.
 *  - Sensory surfaces (optic/sensory/olfactory/mechanosensory): input.
 *  - Descending/motor neurons: output.
 *
 * The mapping does not turn a fly into a human brain. It only defines which
 * real populations of the graph feed each functional module. Cognition still
 * emerges from spike dynamics, not from labels.
 */
class ConnectomeMapping(val connectome: Connectome) {

    private class IntBag(capacity: Int = 1024) {
        var data = IntArray(capacity)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    private val cx = IntBag(9000)
    private val mb = IntBag(2200)
    private val modulatory = IntBag(900)
    private val sensory = IntBag(24000)
    private val motor = IntBag(600)
    private val intrinsic = IntBag(12000)

    /** functional type per neuron (1 byte, not 4) */
    private val typeByIndex = ByteArray(connectome.nodeCount) { TYPE_OTHER.toByte() }

    lateinit var cxCluster: IntArray
        private set
    lateinit var mbCluster: IntArray
        private set

    /** working memory: 32 central-complex neurons */
    val workingMemory = IntArray(32)
    /** context control: 48 mixed neurons */
    val contextNodes = IntArray(48)
    /** semantic association: 64 mushroom-body neurons */
    val associationNodes = IntArray(64)
    /** sensory input: 64 receptors */
    val inputNodes = IntArray(64)
    /** motor output: 32 descending neurons */
    val outputNodes = IntArray(32)

    var cxCount = 0
        private set
    var mbCount = 0
        private set
    var modCount = 0
        private set

    init {
        classify()
        expandMushroomBody()
        buildClusters()
    }

    private fun classify() {
        val neurons = connectome.neurons
        for (i in neurons.indices) {
            val n = neurons[i]
            val ct = n.cellType.lowercase()
            val cc = n.cellClass.lowercase()
            val sc = n.superClass.lowercase()
            val nt = n.topNt.lowercase()
            val flow = n.flow.lowercase()

            val isMb = ct.contains("mbon") || ct.contains("mbin") ||
                ct.contains("kenyon") || ct == "dan" || ct == "pam" ||
                cc.contains("mbon") || cc.contains("kenyon") || cc == "dan"
            val isCx = ct == "cx" || ct.startsWith("cb.fb") || ct.startsWith("cb.eb") ||
                ct.startsWith("cb.pb") || ct.startsWith("cb.no") || ct.startsWith("cb.fbti") ||
                ct.startsWith("cb.fbtf") || ct.contains("fan-shaped") || ct.contains("ellipsoid")
            val isMod = nt.contains("dopamine") || nt.contains("octopamine") ||
                nt.contains("serotonin") || ct == "dan" || ct == "pam" || cc == "dan"
            val isSensory = sc == "sensory" || sc == "optic" || sc.contains("visual") ||
                ct.contains("olfactory") || ct.contains("mechanosensory") ||
                ct.contains("gustatory") || ct.contains("thermosensory") ||
                ct.contains("hygrosensory") || flow.contains("afferent")
            val isMotor = sc == "motor" || sc == "descending" || ct.startsWith("dn") ||
                ct.startsWith("dnp") || ct.startsWith("dng") || ct.startsWith("dna") ||
                flow.contains("efferent")

            when {
                isMb -> {
                    mb.add(i)
                    typeByIndex[i] = TYPE_MB.toByte()
                }
                isCx -> {
                    cx.add(i)
                    typeByIndex[i] = TYPE_CX.toByte()
                }
                isMod -> {
                    modulatory.add(i)
                    typeByIndex[i] = TYPE_MOD.toByte()
                }
                isMotor -> {
                    motor.add(i)
                    typeByIndex[i] = TYPE_MOTOR.toByte()
                }
                isSensory -> {
                    sensory.add(i)
                    typeByIndex[i] = TYPE_SENSORY.toByte()
                }
                flow.contains("intrinsic") -> {
                    intrinsic.add(i)
                    typeByIndex[i] = TYPE_INTRINSIC.toByte()
                }
            }
        }
        cxCount = cx.size
        mbCount = mb.size
        modCount = modulatory.size
    }

    /**
     * The subset has only ~233 nominally MB neurons. The functional mushroom
     * body includes their targets and sources (Kenyon -> MBON, DAN/PAM -> MB).
     * We expand by one synaptic hop to obtain an association population large
     * enough for the semantic engine.
     */
    private fun expandMushroomBody() {
        if (mb.size == 0) return
        val seen = BitSet(connectome.nodeCount)
        val seeds = mb.toArray()
        for (s in seeds) seen.set(s)
        var added = 0
        for (s in seeds) {
            for (e in connectome.outStart(s) until connectome.outEnd(s)) {
                if (added >= 1600) break
                val dst = connectome.target(e)
                if (!seen.get(dst)) {
                    seen.set(dst)
                    mb.add(dst)
                    if (typeByIndex[dst].toInt() == TYPE_OTHER) typeByIndex[dst] = TYPE_MB.toByte()
                    added++
                }
            }
            for (e in connectome.inStart(s) until connectome.inEnd(s)) {
                if (added >= 1600) break
                val src = connectome.source(e)
                if (!seen.get(src)) {
                    seen.set(src)
                    mb.add(src)
                    if (typeByIndex[src].toInt() == TYPE_OTHER) typeByIndex[src] = TYPE_MB.toByte()
                    added++
                }
            }
        }
        mbCluster = mb.toArray()
    }

    private fun buildClusters() {
        cxCluster = if (cx.size >= 32) cx.toArray() else {
            val fallback = IntBag(cx.size + 512)
            for (i in 0 until cx.size) fallback.add(cx.data[i])
            for (i in 0 until intrinsic.size) {
                if (fallback.size >= 512) break
                fallback.add(intrinsic.data[i])
            }
            fallback.toArray()
        }
        if (mb.size < 64) {
            val fallback = IntBag(1024)
            for (i in 0 until mb.size) fallback.add(mb.data[i])
            for (i in 0 until intrinsic.size) {
                if (fallback.size >= 512) break
                fallback.add(intrinsic.data[i])
            }
            mbCluster = fallback.toArray()
        }

        val rng = Random(7919)
        fillFrom(workingMemory, cxCluster, rng)
        fillFrom(associationNodes, mbCluster, rng)
        for (i in contextNodes.indices) {
            contextNodes[i] = when (i % 3) {
                0 -> pick(workingMemory, rng)
                1 -> pick(associationNodes, rng)
                else -> if (intrinsic.size > 0) intrinsic.data[rng.nextInt(intrinsic.size)] else rng.nextInt(connectome.nodeCount)
            }
        }
        val sensoryCluster = if (sensory.size > 0) sensory.toArray() else cxCluster
        fillFrom(inputNodes, sensoryCluster, rng)
        val motorCluster = if (motor.size > 0) motor.toArray() else cxCluster
        fillFrom(outputNodes, motorCluster, rng)
    }

    private fun fillFrom(target: IntArray, source: IntArray, rng: Random) {
        if (source.isEmpty()) {
            for (i in target.indices) target[i] = rng.nextInt(connectome.nodeCount)
            return
        }
        for (i in target.indices) target[i] = source[rng.nextInt(source.size)]
    }

    private fun pick(a: IntArray, rng: Random): Int =
        if (a.isEmpty()) rng.nextInt(connectome.nodeCount) else a[rng.nextInt(a.size)]

    fun typeOf(index: Int): Int =
        if (index in typeByIndex.indices) typeByIndex[index].toInt() else TYPE_OTHER

    fun isCx(index: Int) = typeOf(index) == TYPE_CX
    fun isMb(index: Int) = typeOf(index) == TYPE_MB
    fun isModulatory(index: Int) = typeOf(index) == TYPE_MOD
    fun isSensory(index: Int) = typeOf(index) == TYPE_SENSORY

    fun summary(): String =
        "CX=$cxCount MB=$mbCount mod=$modCount sens=${sensory.size} motor=${motor.size}"

    companion object {
        const val TYPE_OTHER = 0
        const val TYPE_CX = 1
        const val TYPE_MB = 2
        const val TYPE_MOD = 3
        const val TYPE_SENSORY = 4
        const val TYPE_MOTOR = 5
        const val TYPE_INTRINSIC = 6
    }
}
