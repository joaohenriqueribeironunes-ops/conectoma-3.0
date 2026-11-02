package com.conectoma.app.core

import kotlin.random.Random

/**
 * Motor autônomo: decide sozinho o que fazer em seguida, sem depender do usuário.
 *
 * A política é simples de propósito e roda em ciclo:
 *  1. **consolidar** — se entrou material novo, costura no que já sabe;
 *  2. **estudar lacuna** — pega uma palavra frequente que ele conhece de nome mas
 *     não sabe o significado e vai ler sobre ela (Wikipédia);
 *  3. **aproximar** — sem material novo nem rede, tenta ligar dois conceitos
 *     distantes que ainda não se falam.
 *
 * O ponto que importa: a pauta não é uma lista fixa, ela sai do estado atual do
 * conhecimento — quanto mais ele aprende, mais as lacunas mudam.
 */
class AutonomousEngine(private val semantics: SemanticEngine) {

    sealed class Task(val label: String) {
        class Consolidate(val reason: String) : Task("consolidar o que entrou")
        class ReadGap(val id: Int, val word: String) : Task("estudar “$word”")
        class Bridge(val a: Int, val b: Int) : Task("aproximar dois conceitos")
    }

    var tasks = 0
        private set
    var reads = 0
        private set
    var bridges = 0
        private set
    var consolidations = 0
        private set
    var insights = 0
        private set
    var lastLabel = "parado"
        private set
    var lastGap = ""
        private set

    private val attemptedGaps = LinkedHashSet<Int>()

    fun nextTask(pendingConsolidation: Boolean, networkAvailable: Boolean, rng: Random): Task {
        if (pendingConsolidation) return Task.Consolidate("material recém-lido")

        if (networkAvailable) {
            val gaps = semantics.knowledgeGaps(limit = 900).filter { it !in attemptedGaps }
            if (gaps.isNotEmpty()) {
                val pool = gaps.take(120)
                val pick = pool[rng.nextInt(pool.size)]
                attemptedGaps.add(pick)
                return Task.ReadGap(pick, semantics.name(pick))
            }
        }

        val n = semantics.baseCount
        if (n < 8) return Task.Bridge(0, 0)
        var a = rng.nextInt(n)
        var b = rng.nextInt(n)
        var guard = 0
        while (guard++ < 12 && (a == b || semantics.cosine(a, b) > 0.30f)) {
            b = rng.nextInt(n)
        }
        return Task.Bridge(a, b)
    }

    fun record(task: Task) {
        tasks++
        when (task) {
            is Task.Consolidate -> consolidations++
            is Task.ReadGap -> {
                reads++
                lastGap = task.word
            }
            is Task.Bridge -> bridges++
        }
        lastLabel = task.label
    }

    fun recordInsights(count: Int) {
        insights += count
    }

    fun restore(tasks: Int, reads: Int, bridges: Int, consolidations: Int, insights: Int) {
        this.tasks = tasks
        this.reads = reads
        this.bridges = bridges
        this.consolidations = consolidations
        this.insights = insights
    }
}
