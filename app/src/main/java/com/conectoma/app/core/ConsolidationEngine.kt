package com.conectoma.app.core

/**
 * Consolidação: pega o que acabou de entrar (PDF, URL, Wikipédia, conversa) e
 * costura no conhecimento que já existe.
 *
 * Duas operações:
 *  1. ancoragem — cada conceito novo é ligado aos vizinhos mais próximos que ele
 *     já conhecia, com peso proporcional à similaridade;
 *  2. ponte — pares distantes que apareceram juntos no mesmo material são
 *     conectados; são as ligações que "não existiam antes" e valem insight.
 *
 * Sem essa etapa, um PDF deixaria conceitos soltos, ligados só às palavras que
 * estavam na mesma página.
 */
class ConsolidationEngine(private val semantics: SemanticEngine) {

    data class Report(
        val anchors: Int = 0,
        val bridges: Int = 0,
        val reinforced: Int = 0,
        val insights: List<Pair<String, String>> = emptyList(),
        val score: Float = 0f,
        val summary: String = "",
    )

    /**
     * @param concepts conceitos extraídos do material recém-lido
     * @param maxFocus quantos conceitos recebem ancoragem (custo O(n·N))
     */
    fun consolidate(concepts: List<Int>, maxFocus: Int = 24): Report {
        val inside = LinkedHashSet(concepts.filter { it >= 0 })
        if (inside.isEmpty()) return Report(summary = "nada para consolidar")
        val exclude = inside.toHashSet()

        var anchors = 0
        var reinforced = 0
        // 1) ancora no que já existe fora do documento
        for (c in inside.take(maxFocus)) {
            val neighbours = semantics.nearestTo(c, k = 5, exclude = exclude)
            for ((other, sim) in neighbours) {
                if (sim < 0.24f) continue
                val before = semantics.linkWeight(c, other)
                semantics.reinforce(c, other, 0.07f + 0.20f * sim)
                if (semantics.linkWeight(c, other) > before + 0.002f) anchors++
            }
        }

        // 2) pontes entre conceitos distantes do mesmo material
        var bridges = 0
        val insights = ArrayList<Pair<String, String>>(4)
        val terms = inside.take(14)
        for (i in terms.indices) {
            for (j in i + 1 until terms.size) {
                val a = terms[i]
                val b = terms[j]
                val sim = semantics.cosine(a, b)
                if (sim < 0.18f) {
                    val before = semantics.linkWeight(a, b)
                    semantics.reinforce(a, b, 0.13f)
                    if (semantics.linkWeight(a, b) > before + 0.003f) {
                        bridges++
                        if (insights.size < 4) {
                            insights.add(semantics.name(a) to semantics.name(b))
                        }
                    }
                } else if (sim > 0.55f) {
                    semantics.reinforce(a, b, 0.05f)
                    reinforced++
                }
            }
        }

        val score = (0.035f * anchors + 0.06f * bridges).coerceIn(0f, 1f)
        val summary = buildString {
            append(anchors)
            append(if (anchors == 1) " ligação nova ao que já sabia" else " ligações novas ao que já sabia")
            if (bridges > 0) {
                append(" · ")
                append(bridges)
                append(if (bridges == 1) " ponte entre ideias distantes" else " pontes entre ideias distantes")
            }
        }
        return Report(anchors, bridges, reinforced, insights, score, summary)
    }
}
