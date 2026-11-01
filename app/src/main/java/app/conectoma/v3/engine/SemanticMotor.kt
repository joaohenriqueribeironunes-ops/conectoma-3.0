package app.conectoma.v3.engine

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Conceptual + mathematical distance between tokens.
 * Embeddings are 64-d hashed character n-grams (random projection), so the
 * dictionary can grow on-device without shipping a giant model.
 */
class SemanticMotor(private val dim: Int = ConnectomeConstants.EMBED_DIM) {

    fun embed(text: String): FloatArray {
        val v = FloatArray(dim)
        val norm = text.lowercase().trim()
        if (norm.isEmpty()) return v
        val padded = " $norm "
        for (n in 2..4) {
            var i = 0
            while (i + n <= padded.length) {
                val gram = padded.substring(i, i + n)
                val h1 = mix(gram.hashCode())
                val h2 = mix(h1 xor 0x9E3779B9.toInt())
                val idx = h1.mod(dim)
                val sign = if (h2 and 1 == 0) 1f else -1f
                v[idx] += sign
                i++
            }
        }
        var mag = 0f
        for (x in v) mag += x * x
        mag = sqrt(mag)
        if (mag > 1e-6f) {
            for (i in v.indices) v[i] /= mag
        }
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s.coerceIn(-1f, 1f)
    }

    fun semanticDistance(a: String, b: String): Float {
        val c = cosine(embed(a), embed(b))
        return ((1f - c) * 0.5f).coerceIn(0f, 1f)
    }

    fun mathDistance(a: String, b: String): Float {
        val na = a.replace(',', '.').toDoubleOrNull()
        val nb = b.replace(',', '.').toDoubleOrNull()
        if (na != null && nb != null && na != 0.0 && nb != 0.0) {
            val d = kotlin.math.abs(ln(kotlin.math.abs(na)) - ln(kotlin.math.abs(nb)))
            return (d / (1.0 + d)).toFloat()
        }
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() || tb.isEmpty()) return 1f
        val inter = ta.intersect(tb).size.toFloat()
        val union = ta.union(tb).size.toFloat()
        val jaccard = if (union == 0f) 0f else inter / union
        val len = levenshtein(a.lowercase(), b.lowercase()).toFloat() /
            maxOf(a.length, b.length, 1)
        return (0.55f * (1f - jaccard) + 0.45f * len).coerceIn(0f, 1f)
    }

    fun alignment(a: String, b: String): Pair<Float, Float> {
        val sem = 1f - semanticDistance(a, b)
        val math = 1f - mathDistance(a, b)
        return sem to math
    }

    fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .toSet()

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return dp[n]
    }

    private fun mix(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= -2048144789
        h = h xor (h ushr 13)
        h *= -1028477387
        return h xor (h ushr 16)
    }
}
