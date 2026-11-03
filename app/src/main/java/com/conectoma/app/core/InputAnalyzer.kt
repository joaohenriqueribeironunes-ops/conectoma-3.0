package com.conectoma.app.core

/**
 * Análise estrutural e semântica da frase ANTES do conectoma disparar.
 *
 * O motor não responde às cegas: primeiro classifica a intenção do enunciado
 * (definição, comparação, relação, causa, exemplo, ensino, pergunta, reflexão),
 * extrai os termos-foco, detecta negação e marcadores relacionais/causais.
 * Essa leitura orienta tanto o roteamento da resposta quanto a forma como os
 * clusters são re-disparados no raciocínio iterativo.
 *
 * 100% local: tabela de marcadores em PT-BR, sem rede, sem modelo externo.
 */
object InputAnalyzer {

    /** Intenção detectada no enunciado — define o formato da resposta. */
    enum class Intent {
        DEFINICAO,   // "o que é X", "X significa Y"
        COMPARACAO,  // "diferença entre X e Y", "X ou Y é melhor"
        RELACAO,     // "como X se liga a Y", "o que une X e Y"
        CAUSA,       // "por que X", "o que causa Y"
        EXEMPLO,     // "me dê um exemplo de X"
        ENSINO,      // "quero aprender X", "me ensina Y"
        PERGUNTA,    // pergunta geral sem marcador específico
        REFLEXAO,    // afirmação / reflexão do usuário
    }

    /** Resultado da leitura da frase, pronto para o motor de raciocínio. */
    data class Analysis(
        val intent: Intent,
        /** termos de conteúdo na ordem em que aparecem (candidatos a foco) */
        val focusWords: List<String>,
        /** marcadores relacionais encontrados ("se liga", "conexão"...) */
        val relationMarkers: List<String>,
        /** marcadores causais encontrados ("porque", "leva a"...) */
        val causalMarkers: List<String>,
        /** há negação no enunciado ("não", "nunca", "exceto"...) */
        val negated: Boolean,
        /** a frase termina (ou contém) interrogação */
        val question: Boolean,
        /** comparação explícita entre dois ou mais termos */
        val comparative: Boolean,
    )

    // ------------------------------------------------------------- marcadores

    private val RELATION = listOf(
        "se liga", "relaciona", "relação", "relacao", "conexão", "conexao",
        "tem a ver", "parecid", "diferença", "diferenca", "conecta", "liga",
        "uno", "une", "ligado", "vínculo", "vinculo", "vizinho",
    )

    private val CAUSAL = listOf(
        "porque", "por que", "por qual motivo", "causa", "origem",
        "leva a", "resulta em", "devido", "por causa", "consequência", "consequencia",
    )

    private val DEFINITION = listOf(
        "o que é", "o que são", "o que e", "o que sao", "significa",
        "quer dizer", "definição", "definicao", "definido como", "qual o sentido",
    )

    private val COMPARATIVE = listOf(
        "diferença entre", "diferenca entre", "compar", "melhor", "pior",
        "igual", "parecido com", "ao contrário de", "ao contrario de",
        "mais ... que", "ou melhor", "versus", " vs ", "em vez de",
    )

    private val EXAMPLE = listOf(
        "exemplo", "ilustra", "caso prático", "caso pratico", "me dá um caso",
        "me da um caso", "por exemplo", "como seria na prática",
    )

    private val TEACH = listOf(
        "quero aprender", "me ensina", "ensinar", "aprender sobre",
        "aprender com", "estudar", "me explica como aprender",
    )

    private val CAUSE_QUESTION = listOf(
        "por que", "porque", "qual a causa", "o que causa",
        "de onde vem", "como surgiu", "por qual motivo",
    )

    private val NEGATION = listOf(
        " não ", "nao ", "nunca", "jamais", "exceto", "senão que", "se nao",
        "não é", "nao é", "não é isso", "esquece",
    )

    private val COMPARISON_PAIR = listOf(" e ", " ou ", " entre ", " x ", " vs ")

    // ------------------------------------------------------------- análise

    fun analyze(input: String): Analysis {
        val text = " ${input.lowercase().trim()} "
        val question = input.trim().endsWith("?") ||
            text.contains(" o que ") || text.contains(" qual ") ||
            text.contains(" como ") || text.contains(" por que ") ||
            text.contains(" quando ") || text.contains(" onde ") ||
            text.contains(" quem ") || text.contains(" quanto ")

        val relationHits = RELATION.filter { text.contains(it) }
        val causalHits = CAUSAL.filter { text.contains(it) }
        val negated = NEGATION.any { text.contains(it) }
        val comparative = COMPARATIVE.any { text.contains(it) } ||
            (relationHits.any { it == "diferença" || it == "diferenca" || it == "parecid" })

        val words = Tokenizer.contentWords(input)

        val intent = when {
            TEACH.any { text.contains(it) } -> Intent.ENSINO
            EXAMPLE.any { text.contains(it) } -> Intent.EXEMPLO
            CAUSE_QUESTION.any { text.contains(it) } || causalHits.isNotEmpty() -> Intent.CAUSA
            DEFINITION.any { text.contains(it) } -> Intent.DEFINICAO
            comparative && words.size >= 2 -> Intent.COMPARACAO
            relationHits.isNotEmpty() && words.size >= 2 -> Intent.RELACAO
            question -> Intent.PERGUNTA
            else -> Intent.REFLEXAO
        }

        return Analysis(
            intent = intent,
            focusWords = words,
            relationMarkers = relationHits,
            causalMarkers = causalHits,
            negated = negated,
            question = question,
            comparative = comparative || (words.size >= 2 && COMPARISON_PAIR.any { text.contains(it) }),
        )
    }

    /**
     * Ordem em que os termos-foco devem entrar na memória de trabalho:
     * o termo mais "específico" (menor frequência no léxico) lidera o contexto,
     * igual ao critério usado pelo motor — aqui reaproveitado para o round inicial.
     */
    fun prioritize(focus: List<Int>, semantics: SemanticEngine): List<Int> =
        focus.sortedBy { if (semantics.frequency(it) <= 0) 1 else semantics.frequency(it) }
}
