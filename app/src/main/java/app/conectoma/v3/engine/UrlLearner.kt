package app.conectoma.v3.engine

import java.net.HttpURLConnection
import java.net.URL

class UrlLearner(private val dictionary: DictionaryEngine) {

    fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Conectoma3.0/3.0")
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return stripHtml(raw)
    }

    fun learn(url: String): UrlLesson {
        val text = fetchText(url)
        val lexemes = dictionary.ingestText(text, domain = "url")
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: url
        return UrlLesson(url = url, title = title, excerpt = text.take(500), tokens = lexemes.size)
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

data class UrlLesson(
    val url: String,
    val title: String,
    val excerpt: String,
    val tokens: Int
)
