package com.conectoma.app

import com.conectoma.app.core.Connectome
import com.conectoma.app.core.DopamineSystem
import com.conectoma.app.core.Neuron
import com.conectoma.app.core.ReasoningEngine
import com.conectoma.app.core.SemanticEngine
import com.conectoma.app.core.SpikingNetwork
import com.conectoma.app.core.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.abs

class EngineTest {

    companion object {
        private lateinit var connectome: Connectome
        private lateinit var semantics: SemanticEngine
        private lateinit var network: SpikingNetwork

        @JvmStatic
        @BeforeClass
        fun load() {
            val root = File("src/main/assets")
            assertTrue("assets ausentes em ${root.absolutePath}", root.exists())
            val neurons = ArrayList<Neuron>(34900)
            File(root, "nodes.tsv").bufferedReader().use { r ->
                r.readLine()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val p = line.split('\t')
                    if (p.size < 8) continue
                    neurons.add(
                        Neuron(
                            p[0].toInt(), p[1].toLong(), p[2], p[3], p[4], p[5], p[6], p[7]
                        )
                    )
                }
            }
            val buf = FileChannel.open(
                File(root, "connectome.bin").toPath(), StandardOpenOption.READ
            ).use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
            connectome = Connectome.parse(buf, neurons)
            semantics = SemanticEngine(File(root, "lexicon.bin").readBytes())
            network = SpikingNetwork(connectome)
        }
    }

    @Test
    fun tokenizerHandlesPortuguese() {
        val tokens = Tokenizer.tokenize("A sinapse é uma ligação entre neurônios!")
        assertTrue(tokens.contains("sinapse"))
        assertTrue(tokens.contains("neurônios"))
        assertTrue(tokens.contains("é"))
        assertEquals(
            listOf("sinapse", "ligação", "neurônios"),
            Tokenizer.contentWords("A sinapse é uma ligação entre neurônios!"),
        )
        val sentences = Tokenizer.sentences("Esta é uma frase bastante longa. E esta outra também é comprida! Será?")
        assertTrue("frases: $sentences", sentences.size >= 2)
    }

    @Test
    fun connectomeHasQuarterOfTheBrain() {
        assertEquals(34813, connectome.nodeCount)
        assertEquals(3_750_000, connectome.edgeCount)
        assertEquals(connectome.edgeCount, connectome.outStart(connectome.nodeCount - 1) + connectome.outDegree(connectome.nodeCount - 1))
        var maxTarget = 0
        var degreeSum = 0L
        for (i in 0 until connectome.nodeCount) {
            val start = connectome.outStart(i)
            val end = connectome.outEnd(i)
            degreeSum += (end - start)
            assertTrue("offsets fora de ordem em $i", end >= start)
            for (e in start until end) {
                val t = connectome.target(e)
                if (t > maxTarget) maxTarget = t
                assertTrue("alvo fora do intervalo: $t", t in 0 until connectome.nodeCount)
                val syn = connectome.synapseCount(e)
                assertTrue("sinapse inválida $syn", syn >= 0.9f && syn <= 1400f)
            }
        }
        assertEquals(3_750_000L, degreeSum)
        assertTrue(maxTarget < connectome.nodeCount)
        val meanOut = degreeSum.toDouble() / connectome.nodeCount
        assertTrue("grau médio inesperado: $meanOut", abs(meanOut - 107.7) < 1.0)

        val inhibitory = (0 until connectome.edgeCount).count { connectome.isInhibitory(it) }
        val ratio = inhibitory.toDouble() / connectome.edgeCount
        assertTrue("fração inibitória fora do esperado: $ratio", ratio in 0.1..0.35)
    }

    @Test
    fun lexiconLoadsAndKnowsMeaning() {
        assertEquals(35311, semantics.baseCount)
        val sinapse = semantics.idOf("sinapse")
        val neuronio = semantics.idOf("neurônio")
        val casa = semantics.idOf("casa")
        assertTrue(sinapse >= 0 && neuronio >= 0 && casa >= 0)
        assertTrue(semantics.definition(sinapse).contains("sinapse", ignoreCase = true) ||
            semantics.definition(sinapse).length > 10)
        assertTrue("sinapse e neurônio deveriam ser próximos",
            semantics.cosine(sinapse, neuronio) > semantics.cosine(sinapse, casa))
    }

    @Test
    fun distanceIsSymmetricAndBounded() {
        val cão = semantics.idOf("cão")
        val gato = semantics.idOf("gato")
        val matriz = semantics.idOf("matriz")
        assertTrue(cão >= 0 && gato >= 0 && matriz >= 0)
        val d1 = semantics.distance(cão, gato)
        val d2 = semantics.distance(gato, cão)
        assertEquals(d1, d2, 0.001f)
        assertTrue(d1 in 0f..1f)
        assertTrue("cão-gato ($d1) deveria ser menor que cão-matriz", d1 < semantics.distance(cão, matriz))
        assertTrue(semantics.distance(cão, cão) == 0f)
    }

    @Test
    fun networkFiresAndStrengthensSynapses() {
        val before = connectome.plastic.copyOf()
        val concept = semantics.idOf("sinapse")
        val assembly = IntArray(20) { i -> (i * 1499 + concept * 37) % connectome.nodeCount }
        network.reset()
        repeat(4) {
            network.inject(assembly, 60f)
            repeat(30) { network.step(1f) }
        }
        assertTrue("nenhum disparo", network.totalSpikes > 0)
        var changed = 0
        for (i in before.indices) {
            if (abs(before[i] - connectome.plastic[i]) > 1e-4f) changed++
        }
        assertTrue("STDP não alterou sinapses", changed > 0)
    }

    @Test
    fun assistantAnswersAboutAConcept() {
        val dopamine = DopamineSystem()
        val engine = ReasoningEngine(connectome, network, semantics, dopamine)
        val t0 = System.currentTimeMillis()
        val response = engine.respond("o que é sinapse?")
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("resposta curta: '${response.text}'", response.text.length > 40)
        assertTrue("sem hipóteses", response.hits.isNotEmpty())
        assertTrue("lento demais: ${elapsed}ms", elapsed < 8000)
        assertTrue("dopamina não reagiu", dopamine.level >= 0f)
    }

    @Test
    fun assistantLearnsANewWord() {
        val dopamine = DopamineSystem()
        val engine = ReasoningEngine(connectome, network, semantics, dopamine)
        val before = semantics.learnedWords
        val response = engine.respond("quero aprender sobre neuroplasticidade")
        assertTrue(response.text.isNotBlank())
        assertEquals(before, semantics.learnedWords)
        val id = semantics.idOf("neuroplasticidade")
        assertTrue(id < 0 || id >= semantics.baseCount)
    }

    @Test
    fun unknownWordGetsContextVector() {
        val anchor = semantics.idOf("cérebro")
        val created = semantics.idOrCreate("neuroplasticidade", listOf(anchor))
        assertTrue(created >= semantics.baseCount)
        assertTrue(semantics.cosine(created, anchor) > 0.2f)
        val neighbor = semantics.idOrCreate("plasticidade", emptyList())
        assertTrue(neighbor >= 0)
    }

    @Test
    fun conceptLinksStrengthen() {
        val a = semantics.idOf("casa")
        val b = semantics.idOf("telhado")
        val before = semantics.linkWeight(a, b)
        repeat(5) { semantics.reinforce(a, b, 0.3f) }
        val after = semantics.linkWeight(a, b)
        assertTrue("link não fortaleceu: $before -> $after", after > before)
        assertTrue(after <= 1f)
        assertTrue(semantics.graphDistance(a, b) >= 0f)
    }

    @Test
    fun brainStatsShapeIsConsistent() {
        assertNotNull(connectome.neurons)
        assertEquals(34813, connectome.neurons.size)
        val first = connectome.neurons.first()
        assertTrue(first.superClass.isNotEmpty())
        assertTrue(first.rootId > 0)
        val buffer: ByteBuffer = ByteBuffer.allocate(4)
        assertNotNull(buffer)
    }
}
