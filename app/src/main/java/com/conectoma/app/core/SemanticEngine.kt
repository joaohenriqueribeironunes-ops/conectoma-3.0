package com.conectoma.app.core

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lexicon + semantic space.
 *
 * Vectors are PPMI co-occurrence embeddings (random-projection reduced to 96 dims)
 * computed over the dictionary glosses, stored as int8 with a per-word scale.
 * Conceptual distance blends cosine distance, a graph geodesic over the learned
 * concept network, and a morphological term for unknown words.
 */
class SemanticEngine(bytesIn: ByteArray) {

    val dim: Int
    val kNeighbors: Int
    private var names = ArrayList<String>()
    private var defs = ArrayList<String>()
    private var lemmaIds: IntArray
    private var posArr: ByteArray
    private var freqArr: IntArray
    private var emb: ByteArray
    private var scale: FloatArray
    private var norms: FloatArray
    private var neighbors: IntArray
    var baseCount = 0
        private set

    /** words learned at runtime: word -> vector */
    private val learned = HashMap<Int, FloatArray>()
    private val index = HashMap<String, Int>(baseCount * 2)
    private val noAccent = HashMap<String, Int>()

    /** plastic concept links: (a shl 32) or b -> weight */
    private val conceptLinks = HashMap<Long, Float>()
    private val linkEvidence = HashMap<Long, Int>()

    var learnedWords = 0
        private set
    var strengthenedLinks = 0
        private set
    var plasticityEvents = 0
        private set

    init {
        val bytes = bytesIn
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(ByteArray(4).also { buf.get(it) })
        require(magic == "LEXC") { "lexicon inválido: $magic" }
        val version = buf.int
        require(version == 1)
        val n = buf.int
        dim = buf.int
        kNeighbors = buf.int
        buf.int // campo reservado

        val wordOff = IntArray(n + 1)
        buf.asIntBuffer().get(wordOff)
        buf.position(buf.position() + (n + 1) * 4)
        val wordBlob = ByteArray(wordOff[n])
        buf.get(wordBlob)

        val defOff = IntArray(n + 1)
        buf.asIntBuffer().get(defOff)
        buf.position(buf.position() + (n + 1) * 4)
        val defBlob = ByteArray(defOff[n])
        buf.get(defBlob)

        lemmaIds = IntArray(n)
        buf.asIntBuffer().get(lemmaIds)
        buf.position(buf.position() + n * 4)

        posArr = ByteArray(n)
        buf.get(posArr)

        freqArr = IntArray(n)
        buf.asIntBuffer().get(freqArr)
        buf.position(buf.position() + n * 4)

        emb = ByteArray(n * dim)
        buf.get(emb)

        scale = FloatArray(n)
        buf.asFloatBuffer().get(scale)
        buf.position(buf.position() + n * 4)

        neighbors = IntArray(n * kNeighbors)
        buf.asIntBuffer().get(neighbors)

        names.ensureCapacity(n)
        defs.ensureCapacity(n)
        for (i in 0 until n) {
            names.add(String(wordBlob, wordOff[i], wordOff[i + 1] - wordOff[i], Charsets.UTF_8))
            defs.add(String(defBlob, defOff[i], defOff[i + 1] - defOff[i], Charsets.UTF_8))
        }
        baseCount = n
        for (i in 0 until n) {
            index[names[i]] = i
            val plain = Tokenizer.stripAccents(names[i])
            if (plain != names[i] && !noAccent.containsKey(plain)) noAccent[plain] = i
        }
        norms = FloatArray(n)
        for (i in 0 until n) norms[i] = vectorNorm(i)
    }

    // ------------------------------------------------------------------ lookup
    fun idOf(word: String): Int {
        val w = word.lowercase()
        index[w]?.let { return it }
        noAccent[Tokenizer.stripAccents(w)]?.let { return it }
        // inflected form -> lemma
        val base = w.dropLastWhile { it.isLetter() && w.length > 4 && w.endsWith("s") }
        if (base != w) index[base]?.let { return it }
        for (suffix in PT_SUFFIXES) {
            if (w.endsWith(suffix) && w.length > suffix.length + 2) {
                val stem = w.dropLast(suffix.length)
                for (cand in listOf(stem + "ar", stem + "er", stem + "ir", stem + "o", stem + "a")) {
                    index[cand]?.let { return it }
                }
            }
        }
        return -1
    }

    fun name(id: Int): String = if (id in names.indices) names[id] else "?"
    fun definition(id: Int): String = if (id in defs.indices) defs[id] else ""
    fun pos(id: Int): String = POS_NAMES.getOrElse(posArr.getOrElse(id) { 0 }.toInt() and 0xFF) { "" }
    fun frequency(id: Int): Int = freqArr.getOrElse(id) { 0 }
    fun exists(id: Int) = id in names.indices

    fun lemmaOf(id: Int): Int = if (id in lemmaIds.indices) lemmaIds[id] else -1

    // ------------------------------------------------------------------ vectors
    private fun vectorNorm(id: Int): Float {
        if (id >= baseCount) {
            val v = learned[id] ?: return 1f
            var s = 0f
            for (x in v) s += x * x
            return sqrt(s).coerceAtLeast(1e-6f)
        }
        val off = id * dim
        val sc = scale[id]
        var s = 0f
        for (k in 0 until dim) {
            val x = emb[off + k] * sc
            s += x * x
        }
        return sqrt(s).coerceAtLeast(1e-6f)
    }

    fun vector(id: Int): FloatArray {
        learned[id]?.let { return it }
        if (id !in 0 until baseCount) return FloatArray(dim)
        val off = id * dim
        val sc = scale[id]
        return FloatArray(dim) { emb[off + it] * sc }
    }

    fun cosine(a: Int, b: Int): Float {
        if (a == b) return 1f
        if (a !in 0 until baseCount || b !in 0 until baseCount) {
            val va = vector(a)
            val vb = vector(b)
            var d = 0f
            var na = 0f
            var nb = 0f
            for (i in va.indices) {
                d += va[i] * vb[i]
                na += va[i] * va[i]
                nb += vb[i] * vb[i]
            }
            return (d / (sqrt(na) * sqrt(nb) + 1e-6f)).coerceIn(-1f, 1f)
        }
        val oa = a * dim
        val ob = b * dim
        var s = 0
        for (k in 0 until dim) s += emb[oa + k] * emb[ob + k]
        return (s * scale[a] * scale[b] / (norms[a] * norms[b])).coerceIn(-1f, 1f)
    }

    /**
     * Conceptual distance in [0,1]: semantic cosine + graph geodesic + morphology.
     * The graph term rewards concepts that are far in vector space but reachable
     * through a short chain of learned links ("distant but connectable").
     */
    fun distance(a: Int, b: Int): Float {
        if (a < 0 || b < 0) return 1f
        if (a == b) return 0f
        val cosineDistance = (1f - cosine(a, b)) / 2f
        val morph = morphologicalDistance(name(a), name(b))
        val g1 = graphDistance(a, b, maxHops = 3)
        val g2 = graphDistance(b, a, maxHops = 3)
        val graph = when {
            g1 < 0f && g2 < 0f -> -1f
            g1 < 0f -> g2
            g2 < 0f -> g1
            else -> min(g1, g2)
        }
        return if (graph < 0f) {
            (0.78f * cosineDistance + 0.22f * morph).coerceIn(0f, 1f)
        } else {
            (0.55f * cosineDistance + 0.30f * graph + 0.15f * morph).coerceIn(0f, 1f)
        }
    }

    fun similarity(a: Int, b: Int) = 1f - distance(a, b)

    /** how different the two words look, regardless of meaning */
    fun shapeDistance(a: Int, b: Int): Float = morphologicalDistance(name(a), name(b))

    private fun morphologicalDistance(a: String, b: String): Float {
        val x = a.take(7)
        val y = b.take(7)
        if (x == y) return 0f
        val lev = levenshtein(x, y)
        return (lev.toFloat() / max(x.length, y.length).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** weighted breadth-first search over semantic neighbours */
    fun graphDistance(a: Int, b: Int, maxHops: Int = 3): Float {
        if (a in 0 until baseCount && b in 0 until baseCount) {
            for (k in 0 until kNeighbors) {
                if (neighbors[a * kNeighbors + k] == b) {
                    return 1f / (1f + k)
                }
            }
        }
        val direct = conceptLinks[key(a, b)]
        if (direct != null) return (1f - direct).coerceIn(0.02f, 0.9f)
        // one intermediate hop
        var best = -1f
        for (mid in neighborsOf(a)) {
            val w1 = mid.second
            val link = conceptLinks[key(a, mid.first)]
            val w = if (link != null) max(w1, link) else w1 * 0.55f
            if (w < 0.18f) continue
            if (mid.first == b) {
                best = max(best, w)
                continue
            }
            for (m2 in neighborsOf(mid.first)) {
                if (m2.first == b) {
                    val w2 = min(w, m2.second)
                    if (w2 > best) best = w2
                }
            }
            if (maxHops >= 3) {
                val linkB = conceptLinks[key(mid.first, b)]
                if (linkB != null) best = max(best, min(w, linkB))
            }
        }
        return if (best <= 0f) -1f else (1f - best).coerceIn(0.02f, 0.95f)
    }

    fun neighborsOf(id: Int): List<Pair<Int, Float>> {
        if (id !in 0 until baseCount) return emptyList()
        val out = ArrayList<Pair<Int, Float>>(kNeighbors)
        for (k in 0 until kNeighbors) {
            val nb = neighbors[id * kNeighbors + k]
            if (nb >= 0) out.add(nb to (1f - k * 0.09f))
        }
        for ((key, w) in conceptLinks) {
            val a = (key ushr 32).toInt()
            val b = key.toInt()
            if (a == id) out.add(b to w) else if (b == id) out.add(a to w)
        }
        return out
    }

    /** nearest neighbours by cosine, optionally excluding a word */
    fun nearest(query: String, k: Int = 8): List<Pair<Int, Float>> {
        val q = idOf(query)
        if (q < 0) return emptyList()
        return nearestTo(q, k, emptySet(), includeLearned = false)
    }

    /**
     * Top-k mais parecidos com [id]. Usado pela consolidação para ancorar o que
     * acabou de ser aprendido no conhecimento que já existe.
     */
    fun nearestTo(
        id: Int,
        k: Int = 6,
        exclude: Set<Int> = emptySet(),
        includeLearned: Boolean = true,
    ): List<Pair<Int, Float>> {
        if (id < 0) return emptyList()
        val best = ArrayList<Pair<Int, Float>>(k + 1)
        val limit = if (includeLearned) names.size else baseCount
        for (i in 0 until limit) {
            if (i == id || i in exclude) continue
            val sim = cosine(id, i)
            if (best.size < k) {
                best.add(i to sim)
                best.sortBy { it.second }
            } else if (sim > best[0].second) {
                best[0] = i to sim
                best.sortBy { it.second }
            }
        }
        best.reverse()
        return best
    }

    /**
     * Onde o conhecimento está furado, em ordem de prioridade:
     *  3. palavra que ele aprendeu lendo e ainda não tem significado;
     *  2. forma flexionada muito frequente (sabe a flexão, não o conceito);
     *  1. palavra comum que nunca entrou em nenhuma ligação plástica.
     *
     * Isso é o que o motor autônomo usa como pauta de estudo.
     */
    fun knowledgeGaps(limit: Int = 900): List<Int> {
        val touched = HashSet<Int>(conceptLinks.size)
        for (k in conceptLinks.keys) {
            touched.add((k ushr 32).toInt())
            touched.add(k.toInt())
        }
        val scored = ArrayList<Pair<Int, Int>>(512)
        for (i in 0 until names.size) {
            val d = defs.getOrElse(i) { "" }
            val freq = if (i < baseCount) freqArr.getOrElse(i) { 0 } else 0
            val priority = when {
                d.isBlank() -> 3
                d.startsWith("forma flexionada de") && freq >= 200 -> 2
                i < 3000 && i !in touched -> 1
                else -> 0
            }
            if (priority > 0) scored.add(i to (priority * 10_000_000 + freq.coerceAtMost(9_999_999)))
        }
        scored.sortByDescending { it.second }
        return scored.take(limit).map { it.first }
    }

    /** quantas ligações plásticas um conceito tem (para medir isolamento) */
    fun linkDegree(id: Int): Int {
        var count = 0
        for (k in conceptLinks.keys) {
            if ((k ushr 32).toInt() == id || k.toInt() == id) count++
        }
        return count / 2
    }

    // ------------------------------------------------------------------ learning
    /**
     * Hebbian concept pairing: when two concepts co-occur in a meaningful context the
     * link between them stiffens; repeated co-occurrence approaches 1.
     */
    fun reinforce(a: Int, b: Int, amount: Float = 0.18f): Float {
        if (a < 0 || b < 0 || a == b) return 0f
        val key = key(a, b)
        val before = conceptLinks[key] ?: 0f
        val n = (linkEvidence[key] ?: 0) + 1
        linkEvidence[key] = n
        val gain = amount / (1f + 0.12f * n)
        var after = before + (1f - before) * gain
        if (after > 1f) after = 1f
        conceptLinks[key] = after
        conceptLinks[key(b, a)] = after
        if (after - before > 0.004f) {
            plasticityEvents++
            strengthenedLinks = conceptLinks.size / 2
        }
        return after - before
    }

    fun weaken(a: Int, b: Int, amount: Float = 0.05f) {
        val key = key(a, b)
        val before = conceptLinks[key] ?: return
        val after = (before - amount).coerceAtLeast(0f)
        if (after <= 0f) {
            conceptLinks.remove(key)
            conceptLinks.remove(key(b, a))
        } else {
            conceptLinks[key] = after
            conceptLinks[key(b, a)] = after
        }
    }

    fun linkWeight(a: Int, b: Int): Float = conceptLinks[key(a, b)] ?: 0f

    fun topLinks(limit: Int = 12): List<Triple<Int, Int, Float>> =
        conceptLinks.asSequence()
            .filter { (it.key ushr 32).toInt() < it.key.toInt() }
            .map { Triple((it.key ushr 32).toInt(), it.key.toInt(), it.value) }
            .sortedByDescending { it.third }
            .take(limit)
            .toList()

    /** create (or return) a runtime word, seeding its vector from its context */
    fun idOrCreate(word: String, contextIds: List<Int> = emptyList()): Int {
        val existing = idOf(word)
        if (existing >= 0) return existing
        val clean = word.lowercase().trim()
        if (clean.length < 2 || clean.length > 32) return -1
        // teto de crescimento acumulado: memória ilimitada aqui era o caminho
        // direto para OOM/ANR — acima do teto o léxico simplesmente para de crescer
        if (learned.size >= MAX_LEARNED_WORDS) return -1
        val id = names.size
        names.add(clean)
        defs.add("")
        val v = FloatArray(dim)
        if (contextIds.isNotEmpty()) {
            for (cid in contextIds) {
                val cv = vector(cid)
                for (k in 0 until dim) v[k] += cv[k]
            }
            val inv = 1f / contextIds.size
            var norm = 0f
            for (k in 0 until dim) {
                v[k] *= inv
                norm += v[k] * v[k]
            }
            norm = sqrt(norm).coerceAtLeast(1e-6f)
            for (k in 0 until dim) v[k] = v[k] / norm * 2.4f
        } else {
            // morphological seed: average of words sharing a prefix
            var n = 0
            val prefix = clean.take(4)
            for (i in 0 until baseCount) {
                val nm = names[i]
                if (nm.length >= 4 && nm.startsWith(prefix) && n < 24) {
                    val cv = vector(i)
                    for (k in 0 until dim) v[k] += cv[k]
                    n++
                }
            }
            if (n > 0) {
                for (k in 0 until dim) v[k] /= n.toFloat()
                // nudge away from the prefix cloud so the word is not a duplicate
                val rnd = Random(clean.hashCode().toLong())
                for (k in 0 until dim) v[k] += (rnd.nextFloat() - 0.5f) * 0.08f
            } else {
                val rnd = Random(clean.hashCode().toLong())
                for (k in 0 until dim) v[k] = (rnd.nextFloat() - 0.5f) * 0.6f
            }
        }
        learned[id] = v
        index[clean] = id
        learnedWords++
        return id
    }

    fun setDefinition(id: Int, text: String) {
        if (id in defs.indices && defs[id].isBlank()) defs[id] = text.take(400)
    }

    private fun key(a: Int, b: Int): Long {
        val lo = min(a, b).toLong()
        val hi = max(a, b).toLong()
        return (lo shl 32) or hi
    }

    fun serialiseLearned(): String {
        val sb = StringBuilder()
        sb.append("{\"words\":[")
        var first = true
        for ((id, v) in learned.entries.take(MAX_LEARNED_WORDS)) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"w\":\"").append(jsonEscape(names[id])).append("\",\"v\":[")
            for (i in v.indices) {
                if (i > 0) sb.append(',')
                sb.append(String.format(java.util.Locale.US, "%.3f", v[i]))
            }
            sb.append("]}")
        }
        sb.append("],\"links\":[")
        first = true
        for ((k, w) in conceptLinks.entries.take(MAX_LEARNED_LINKS)) {
            val a = (k ushr 32).toInt()
            val b = k.toInt()
            if (a >= b) continue
            if (!first) sb.append(',')
            first = false
            sb.append("[\"").append(jsonEscape(names[a])).append("\",\"")
                .append(jsonEscape(names[b])).append("\",").append(String.format(java.util.Locale.US, "%.3f", w)).append(']')
        }
        sb.append("]}")
        return sb.toString()
    }

    fun restoreLearned(json: String) {
        runCatching {
            val root = org.json.JSONObject(json)
            val arr = root.optJSONArray("words") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val w = o.getString("w")
                val v = o.getJSONArray("v")
                // dedup: seed do APK + memória do aparelho podem se sobrepor —
                // reinserir a mesma palavra duplicaria o léxico a cada boot
                if (index.containsKey(w)) continue
                if (learned.size >= MAX_LEARNED_WORDS) break
                val id = names.size
                names.add(w)
                defs.add("")
                learned[id] = FloatArray(v.length()) { v.getDouble(it).toFloat() }
                index[w] = id
            }
            val links = root.optJSONArray("links") ?: return
            for (i in 0 until links.length()) {
                val l = links.getJSONArray(i)
                val a = index[l.getString(0)] ?: continue
                val b = index[l.getString(1)] ?: continue
                val w = l.getDouble(2).toFloat()
                conceptLinks[key(a, b)] = w
                conceptLinks[key(b, a)] = w
            }
            strengthenedLinks = conceptLinks.size / 2
            learnedWords = learned.size
        }
    }

    companion object {
        val POS_NAMES = arrayOf("", "subst.", "verbo", "adj.", "adv.", "nome", "pron.", "prep.", "conj.", "num.")
        /** teto de palavras aprendidas em runtime — memória crescente, porém finita */
        const val MAX_LEARNED_WORDS = 8_000
        /** teto de sinapses novas aprendidas (pares de conceitos) */
        const val MAX_LEARNED_LINKS = 40_000
        private val PT_SUFFIXES = listOf("ando", "endo", "indo", "ado", "ido", "ada", "ida", "mente", "ções", "s")
        private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev
                prev = IntArray(cur.size) { cur[it] }
                if (t === prev) Unit
            }
            return prev[b.length]
        }

        fun pmik(cos: Float): Float = (1f - cos).coerceIn(0f, 2f)
    }
}

object Tokenizer {
    private val STOP = setOf(
        "a", "o", "e", "de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas", "um", "uma",
        "uns", "umas", "que", "para", "por", "com", "sem", "sob", "sobre", "entre", "ate", "apos",
        "se", "sua", "seu", "suas", "seus", "minha", "meu", "ao", "aos", "as", "os", "mais", "menos",
        "muito", "muita", "ja", "nao", "sim", "ou", "mas", "como", "quando", "onde", "porque", "ele",
        "ela", "eles", "elas", "eu", "tu", "voce", "voces", "nos", "lhe", "lhes", "me", "te", "isso",
        "isto", "aquilo", "esse", "essa", "este", "esta", "aquele", "aquela", "foi", "era", "sao",
        "ser", "estar", "esta", "estao", "tem", "ter", "havia", "ha", "the", "of", "and", "to", "in",
        "is", "it", "that", "this", "for", "with", "on", "as", "at", "by", "an", "be", "are",
        "fale", "fala", "diga", "diz", "conte", "conta", "explique", "explica", "defina", "defini",
        "mostre", "quero", "gostaria", "poderia", "pode", "ajuda", "ajude", "favor", "sobre", "respeito",
        "liga", "ligam", "ligado", "ligada", "conecta", "conectar", "conectado", "descobriu", "descobrir"
    )

    fun stripAccents(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val r = when (ch) {
                'á', 'à', 'â', 'ã', 'ä' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                'ú', 'ù', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                else -> ch
            }
            sb.append(r)
        }
        return sb.toString()
    }

    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>(64)
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch.isLetter() || ch == '-' || ch == '\'') {
                sb.append(ch)
            } else if (sb.isNotEmpty()) {
                val w = sb.toString().trim('-', '\'')
                if (w.isNotEmpty()) out.add(w)
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) {
            val w = sb.toString().trim('-', '\'')
            if (w.isNotEmpty()) out.add(w)
        }
        return out
    }

    fun contentWords(text: String): List<String> = tokenize(text).filter { it !in STOP && it.length > 2 }

    fun isStop(word: String) = word in STOP

    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                val s = sb.toString().trim()
                if (s.length > 12) out.add(s)
                sb.setLength(0)
            }
        }
        val rest = sb.toString().trim()
        if (rest.length > 12) out.add(rest)
        return out
    }
}
