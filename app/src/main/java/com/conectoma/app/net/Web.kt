package com.conectoma.app.net

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Reads a page and turns it into plain Portuguese text.
 * No third-party HTTP stack: the app ships nothing it cannot compile offline.
 */
object Web {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Conectoma/3.0"

    data class Page(val url: String, val title: String, val text: String, val bytes: Int)

    fun fetch(url: String, maxBytes: Int = 2_500_000, timeoutMs: Int = 14_000): Page {
        require(url.startsWith("http://") || url.startsWith("https://")) { "apenas http(s)" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.6")
            setRequestProperty("Accept-Encoding", "gzip, deflate")
        }
        try {
            val code = conn.responseCode
            if (code >= 400) throw IllegalStateException("HTTP $code em $url")
            val encoding = conn.contentEncoding?.lowercase() ?: ""
            val raw = conn.inputStream.let { input ->
                val stream = when {
                    encoding.contains("gzip") -> GZIPInputStream(input)
                    encoding.contains("deflate") -> InflaterInputStream(input)
                    else -> input
                }
                val out = ByteArrayOutputStream(1 shl 16)
                val buf = ByteArray(1 shl 16)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > maxBytes) break
                }
                out.toByteArray()
            }
            val charset = conn.contentType?.let { ct ->
                Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
            } ?: "UTF-8"
            val body = runCatching { String(raw, charsetOf(charset)) }.getOrElse { String(raw, Charsets.UTF_8) }
            val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.let { htmlToText(it) }?.trim()
                ?: url.substringAfterLast('/')
            val text = htmlToText(body)
            return Page(url, title.ifBlank { url }, text, raw.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun charsetOf(name: String) = runCatching { charset(name) }.getOrDefault(Charsets.UTF_8)

    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script.*?</script>"), " ")
        s = s.replace(Regex("(?is)<style.*?</style>"), " ")
        s = s.replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|h[1-6]|tr|section|article)>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "• ")
        s = s.replace(Regex("<[^>]{1,400}>"), " ")
        s = s.replace("&nbsp;", " ")
        s = s.replace("&amp;", "&")
        s = s.replace("&lt;", "<")
        s = s.replace("&gt;", ">")
        s = s.replace("&quot;", "\"")
        s = s.replace("&#39;", "'")
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: " "
        }
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Wikipedia REST summary: a clean, license-free paragraph per topic. */
    fun wikipediaSummary(topic: String): Page? {
        val slug = topic.trim().replace(' ', '_')
        val url = "https://pt.wikipedia.org/api/rest_v1/page/summary/" +
            java.net.URLEncoder.encode(slug, "UTF-8")
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val extract = Regex("\"extract\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1)
                ?.replace("\\n", " ")?.replace("\\\"", "\"") ?: return null
            val title = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?.groupValues?.get(1) ?: topic
            if (extract.length < 40) return null
            Page("https://pt.wikipedia.org/wiki/$slug", title, extract, body.length)
        }.getOrNull()
    }

    /**
     * A curriculum of deliberately unrelated starting points. The autonomous learner
     * picks whichever topic is currently furthest from everything it already knows.
     */
    val CURRICULUM = listOf(
        "Fotossíntese", "Entropia", "Soneto", "Criptografia", "Mitocôndria", "Jazz",
        "Tectônica de placas", "Aprendizado de máquina", "Barroco", "Vacina",
        "Teorema de Gödel", "Coral", "Inflação", "Epigenética", "Xadrez", "Vulcão",
        "Linguística", "Supernova", "Fermentação", "Democracia", "Astrolábio",
        "Antibiótico", "Simbiose", "Poesia concreta", "Termodinâmica", "Biodiversidade",
        "Neurotransmissor", "Catedral gótica", "Enzima", "Relatividade geral",
        "Cerâmica", "Ritmo circadiano", "Criptomoeda", "Filosofia estoica",
        "Buraco negro", "Bossa nova", "Imunologia", "Cartografia", "Microbioma",
        "Computação quântica", "Romantismo", "Sismologia", "Algoritmo genético",
        "Urbanismo", "Fotografia", "Genética", "Oceanografia", "Teoria dos jogos",
        "Música modal", "Paleontologia", "Semicondutor", "Antropologia", "Energia solar",
        "Cognição", "Barragem", "Lógica paraconsistente", "Especiaria", "Neurociência",
        "Arquitetura sustentável", "Metamorfose", "Radiografia", "Dialeto", "Escultura",
        "Probabilidade", "Nebulosa", "Culinária", "Cibernética", "Botânica",
        "Satélite", "Emoção", "Mito", "Tributação", "Holografia", "Ecossistema",
        "Impressionismo", "Vacúolo", "Aristocracia", "Robótica", "Magnitude",
        "Pintura a óleo", "Evolução", "Alfabetização", "Ondas gravitacionais",
        "Terapia gênica", "Ritual", "Cartilagem", "Protocolo", "Fóssil",
        "Inteligência artificial", "Sinfonia", "Farmácia", "Cosmologia", "Dialética",
        "Permacultura", "Sinapse", "Meteorologia", "Gravura", "Ontologia"
    )
}
