package com.conectoma.app

import com.conectoma.app.core.CognitionEngine
import com.conectoma.app.core.Connectome
import com.conectoma.app.core.ConnectomeMapping
import com.conectoma.app.core.DopamineSystem
import com.conectoma.app.core.MemoryEngine
import com.conectoma.app.core.Neuron
import com.conectoma.app.core.ReasoningEngine
import com.conectoma.app.core.SemanticEngine
import com.conectoma.app.core.SpikingNetwork
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/** Prints a transcript so the answers can be reviewed without a device. */
class TranscriptTest {

    @Test
    fun printTranscript() {
        val root = File("src/main/assets")
        val neurons = ArrayList<Neuron>()
        File(root, "nodes.tsv").bufferedReader().use { r ->
            r.readLine()
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val p = line.split('\t')
                if (p.size < 8) continue
                neurons.add(Neuron(p[0].toInt(), p[1].toLong(), p[2], p[3], p[4], p[5], p[6], p[7]))
            }
        }
        val buf = FileChannel.open(File(root, "connectome.bin").toPath(), StandardOpenOption.READ)
            .use { ch -> ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
        val connectome = Connectome.parse(buf, neurons)
        val semantics = SemanticEngine(File(root, "lexicon.bin").readBytes())
        val network = SpikingNetwork(connectome)
        val memory = MemoryEngine(semantics)
        val concepts = listOf("sinapse", "neurônio", "aprendizado", "memória", "dopamina")
            .map { semantics.idOf(it) }
            .filter { it >= 0 }
        memory.remember(
            "aula de neurociência",
            "https://exemplo.test/neuro",
            "Uma sinapse é o ponto de contato entre dois neurônios por onde o impulso nervoso passa. " +
                "O aprendizado fortalece essas conexões e a memória depende da plasticidade sináptica. " +
                "A dopamina sinaliza recompensa e modula a força das sinapses.",
            concepts,
        )
        val dopamine = DopamineSystem()
        val mapping = ConnectomeMapping(connectome)
        val cognition = CognitionEngine(mapping, network, semantics, dopamine, memory)
        val engine = ReasoningEngine(connectome, network, semantics, dopamine, memory, cognition)
        println("MAPA FUNCIONAL: ${mapping.summary()}")

        val prompts = listOf(
            "o que é sinapse?",
            "como a memória se liga ao aprendizado?",
            "o que é entropia?",
            "o que é amor?",
            "me fale sobre o cérebro",
            "aprender é importante",
            "como a música se liga à matemática?",
            "quem descobriu o brasil?",
        )
        for (p in prompts) {
            val r = engine.respond(p)
            println("=================================================")
            println("USUÁRIO: $p")
            println("CONECTOMA: ${r.text}")
            println(
                "   [dopamina=${r.dopamine} reward=${r.reward} spikes=${r.spikes} links=${r.newLinks} " +
                    "fatos=${r.factsUsed} cobertura=${(r.coverage * 100).toInt()}% " +
                    "varredura=${r.synapsesSwept} ${r.elapsedMs}ms]"
            )
        }
        for (seed in 0 until 3) {
            val said = engine.spontaneous(Random(seed.toLong() * 7919), concepts)
            println("-------------------------------------------------")
            println("ESPONTÂNEO [${said?.kind}]: ${said?.text}")
        }
    }
}
