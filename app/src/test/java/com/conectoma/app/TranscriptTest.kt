package com.conectoma.app

import com.conectoma.app.core.Connectome
import com.conectoma.app.core.DopamineSystem
import com.conectoma.app.core.Neuron
import com.conectoma.app.core.ReasoningEngine
import com.conectoma.app.core.SemanticEngine
import com.conectoma.app.core.SpikingNetwork
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

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
        val engine = ReasoningEngine(connectome, network, semantics, DopamineSystem())

        val prompts = listOf(
            "o que é sinapse?",
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
            println("   [dopamina=${r.dopamine} reward=${r.reward} spikes=${r.spikes} links=${r.newLinks} ${r.elapsedMs}ms]")
        }
    }
}
