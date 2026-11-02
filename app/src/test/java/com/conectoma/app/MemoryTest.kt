package com.conectoma.app

import com.conectoma.app.core.MemoryEngine
import com.conectoma.app.core.SemanticEngine
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** A memória episódica precisa guardar o que foi lido e devolver na consulta. */
class MemoryTest {

    companion object {
        private lateinit var semantics: SemanticEngine

        @JvmStatic
        @BeforeClass
        fun load() {
            semantics = SemanticEngine(File("src/main/assets/lexicon.bin").readBytes())
        }
    }

    @Test
    fun remembersAndRecallsLearnedMaterial() {
        val memory = MemoryEngine(semantics)
        val text = """
            Uma sinapse é a junção entre neurônios por onde passa a informação.
            O aprendizado ocorre quando essas conexões se fortalecem com o uso.
            A memória depende da plasticidade sináptica.
        """.trimIndent()
        val concepts = listOf("sinapse", "aprendizado", "memória")
            .map { semantics.idOf(it) }
            .filter { it >= 0 }

        val engram = memory.remember("neurociência", "https://exemplo.test", text, concepts)
        assertTrue("nenhum fato extraído", engram.facts.isNotEmpty())
        assertTrue(memory.size >= 1)
        assertTrue(memory.factCount >= 1)

        val recalled = memory.factsFor(concepts, 3)
        assertTrue("nada lembrado sobre sinapse", recalled.isNotEmpty())
        assertTrue(recalled.first().text.length > 20)

        val blob = memory.serialise()
        val restored = MemoryEngine(semantics)
        restored.restore(blob)
        assertTrue(restored.size >= 1)
        assertTrue(restored.factCount >= 1)
        assertTrue(restored.factsFor(concepts, 3).isNotEmpty())
    }

    @Test
    fun emptyMemoryIsSafe() {
        val memory = MemoryEngine(semantics)
        assertTrue(memory.recall(emptyList(), 3).isEmpty())
        assertTrue(memory.factsFor(listOf(semantics.idOf("sinapse")), 3).isEmpty())
        assertTrue(memory.recentFacts(3).isEmpty())
        memory.restore("")
        assertTrue(memory.size == 0)
    }
}
