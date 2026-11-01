package app.conectoma.v3.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DictionaryEngine {
    val entries: MutableMap<String, Lexeme> = Lexicon.seed()
    val semantic = SemanticMotor()

    fun known(): Int = entries.size

    fun lookup(raw: String): Lexeme? = entries[raw.lowercase().trim()]

    fun remember(word: String, meaning: String, domain: String = "aprendido"): Lexeme {
        val key = word.lowercase().trim()
        val existing = entries[key]
        return if (existing == null) {
            val lex = Lexeme(key, meaning, domain)
            entries[key] = lex
            lex
        } else {
            existing.visits++
            existing.lastSeen = System.currentTimeMillis()
            if (meaning.isNotBlank() && meaning != existing.meaning) {
                existing.meaning = merge(existing.meaning, meaning)
            }
            existing
        }
    }

    fun ingestText(text: String, domain: String = "fala"): List<Lexeme> {
        val tokens = semantic.tokenize(text)
        val learned = ArrayList<Lexeme>()
        for (t in tokens) {
            val existing = entries[t]
            if (existing != null) {
                existing.visits++
                existing.lastSeen = System.currentTimeMillis()
                learned += existing
            } else {
                learned += remember(t, "termo encontrado em contexto: “${clip(text)}”", domain)
            }
        }
        return learned
    }

    fun nearest(word: String, k: Int = 8): List<Pair<Lexeme, Float>> {
        val target = semantic.embed(word + " " + (lookup(word)?.meaning ?: ""))
        return entries.values.map { lex ->
            lex to semantic.cosine(target, semantic.embed(lex.word + " " + lex.meaning))
        }.sortedByDescending { it.second }.take(k)
    }

    fun distantPair(): Pair<Lexeme, Lexeme>? {
        if (entries.size < 8) return null
        val list = entries.values.toList()
        var best: Pair<Lexeme, Lexeme>? = null
        var bestD = -1f
        repeat(24) {
            val a = list.random()
            val b = list.random()
            if (a.word == b.word) return@repeat
            val d = semantic.semanticDistance(a.word + a.meaning, b.word + b.meaning)
            if (d > bestD) {
                bestD = d
                best = a to b
            }
        }
        return best
    }

    fun fetchDefinition(word: String): String? {
        val w = URLEncoder.encode(word.trim(), StandardCharsets.UTF_8.name())
        fetchJson("https://api.dictionaryapi.dev/api/v2/entries/en/$w")?.let { body ->
            parseFreeDictionary(body)?.let { return it }
        }
        fetchJson("https://pt.wikipedia.org/api/rest_v1/page/summary/$w")?.let { body ->
            runCatching {
                val extract = JSONObject(body).optString("extract")
                if (extract.isNotBlank()) return extract
            }
        }
        fetchJson("https://en.wikipedia.org/api/rest_v1/page/summary/$w")?.let { body ->
            runCatching {
                val extract = JSONObject(body).optString("extract")
                if (extract.isNotBlank()) return extract
            }
        }
        return null
    }

    fun fetchImage(word: String): String? {
        val w = URLEncoder.encode(word.trim(), StandardCharsets.UTF_8.name())
        val body = fetchJson("https://en.wikipedia.org/api/rest_v1/page/summary/$w") ?: return null
        return runCatching {
            JSONObject(body).optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
        }.getOrNull()
    }

    private fun parseFreeDictionary(body: String): String? = runCatching {
        val arr = JSONArray(body)
        val meanings = arr.getJSONObject(0).getJSONArray("meanings")
        val defs = meanings.getJSONObject(0).getJSONArray("definitions")
        defs.getJSONObject(0).optString("definition").ifBlank { null }
    }.getOrNull()

    private fun fetchJson(spec: String): String? = runCatching {
        val conn = URL(spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Conectoma3.0/3.0 (educational connectome app)")
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }.getOrNull()

    private fun merge(old: String, incoming: String): String {
        if (old.contains(incoming)) return old
        return (old + " · " + incoming).take(600)
    }

    private fun clip(text: String) = text.replace('\n', ' ').take(80)
}
