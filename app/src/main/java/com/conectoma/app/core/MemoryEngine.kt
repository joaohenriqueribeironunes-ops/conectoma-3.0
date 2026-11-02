package com.conectoma.app.core

import kotlin.math.max

/**
 * Memória episódica do conectoma.
 *
 * Cada leitura (URL, PDF, Wikipédia ou conversa) vira um *engrama*: título, de
 * onde veio, os conceitos que entraram e as frases mais informativas do texto.
 * Quando um conceito volta à conversa, o assistente recupera essas frases e as
 * usa na resposta — é o que transforma "sei que a palavra existe" em "eu li
 * isso e posso te contar".
 *
 * O formato de persistência é texto puro (sem org.json) para funcionar também
 * nos testes de JVM.
 */
class MemoryEngine(private val semantics: SemanticEngine) {

    data class Engram(
        val title: String,
        val url: String,
        val concepts: List<String>,
        val facts: List<String>,
        val stamp: Long,
    )

    data class Fact(
        val text: String,
        val source: String,
        val score: Float,
    )

    private val engrams = ArrayList<Engram>()
    private var clock = 1L

    val size: Int get() = engrams.size
    val factCount: Int get() = engrams.sumOf { it.facts.size }
    val titles: List<String> get() = engrams.map { it.title }

    // ---------------------------------------------------------------- learning

    /**
     * Guarda um material recém-lido. [concepts] são ids do léxico; os nomes são
     * resolvidos aqui para que a memória continue legível depois de reiniciar.
     */
    fun remember(title: String, url: String, text: String, concepts: List<Int>): Engram {
        val names = concepts
            .filter { it >= 0 }
            .map { semantics.name(it) }
            .filter { it.isNotBlank() && it != "?" }
            .distinct()
            .take(32)
        val facts = extractFacts(text, concepts.filter { it >= 0 }.toSet(), names)
        val engram = Engram(
            title = cleanTitle(title, url),
            url = url.take(300),
            concepts = names,
            facts = facts,
            stamp = clock++,
        )
        engrams.add(0, engram)
        if (engrams.size > MAX_ENGRAMS) engrams.removeAt(engrams.size - 1)
        return engram
    }

    private fun cleanTitle(title: String, url: String): String {
        val t = title.trim().ifBlank { url.substringAfterLast('/').ifBlank { "leitura" } }
        return t.take(160)
    }

    /**
     * Escolhe frases que realmente ensinam algo: definições, frases com vários
     * conceitos do material e as primeiras frases do texto (que costumam definir
     * o assunto). Descarta repetições e frases curtas demais.
     */
    private fun extractFacts(text: String, conceptIds: Set<Int>, names: List<String>): List<String> {
        val sentences = Tokenizer.sentences(text)
        if (sentences.isEmpty()) return emptyList()
        data class Candidate(val text: String, val score: Float, val index: Int)
        val candidates = ArrayList<Candidate>(sentences.size)
        val nameSet = names.map { it.lowercase() }.toSet()

        for ((index, raw) in sentences.withIndex()) {
            val s = raw.replace(Regex("\\s+"), " ").trim()
            if (s.length < 45 || s.length > 340) continue
            if (s.count { it == '|' } > 2) continue
            var score = 0f
            if (index < 8) score += 0.9f
            val lower = s.lowercase()
            if (DEFINITION_MARKERS.any { lower.contains(it) }) score += 2.2f
            if (lower.contains("por exemplo") || lower.contains("ou seja")) score += 0.4f

            val tokens = Tokenizer.contentWords(s)
            if (tokens.size >= 5) score += 0.3f
            var hits = 0
            for (token in tokens) {
                val id = semantics.idOf(token)
                if (id >= 0 && id in conceptIds) hits++
            }
            score += (hits.coerceAtMost(5)) * 0.65f
            var nameHits = 0
            for (n in nameSet) {
                if (n.length >= 4 && lower.contains(n)) nameHits++
            }
            score += nameHits.coerceAtMost(4) * 0.35f
            if (s.count { it.isDigit() } > s.length / 5) score -= 0.8f
            if (score >= 1.1f) candidates.add(Candidate(s, score, index))
        }

        candidates.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.index })
        val chosen = ArrayList<String>(MAX_FACTS)
        val seen = ArrayList<String>(MAX_FACTS)
        for (c in candidates) {
            val key = c.text.lowercase().take(60)
            if (seen.any { it.startsWith(key.take(30)) || key.startsWith(it.take(30)) }) continue
            chosen.add(c.text)
            seen.add(key)
            if (chosen.size >= MAX_FACTS) break
        }
        if (chosen.isEmpty()) {
            // fallback: pelo menos uma frase longa do material
            sentences.firstOrNull { it.length in 45..340 }?.let { chosen.add(it.trim()) }
        }
        return chosen
    }

    // ---------------------------------------------------------------- recall

    /** Engramas mais relevantes para um conjunto de conceitos. */
    fun recall(ids: List<Int>, k: Int = 3): List<Engram> {
        val wanted = ids.filter { it >= 0 }.distinct()
        if (wanted.isEmpty() || engrams.isEmpty()) return emptyList()
        data class Ranked(val engram: Engram, val score: Float)

        val ranked = ArrayList<Ranked>(engrams.size)
        for (e in engrams) {
            var score = 0f
            for (c in e.concepts) {
                val cid = semantics.idOf(c)
                if (cid < 0) continue
                if (cid in wanted) {
                    score += 1.8f
                    continue
                }
                var best = 0f
                for (w in wanted) {
                    val sim = semantics.cosine(w, cid)
                    if (sim > best) best = sim
                }
                if (best > 0.34f) score += best
            }
            if (score <= 0.25f) continue
            // leve preferência pelo que entrou recentemente, sem apagar o antigo
            score += (e.stamp.toFloat() / max(clock, 2L).toFloat()) * 0.18f
            ranked.add(Ranked(e, score))
        }
        ranked.sortByDescending { it.score }
        return ranked.take(k).map { it.engram }
    }

    /**
     * Frases concretas sobre um assunto. É isso que entra na resposta detalhada:
     * o assistente cita o que leu, não apenas sinônimos.
     */
    fun factsFor(ids: List<Int>, k: Int = 4): List<Fact> {
        val wanted = ids.filter { it >= 0 }.distinct()
        if (wanted.isEmpty() || engrams.isEmpty()) return emptyList()
        val wantedNames = wanted.map { semantics.name(it) }.filter { it.length >= 3 }
        val out = ArrayList<Fact>()

        for (e in engrams) {
            var directConcepts = 0
            var soft = 0f
            for (c in e.concepts) {
                val cid = semantics.idOf(c)
                if (cid < 0) continue
                if (cid in wanted) {
                    directConcepts++
                } else {
                    var best = 0f
                    for (w in wanted) {
                        val sim = semantics.cosine(w, cid)
                        if (sim > best) best = sim
                    }
                    // só conta similaridade muito forte; evita puxar lembranças alheias
                    if (best > 0.58f) soft += best * 0.4f
                }
            }
            if (directConcepts == 0 && soft < 0.30f) continue
            for (f in e.facts) {
                val lower = f.lowercase()
                var mentions = 0
                for (name in wantedNames) if (lower.contains(name)) mentions++
                // o fato precisa ser sobre o assunto: cita a palavra ou o engrama
                // tem uma correspondência conceitual direta
                if (directConcepts == 0 && mentions == 0) continue
                var score = directConcepts * 1.4f + mentions.coerceAtMost(4) * 0.9f + soft
                if (DEFINITION_MARKERS.any { lower.contains(it) }) score += 0.25f
                out.add(Fact(f, e.title, score))
            }
        }
        out.sortByDescending { it.score }
        val distinct = ArrayList<Fact>(k)
        val seen = HashSet<String>()
        for (f in out) {
            val key = f.text.lowercase().take(50)
            if (!seen.add(key)) continue
            distinct.add(f)
            if (distinct.size >= k) break
        }
        return distinct
    }

    /** Fatos recentes, usados pelo motor espontâneo. */
    fun recentFacts(k: Int = 6): List<Fact> =
        engrams.asSequence()
            .flatMap { e -> e.facts.asSequence().map { Fact(it, e.title, 0f) } }
            .take(k)
            .toList()

    fun titlesMatching(ids: List<Int>, k: Int = 3): List<String> =
        recall(ids, k).map { it.title }

    // ---------------------------------------------------------------- persistence

    fun serialise(): String {
        val sb = StringBuilder()
        for ((index, e) in engrams.withIndex()) {
            if (index > 0) sb.append(RECORD)
            sb.append(esc(e.title)).append(FIELD)
                .append(esc(e.url)).append(FIELD)
                .append(e.concepts.joinToString(LIST.toString()) { esc(it) }).append(FIELD)
                .append(e.facts.joinToString(LIST.toString()) { esc(it) }).append(FIELD)
                .append(e.stamp)
        }
        return sb.toString()
    }

    fun restore(blob: String) {
        if (blob.isBlank()) return
        runCatching {
            val restored = ArrayList<Engram>()
            for (record in blob.split(RECORD)) {
                if (record.isBlank()) continue
                val p = record.split(FIELD)
                if (p.size < 5) continue
                val concepts = p[2].split(LIST).filter { it.isNotBlank() }.map { unesc(it) }
                val facts = p[3].split(LIST).filter { it.isNotBlank() }.map { unesc(it) }
                val stamp = p[4].toLongOrNull() ?: 0L
                restored.add(Engram(unesc(p[0]), unesc(p[1]), concepts, facts, stamp))
            }
            engrams.clear()
            engrams.addAll(restored.take(MAX_ENGRAMS))
            clock = (engrams.maxOfOrNull { it.stamp } ?: 0L) + 1L
        }
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace(FIELD.toString(), "\\f")
        .replace(RECORD.toString(), "\\r")
        .replace(LIST.toString(), "\\l")

    private fun unesc(s: String): String = s
        .replace("\\l", LIST.toString())
        .replace("\\r", RECORD.toString())
        .replace("\\f", FIELD.toString())
        .replace("\\\\", "\\")

    companion object {
        private const val MAX_ENGRAMS = 400
        private const val MAX_FACTS = 6
        private const val FIELD = '\u0001'
        private const val RECORD = '\u0002'
        private const val LIST = '\u0003'
        private val DEFINITION_MARKERS = listOf(
            " é ", " são ", " significa ", " refere-se", " consiste", " quer dizer ",
            " define-se", " é definido", " é chamado", " é conhecido", " trata-se",
        )
    }
}
