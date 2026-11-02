package com.conectoma.app.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The FlyWire FAFB v783 sub-connectome: 34.813 neurons and 3.750.000 synapses.
 *
 * Binary layout (little endian):
 *   magic "CNCT" | u32 version | u32 nodes | u32 edges | u32 offsets[nodes+1]
 *   u16 post[edges] | u8 syn[edges] | u8 da | u8 gaba | u8 ach | u8 glut
 */
class Connectome private constructor(
    val nodeCount: Int,
    val edgeCount: Int,
    private val offsets: IntArray,
    private val post: IntArray,
    private val syn: ByteArray,
    private val da: ByteArray,
    private val gaba: ByteArray,
    private val ach: ByteArray,
    private val glut: ByteArray,
    val neurons: List<Neuron>,
) {
    /** incoming edges (CSR over target neurons) */
    private val inOffsets = IntArray(nodeCount + 1)
    private val inSrc = IntArray(edgeCount)

    /** plastic multiplier per synapse, 1.0 = native wiring strength */
    val plastic = FloatArray(edgeCount) { 1f }

    init {
        for (e in 0 until edgeCount) inOffsets[post[e] + 1]++
        for (i in 0 until nodeCount) inOffsets[i + 1] += inOffsets[i]
        val cursor = inOffsets.copyOf()
        for (i in 0 until nodeCount) {
            for (e in offsets[i] until offsets[i + 1]) {
                val dst = post[e]
                inSrc[cursor[dst]++] = i
            }
        }
    }

    fun outStart(i: Int) = offsets[i]
    fun outEnd(i: Int) = offsets[i + 1]
    fun inStart(i: Int) = inOffsets[i]
    fun inEnd(i: Int) = inOffsets[i + 1]
    fun target(e: Int) = post[e]
    fun source(e: Int) = inSrc[e]
    fun preOf(e: Int): Int {
        var lo = 0
        var hi = nodeCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (offsets[mid] <= e) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** native synapse count, decoded from the log-quantised byte */
    fun synapseCount(e: Int): Float {
        val q = syn[e].toInt() and 0xFF
        return (Math.exp(q / 255.0 * LN_1301) - 1.0).toFloat()
    }

    /** normalised wiring strength 0..1 */
    fun strength(e: Int): Float = (syn[e].toInt() and 0xFF) / 255f
    fun dopamine(e: Int): Float = (da[e].toInt() and 0xFF) / 255f
    fun gabaProb(e: Int): Float = (gaba[e].toInt() and 0xFF) / 255f
    fun achProb(e: Int): Float = (ach[e].toInt() and 0xFF) / 255f
    fun glutProb(e: Int): Float = (glut[e].toInt() and 0xFF) / 255f

    /** inhibitory if GABA dominates the release profile */
    fun isInhibitory(e: Int): Boolean = gabaProb(e) > achProb(e) + glutProb(e)

    fun outDegree(i: Int) = offsets[i + 1] - offsets[i]

    companion object {
        private const val LN_1301 = 7.170888
        private const val MAGIC = 0x54434E43 // "CNCT"

        fun load(context: Context): Connectome {
            val file = ensureCopy(context)
            RandomAccessFile(file, "r").use { raf ->
                val ch: FileChannel = raf.channel
                val buf: ByteBuffer = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                return parse(buf, readNeurons(context))
            }
        }

        /** parses the binary asset; kept framework-free so it can run on the JVM */
        fun parse(buf: ByteBuffer, neurons: List<Neuron>): Connectome {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            require(buf.int == MAGIC) { "assinatura inválida" }
            require(buf.int == 1) { "versão não suportada" }
            val nodes = buf.int
            val edges = buf.int
            val offsets = IntArray(nodes + 1)
            buf.asIntBuffer().get(offsets)
            buf.position(buf.position() + (nodes + 1) * 4)
            val post = IntArray(edges)
            val shorts = buf.asShortBuffer()
            for (i in 0 until edges) post[i] = shorts.get(i).toInt() and 0xFFFF
            buf.position(buf.position() + edges * 2)
            fun bytes(): ByteArray {
                val a = ByteArray(edges)
                buf.get(a)
                return a
            }
            val syn = bytes()
            val da = bytes()
            val gaba = bytes()
            val ach = bytes()
            val glut = bytes()
            return Connectome(nodes, edges, offsets, post, syn, da, gaba, ach, glut, neurons)
        }

        private fun ensureCopy(context: Context): File {
            val dir = File(context.filesDir, "connectome")
            dir.mkdirs()
            val target = File(dir, "connectome.bin")
            if (target.exists() && target.length() > 0) return target
            context.assets.open("connectome.bin").use { input ->
                BufferedInputStream(input, 1 shl 16).use { bis ->
                    target.outputStream().use { out -> bis.copyTo(out, 1 shl 16) }
                }
            }
            return target
        }

        private fun readNeurons(context: Context): List<Neuron> {
            val out = ArrayList<Neuron>(34816)
            context.assets.open("nodes.tsv").bufferedReader(Charsets.UTF_8).use { r ->
                r.readLine()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty()) continue
                    val p = line.split('\t')
                    if (p.size < 8) continue
                    out.add(
                        Neuron(
                            index = p[0].toInt(),
                            rootId = p[1].toLong(),
                            superClass = p[2],
                            cellClass = p[3],
                            cellType = p[4],
                            topNt = p[5],
                            side = p[6],
                            flow = p[7],
                        )
                    )
                }
            }
            return out
        }
    }
}

data class Neuron(
    val index: Int,
    val rootId: Long,
    val superClass: String,
    val cellClass: String,
    val cellType: String,
    val topNt: String,
    val side: String,
    val flow: String,
)
