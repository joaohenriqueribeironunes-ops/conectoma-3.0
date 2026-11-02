package com.conectoma.app

import com.conectoma.app.core.AutonomousEngine
import com.conectoma.app.core.ConsolidationEngine
import com.conectoma.app.core.SemanticEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Consolidação (PDF/URL → conhecimento antigo) e o motor autônomo. */
class MindTest {

    companion object {
        private lateinit var semantics: SemanticEngine

        @JvmStatic
        @BeforeClass
        fun load() {
            semantics = SemanticEngine(File("src/main/assets/lexicon.bin").readBytes())
        }
    }

    @Test
    fun consolidatesDocumentIntoExistingKnowledge() {
        val doc = listOf("sinapse", "memória", "aprendizado", "dopamina", "neurônio", "cérebro")
            .map { semantics.idOf(it) }
            .filter { it >= 0 }
        assertTrue("conceitos de teste ausentes", doc.size >= 5)

        val engine = ConsolidationEngine(semantics)
        val report = engine.consolidate(doc)
        assertTrue("nada foi ancorado: $report", report.anchors > 0)
        assertTrue("relatório sem resumo", report.summary.isNotBlank())

        // pelo menos um conceito do documento ganhou ligação com algo de fora dele
        val anchoredOutside = doc.any { c ->
            semantics.nearestTo(c, 6, exclude = doc.toSet())
                .any { (other, _) -> semantics.linkWeight(c, other) > 0f }
        }
        assertTrue("o documento ficou isolado do resto", anchoredOutside)
    }

    @Test
    fun distantTermsInsideTheSameDocumentGetBridged() {
        val a = semantics.idOf("sinapse")
        val b = semantics.idOf("futebol")
        val c = semantics.idOf("banana")
        assertTrue(a >= 0 && b >= 0 && c >= 0)
        val engine = ConsolidationEngine(semantics)
        val report = engine.consolidate(listOf(a, b, c))
        assertTrue("não criou pontes: $report", report.bridges > 0)
        assertTrue(semantics.linkWeight(a, b) > 0f || semantics.linkWeight(a, c) > 0f)
    }

    @Test
    fun emptyInputIsHandled() {
        val report = ConsolidationEngine(semantics).consolidate(emptyList())
        assertEquals(0, report.anchors)
        assertTrue(report.summary.isNotBlank())
    }

    @Test
    fun autonomyConsolidatesWhenMaterialIsPending() {
        val engine = AutonomousEngine(semantics)
        val task = engine.nextTask(pendingConsolidation = true, networkAvailable = true, rng = Random(1))
        assertTrue("esperava consolidação, veio $task", task is AutonomousEngine.Task.Consolidate)
    }

    @Test
    fun autonomyPicksARealKnowledgeGap() {
        val fresh = semantics.idOrCreate("criptoentropia", listOf(semantics.idOf("entropia")))
        assertTrue("palavra nova deveria ser uma lacuna", semantics.knowledgeGaps().contains(fresh))

        val engine = AutonomousEngine(semantics)
        val task = engine.nextTask(pendingConsolidation = false, networkAvailable = true, rng = Random(2))
        assertTrue("esperava leitura de lacuna, veio $task", task is AutonomousEngine.Task.ReadGap)
        val read = task as AutonomousEngine.Task.ReadGap
        assertTrue("palavra vazia", read.word.isNotBlank())
        assertTrue("palavra não está entre as lacunas", semantics.knowledgeGaps(limit = 900).contains(read.id))
    }

    @Test
    fun autonomyBridgesWhenThereIsNoNetwork() {
        val engine = AutonomousEngine(semantics)
        val task = engine.nextTask(pendingConsolidation = false, networkAvailable = false, rng = Random(3))
        assertTrue("esperava ponte, veio $task", task is AutonomousEngine.Task.Bridge)
        val bridge = task as AutonomousEngine.Task.Bridge
        assertTrue(bridge.a in 0 until semantics.baseCount)
        assertTrue(bridge.b in 0 until semantics.baseCount)
    }

    @Test
    fun autonomyCountersAccumulateAndRestore() {
        val engine = AutonomousEngine(semantics)
        engine.record(AutonomousEngine.Task.Consolidate("teste"))
        engine.record(AutonomousEngine.Task.Bridge(0, 1))
        engine.record(AutonomousEngine.Task.ReadGap(0, "teste"))
        engine.recordInsights(2)
        assertEquals(3, engine.tasks)
        assertEquals(1, engine.reads)
        assertEquals(1, engine.bridges)
        assertEquals(1, engine.consolidations)
        assertEquals(2, engine.insights)

        val restored = AutonomousEngine(semantics)
        restored.restore(10, 4, 3, 2, 7)
        assertEquals(10, restored.tasks)
        assertEquals(7, restored.insights)
        assertNotNull(restored.lastLabel)
    }
}
