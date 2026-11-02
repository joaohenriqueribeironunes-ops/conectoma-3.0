package com.conectoma.app.core

import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Extrator de texto de PDF em Kotlin puro, sem dependências.
 *
 * Cobre o que aparece na prática: xref ignorado (varre objetos direto), streams
 * FlateDecode, fontes com ToUnicode CMap, strings literais e hexadecimais,
 * operadores Tj/TJ/'/" e posicionamento Td/TD/Tm/T*.
 *
 * Limites conhecidos: PDFs cifrados são recusados e PDFs só de imagem (escaneados)
 * não têm texto — nesse caso o app avisa e usa apenas a visualização.
 */
object PdfText {

    internal const val MAX_CHARS = 600_000

    fun extract(bytes: ByteArray, maxPages: Int = 60): String {
        if (bytes.size < 8 || !String(bytes, 0, 5, Charsets.ISO_8859_1).startsWith("%PDF")) {
            throw IllegalArgumentException("não parece um PDF")
        }
        val doc = PdfFile(bytes)
        if (doc.isEncrypted()) throw IllegalStateException("PDF protegido por senha")
        val pages = doc.pageObjects().take(maxPages)
        val out = StringBuilder(8192)
        pages.forEachIndexed { index, page ->
            if (index > 0) out.append("\n\n")
            val content = doc.pageContent(page)
            if (content.isNotEmpty()) doc.renderText(content, page, out)
            if (out.length > MAX_CHARS) return out.toString().take(MAX_CHARS)
        }
        return out.toString()
    }
}

// ------------------------------------------------------------------ modelo

private const val PDF_WS = " \t\r\n\u000C\u0000"

private sealed interface PObj
private class PDict(val map: MutableMap<String, PObj> = LinkedHashMap()) : PObj {
    operator fun get(key: String): PObj? = map[key]
}
private class PArr(val items: MutableList<PObj> = ArrayList()) : PObj
private class PName(val name: String) : PObj
private class PNum(val value: Double) : PObj
private class PStr(val bytes: ByteArray) : PObj
private class PRef(val num: Int) : PObj
private class PBool(val value: Boolean) : PObj
private object PNull : PObj
private class PStream(val dict: PDict, val raw: ByteArray) : PObj

private class PdfFile(private val src: ByteArray) {

    private val objects = HashMap<Int, PObj>(256)
    private val trailer = HashMap<String, PObj>(8)

    init {
        scanObjects()
        findTrailer()
    }

    fun isEncrypted(): Boolean = trailer["Encrypt"] != null || objects.values.any {
        (it as? PDict)?.get("Encrypt") != null
    }

    // ---------------------------------------------------------- varredura
    private fun scanObjects() {
        var i = 0
        val n = src.size
        while (i < n) {
            val mark = i
            var p = i
            var num = 0
            var sawNum = false
            while (p < n && src[p].toInt() in 48..57) { num = num * 10 + (src[p].toInt() - 48); p++; sawNum = true }
            if (!sawNum) { i = mark + 1; continue }
            p = skipSpaces(p)
            var gen = 0
            var sawGen = false
            while (p < n && src[p].toInt() in 48..57) { gen = gen * 10 + (src[p].toInt() - 48); p++; sawGen = true }
            if (!sawGen) { i = mark + 1; continue }
            p = skipSpaces(p)
            if (!startsWith(p, "obj")) { i = mark + 1; continue }
            p = skipSpaces(p + 3)
            val parser = Parser(src, p)
            val obj = try { parser.parseObject(0) } catch (t: Throwable) { i = mark + 1; continue }
            var pos = parser.pos
            pos = skipSpaces(pos)
            if (obj is PDict && startsWith(pos, "stream")) {
                var dataStart = pos + 6
                if (dataStart < n && src[dataStart].toInt() == 13) dataStart++
                if (dataStart < n && src[dataStart].toInt() == 10) dataStart++
                val declared = (obj["Length"] as? PNum)?.value?.toInt()
                    ?: (obj["Length"] as? PRef)?.let { ref -> (objects[ref.num] as? PNum)?.value?.toInt() }
                val end = if (declared != null && declared > 0 && dataStart + declared <= n) {
                    dataStart + declared
                } else {
                    indexOf("endstream", dataStart).let { if (it < 0) n else it }
                }
                objects[num] = PStream(obj, src.copyOfRange(dataStart, minOf(end, n)))
                var after = indexOf("endstream", end)
                if (after < 0) after = end
                i = skipSpaces(after + 9)
                continue
            }
            objects[num] = obj
            i = maxOf(skipSpaces(pos), mark + 1)
        }
    }

    private fun findTrailer() {
        var idx = lastIndexOf("trailer")
        while (idx >= 0) {
            val p = Parser(src, idx + 7)
            val obj = try { p.parseObject(0) } catch (t: Throwable) { null }
            if (obj is PDict) {
                for ((k, v) in obj.map) if (k !in trailer) trailer[k] = v
                if (trailer["Root"] != null) return
            }
            idx = lastIndexOf("trailer", idx - 1)
        }
        // PDF 1.5+: /Root costuma estar no dict do xref stream
        for (obj in objects.values) {
            val d = (obj as? PDict) ?: (obj as? PStream)?.dict ?: continue
            if (d["Root"] != null) { trailer["Root"] = d["Root"]!!; return }
        }
    }

    // ---------------------------------------------------------- páginas
    fun pageObjects(): List<PDict> {
        val root = resolve(trailer["Root"]) as? PDict
        val pagesNode = resolve(root?.get("Pages")) as? PDict
        val out = ArrayList<PDict>(32)
        if (pagesNode != null) collectPages(pagesNode, out, 0)
        if (out.isEmpty()) {
            objects.entries.sortedBy { it.key }.forEach { (_, obj) ->
                val d = (obj as? PDict) ?: (obj as? PStream)?.dict ?: return@forEach
                if (name(d["Type"]) == "Page") out.add(d)
            }
        }
        return out
    }

    private fun collectPages(node: PDict, out: MutableList<PDict>, depth: Int) {
        if (depth > 24) return
        val type = name(node["Type"])
        if (type == "Page") { out.add(node); return }
        val kids = resolve(node["Kids"]) as? PArr ?: return
        for (kid in kids.items) {
            val child = resolve(kid) as? PDict ?: continue
            collectPages(child, out, depth + 1)
        }
    }

    fun pageContent(page: PDict): ByteArray {
        val contents = resolve(page["Contents"]) ?: return ByteArray(0)
        val parts = when (contents) {
            is PArr -> contents.items.mapNotNull { streamBytes(resolve(it)) }
            else -> listOfNotNull(streamBytes(contents))
        }
        if (parts.isEmpty()) return ByteArray(0)
        if (parts.size == 1) return parts[0]
        val total = parts.sumOf { it.size + 1 }
        val joined = ByteArray(total)
        var off = 0
        for (p in parts) { p.copyInto(joined, off); off += p.size; joined[off++] = 10 }
        return joined
    }

    // ---------------------------------------------------------- fontes
    private fun fontsFor(page: PDict): Map<String, CMapFont> {
        var resources = resolve(page["Resources"]) as? PDict
        if (resources == null) {
            val parent = resolve(page["Parent"]) as? PDict
            resources = resolve(parent?.get("Resources")) as? PDict
        }
        val fontDict = resolve(resources?.get("Font")) as? PDict ?: return emptyMap()
        val out = HashMap<String, CMapFont>()
        for ((key, value) in fontDict.map) {
            val font = resolve(value) as? PDict ?: continue
            val subtype = name(font["Subtype"])
            val composite = subtype == "Type0"
            val cmap = (resolve(font["ToUnicode"]) as? PStream)?.let { stream ->
                runCatching { CMapFont.parse(decode(stream), composite) }.getOrNull()
            } ?: CMapFont.empty(composite)
            cmap.loadWidths(font, this)
            out[key] = cmap
        }
        return out
    }

    // ---------------------------------------------------------- conteúdo
    private var lastLineY: Double? = null

    fun renderText(content: ByteArray, page: PDict, out: StringBuilder) {
        val fonts = fontsFor(page)
        var font = CMapFont.empty(false)
        var size = 12.0
        var lineX = 0.0
        var advance = 0.0
        var pendingSpace = false
        lastLineY = null
        val operands = ArrayList<PObj>(8)
        val parser = ContentParser(content)

        fun emitSpace() {
            if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
        }
        fun show(bytes: ByteArray) {
            if (pendingSpace) { emitSpace(); pendingSpace = false }
            val text = font.decode(bytes)
            val width = font.advance(bytes, size)
            if (text.isNotEmpty()) {
                out.append(text)
                if (out.length > PdfText.MAX_CHARS) throw StopPdf()
            }
            advance += width
        }
        fun newline() {
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
            pendingSpace = false
            advance = 0.0
        }

        try {
            while (true) {
                val token = parser.next() ?: break
                when (token) {
                    is PStr, is PNum, is PName, is PArr, is PBool -> { operands.add(token); continue }
                    is POp -> {
                        when (token.op) {
                            "Tf" -> {
                                val nameOperand = operands.getOrNull(operands.size - 2) as? PName
                                val sizeOperand = operands.lastOrNull() as? PNum
                                if (nameOperand != null) font = fonts[nameOperand.name] ?: CMapFont.empty(false)
                                if (sizeOperand != null && sizeOperand.value > 0) size = sizeOperand.value
                            }
                            "Tj" -> (operands.lastOrNull() as? PStr)?.let { show(it.bytes) }
                            "'" -> { newline(); (operands.lastOrNull() as? PStr)?.let { show(it.bytes) } }
                            "\"" -> { newline(); (operands.lastOrNull() as? PStr)?.let { show(it.bytes) } }
                            "TJ" -> {
                                val arr = operands.lastOrNull() as? PArr
                                arr?.items?.forEach { item ->
                                    when (item) {
                                        is PStr -> show(item.bytes)
                                        is PNum -> {
                                            val shift = -item.value / 1000.0 * size
                                            advance += shift
                                            if (shift > 0.22 * size) pendingSpace = true
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            "Td", "TD" -> {
                                val dx = (operands.getOrNull(operands.size - 2) as? PNum)?.value ?: 0.0
                                val dy = (operands.getOrNull(operands.size - 1) as? PNum)?.value ?: 0.0
                                val gap = dx - advance
                                if (kotlin.math.abs(dy) > 0.6 * size) newline()
                                else if (gap > 0.2 * size) pendingSpace = true
                                lineX += dx
                                advance = 0.0
                            }
                            "Tm" -> {
                                val e = (operands.getOrNull(operands.size - 2) as? PNum)?.value ?: 0.0
                                val f = (operands.lastOrNull() as? PNum)?.value ?: 0.0
                                val previousY = lastLineY
                                if (previousY != null && kotlin.math.abs(f - previousY) > 0.6 * size) newline()
                                else if (previousY != null && e - (lineX + advance) > 0.2 * size) pendingSpace = true
                                lineX = e
                                lastLineY = f
                                advance = 0.0
                            }
                            "T*" -> newline()
                            "ET", "BT" -> newline()
                            else -> Unit
                        }
                        operands.clear()
                    }
                    else -> Unit
                }
            }
        } catch (_: StopPdf) {
        }
    }

    // ---------------------------------------------------------- helpers
    fun resolve(obj: PObj?): PObj? {
        var current = obj
        var hops = 0
        while (current is PRef && hops++ < 32) {
            current = objects[current.num]
        }
        return current
    }

    private fun streamBytes(obj: PObj?): ByteArray? {
        val stream = obj as? PStream ?: return null
        return runCatching { decode(stream) }.getOrNull()
    }

    private fun decode(stream: PStream): ByteArray {
        val filters = when (val f = resolve(stream.dict["Filter"])) {
            is PName -> listOf(f.name)
            is PArr -> f.items.mapNotNull { name(it) }
            else -> emptyList()
        }
        var data = stream.raw
        for (filter in filters) {
            data = when (filter) {
                "FlateDecode", "Fl" -> inflate(data)
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "ASCII85Decode", "A85" -> ascii85(data)
                else -> data
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        runCatching {
            InflaterInputStream(data.inputStream()).use { return it.readBytes() }
        }
        return runCatching {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val out = ByteArray(data.size * 6 + 1024)
            val n = inflater.inflate(out)
            inflater.end()
            out.copyOf(n)
        }.getOrDefault(ByteArray(0))
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size / 2)
        var hi = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            val v = when {
                c in 48..57 -> c - 48
                c in 65..70 -> c - 55
                c in 97..102 -> c - 87
                c == 62 -> break
                else -> continue
            }
            if (hi < 0) hi = v else { out.add(((hi shl 4) or v).toByte()); hi = -1 }
        }
        if (hi >= 0) out.add((hi shl 4).toByte())
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var tuple = 0
        var count = 0
        var i = 0
        if (data.size > 2 && data[0].toInt() == 60) i = 2
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            if (c == 126) break
            if (c in 33..117) {
                tuple = tuple * 85 + (c - 33)
                if (++count == 5) {
                    for (shift in intArrayOf(24, 16, 8, 0)) out.write((tuple shr shift) and 0xFF)
                    tuple = 0; count = 0
                }
            }
            i++
        }
        if (count > 0) {
            for (k in count until 5) tuple = tuple * 85 + 84
            for (shift in intArrayOf(24, 16, 8, 0)) out.write((tuple shr shift) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun name(obj: PObj?): String? = (obj as? PName)?.name
    private fun startsWith(pos: Int, text: String): Boolean {
        if (pos < 0 || pos + text.length > src.size) return false
        for (k in text.indices) if (src[pos + k].toInt() != text[k].code) return false
        return true
    }
    private fun indexOf(text: String, from: Int): Int {
        val target = text.toByteArray(Charsets.ISO_8859_1)
        var i = maxOf(from, 0)
        while (i <= src.size - target.size) {
            var k = 0
            while (k < target.size && src[i + k] == target[k]) k++
            if (k == target.size) return i
            i++
        }
        return -1
    }
    private fun lastIndexOf(text: String, from: Int = src.size): Int {
        val target = text.toByteArray(Charsets.ISO_8859_1)
        var i = minOf(from, src.size - target.size)
        while (i >= 0) {
            var k = 0
            while (k < target.size && src[i + k] == target[k]) k++
            if (k == target.size) return i
            i--
        }
        return -1
    }
    private fun skipSpaces(pos: Int): Int {
        var i = pos
        while (i < src.size) {
            val c = src[i].toInt().toChar()
            if (c in PDF_WS) i++ else if (c == '%') { while (i < src.size && src[i].toInt() != 10 && src[i].toInt() != 13) i++ } else break
        }
        return i
    }
    private fun digits(v: Int): Int = v.toString().length
    private fun readUInt(pos: Int): Int? {
        var i = pos
        var value = 0
        var any = false
        while (i < src.size) {
            val c = src[i].toInt() and 0xFF
            if (c in 48..57) { value = value * 10 + (c - 48); any = true; i++ } else break
        }
        return if (any) value else null
    }
}

private class StopPdf : RuntimeException()

private class POp(val op: String) : PObj

// ------------------------------------------------------------------ parser de objetos

private class Parser(private val src: ByteArray, var pos: Int) {

    fun parseObject(depth: Int): PObj {
        if (depth > 40) return PNull
        skip()
        if (pos >= src.size) return PNull
        return when (val c = src[pos].toInt().toChar()) {
            '<' -> if (pos + 1 < src.size && src[pos + 1].toInt().toChar() == '<') parseDict(depth) else parseHexString()
            '(' -> parseLiteralString()
            '[' -> parseArray(depth)
            '/' -> parseName()
            't', 'f' -> parseWordLike()
            'n' -> { parseWordLike() }
            else -> parseNumberOrRef()
        }
    }

    private fun parseDict(depth: Int): PDict {
        pos += 2
        val dict = PDict()
        while (pos < src.size) {
            skip()
            if (pos + 1 < src.size && src[pos].toInt().toChar() == '>' && src[pos + 1].toInt().toChar() == '>') { pos += 2; return dict }
            val key = parseObject(depth + 1) as? PName ?: break
            val value = parseObject(depth + 1)
            dict.map[key.name] = value
        }
        return dict
    }

    private fun parseArray(depth: Int): PArr {
        pos++
        val arr = PArr()
        while (pos < src.size) {
            skip()
            if (src[pos].toInt().toChar() == ']') { pos++; return arr }
            arr.items.add(parseObject(depth + 1))
        }
        return arr
    }

    private fun parseHexString(): PStr {
        pos++
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (pos < src.size) {
            val c = src[pos].toInt() and 0xFF
            pos++
            if (c == 62) break
            val v = when {
                c in 48..57 -> c - 48
                c in 65..70 -> c - 55
                c in 97..102 -> c - 87
                else -> continue
            }
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return PStr(out.toByteArray())
    }

    private fun parseLiteralString(): PStr {
        pos++
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (pos < src.size && depth > 0) {
            val c = src[pos].toInt() and 0xFF
            pos++
            when (c) {
                40 -> { depth++; out.write(c) }
                41 -> { depth--; if (depth > 0) out.write(c) }
                92 -> {
                    if (pos >= src.size) break
                    val e = src[pos].toInt() and 0xFF
                    pos++
                    when (e) {
                        110 -> out.write(10)
                        114 -> out.write(13)
                        116 -> out.write(9)
                        98 -> out.write(8)
                        102 -> out.write(12)
                        40, 41, 92 -> out.write(e)
                        13 -> { if (pos < src.size && src[pos].toInt() == 10) pos++ }
                        10 -> Unit
                        in 48..55 -> {
                            var v = e - 48
                            var count = 1
                            while (count < 3 && pos < src.size) {
                                val d = src[pos].toInt() and 0xFF
                                if (d in 48..55) { v = v * 8 + (d - 48); pos++; count++ } else break
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(e)
                    }
                }
                else -> out.write(c)
            }
        }
        return PStr(out.toByteArray())
    }

    private fun parseName(): PName {
        pos++
        val sb = StringBuilder()
        while (pos < src.size) {
            val c = src[pos].toInt() and 0xFF
            if (c <= 32 || c == 47 || c == 91 || c == 93 || c == 60 || c == 62 || c == 40 || c == 41) break
            if (c == 35 && pos + 2 < src.size) {
                val hex = String(src, pos + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                if (hex != null) { sb.append(hex.toChar()); pos += 3; continue }
            }
            sb.append(c.toChar())
            pos++
        }
        return PName(sb.toString())
    }

    private fun parseWordLike(): PObj {
        val start = pos
        while (pos < src.size) {
            val c = src[pos].toInt() and 0xFF
            if (c <= 32 || c == 47 || c == 91 || c == 93 || c == 60 || c == 62) break
            pos++
        }
        val word = String(src, start, pos - start, Charsets.ISO_8859_1)
        return when (word) {
            "true" -> PBool(true)
            "false" -> PBool(false)
            "null" -> PNull
            else -> PName(word)
        }
    }

    private fun parseNumberOrRef(): PObj {
        val start = pos
        var sawDigit = false
        var isReal = false
        while (pos < src.size) {
            val c = src[pos].toInt().toChar()
            when {
                c in '0'..'9' -> { sawDigit = true; pos++ }
                c == '.' || c == '-' || c == '+' -> { isReal = isReal || c == '.'; pos++ }
                else -> break
            }
        }
        if (!sawDigit) { if (pos < src.size) pos++ else pos = src.size; return PNull }
        val text = String(src, start, pos - start, Charsets.ISO_8859_1)
        if (isReal) return PNum(text.toDoubleOrNull() ?: 0.0)
        val value = text.toIntOrNull() ?: return PNum(0.0)
        // possível referência indireta "12 0 R"
        val save = pos
        skip()
        val genStart = pos
        var gen = 0
        var genDigits = false
        while (pos < src.size && src[pos].toInt().toChar() in '0'..'9') { gen = gen * 10 + (src[pos].toInt() - 48); pos++; genDigits = true }
        if (genDigits) {
            skip()
            if (pos < src.size && src[pos].toInt().toChar() == 'R') {
                pos++
                return PRef(value)
            }
        }
        pos = save
        return PNum(value.toDouble())
    }

    private fun skip() {
        while (pos < src.size) {
            val c = src[pos].toInt() and 0xFF
            if (c == 37) { while (pos < src.size && src[pos].toInt() != 10 && src[pos].toInt() != 13) pos++ }
            else if (c <= 32) pos++
            else return
        }
    }
}

// ------------------------------------------------------------------ parser de conteúdo

private class ContentParser(private val src: ByteArray) {
    private var pos = 0

    fun next(): PObj? {
        skip()
        if (pos >= src.size) return null
        val c = src[pos].toInt().toChar()
        return when {
            c == '(' || c == '<' || c == '[' || c == '/' -> Parser(src, pos).let { p ->
                val obj = p.parseObject(0)
                pos = p.pos
                obj
            }
            c == '+' || c == '-' || c == '.' || c in '0'..'9' -> Parser(src, pos).let { p ->
                val obj = p.parseObject(0)
                pos = p.pos
                obj
            }
            else -> {
                val start = pos
                while (pos < src.size) {
                    val ch = src[pos].toInt().toChar()
                    if (ch in PDF_WS || ch == '/' || ch == '[' || ch == '(' || ch == '<') break
                    pos++
                }
                if (pos == start) { pos++; return POp("") }
                POp(String(src, start, pos - start, Charsets.ISO_8859_1))
            }
        }
    }

    private fun skip() {
        while (pos < src.size) {
            val c = src[pos].toInt() and 0xFF
            if (c == 37) { while (pos < src.size && src[pos].toInt() != 10 && src[pos].toInt() != 13) pos++ }
            else if (c <= 32) pos++
            else return
        }
    }
}

// ------------------------------------------------------------------ CMap / decodificação

private class CMapFont(
    private val map: HashMap<Int, String>,
    private val composite: Boolean,
) {
    private val widths = HashMap<Int, Double>()
    private var defaultWidth = if (composite) 1000.0 else 500.0

    fun loadWidths(font: PDict, file: PdfFile) {
        if (composite) { defaultWidth = 1000.0; return }
        val first = (file.resolve(font["FirstChar"]) as? PNum)?.value?.toInt() ?: return
        val arr = file.resolve(font["Widths"]) as? PArr ?: return
        for ((i, item) in arr.items.withIndex()) {
            val w = (file.resolve(item) as? PNum)?.value ?: continue
            if (w > 0) widths[first + i] = w
        }
    }

    /** largura aproximada do trecho, em unidades de texto (já multiplicada pelo tamanho) */
    fun advance(bytes: ByteArray, size: Double): Double {
        if (bytes.isEmpty()) return 0.0
        if (composite) return size * (bytes.size / 2.0)
        var units = 0.0
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            units += widths[code] ?: if (code == 32) 250.0 else defaultWidth
        }
        return units / 1000.0 * size
    }

    fun decode(bytes: ByteArray): String {
        if (map.isEmpty()) return latin1(bytes)
        val sb = StringBuilder(bytes.size)
        if (composite) {
            var i = 0
            while (i + 1 < bytes.size) {
                val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val mapped = map[code]
                if (mapped != null) sb.append(mapped) else sb.append(latin1(bytes.copyOfRange(i, i + 2)))
                i += 2
            }
            if (i < bytes.size) sb.append(latin1(bytes.copyOfRange(i, bytes.size)))
        } else {
            for (b in bytes) {
                val code = b.toInt() and 0xFF
                val mapped = map[code]
                if (mapped != null) sb.append(mapped) else sb.append(latin1(byteArrayOf(b)))
            }
        }
        return sb.toString()
    }

    companion object {
        fun empty(composite: Boolean) = CMapFont(HashMap(), composite)

        fun parse(data: ByteArray, compositeFont: Boolean): CMapFont {
            val text = String(data, Charsets.ISO_8859_1)
            val map = HashMap<Int, String>()
            var composite = compositeFont
            // codespace: revela se os códigos têm 1 ou 2 bytes
            if (Regex("<([0-9A-Fa-f]{4,8})>\\s*<([0-9A-Fa-f]{4,8})>").containsMatchIn(text)) composite = composite || data.size > 0 && text.contains("begincodespacerange")
            for (block in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                val pair = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1])
                for (m in pair) {
                    val code = m.groupValues[1].toIntOrNull(16) ?: continue
                    map[code] = unicode(m.groupValues[2])
                }
            }
            for (block in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
                val body = block.groupValues[1]
                val simple = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(body)
                for (m in simple) {
                    val lo = m.groupValues[1].toIntOrNull(16) ?: continue
                    val hi = m.groupValues[2].toIntOrNull(16) ?: continue
                    val dst = m.groupValues[3].toIntOrNull(16) ?: continue
                    if (hi - lo > 4096) continue
                    for (k in 0..(hi - lo)) map[lo + k] = unicode((dst + k).toString(16))
                }
                val arrayForm = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).findAll(body)
                for (m in arrayForm) {
                    val lo = m.groupValues[1].toIntOrNull(16) ?: continue
                    val targets = Regex("<([0-9A-Fa-f]+)>").findAll(m.groupValues[3]).map { it.groupValues[1] }.toList()
                    targets.forEachIndexed { k, hex -> map[lo + k] = unicode(hex) }
                }
            }
            if (map.isEmpty()) throw IllegalArgumentException("CMap vazio")
            return CMapFont(map, composite)
        }

        private fun unicode(hex: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 4 <= hex.length) {
                val unit = hex.substring(i, i + 4).toIntOrNull(16) ?: break
                sb.append(unit.toChar())
                i += 4
            }
            if (sb.isEmpty() && hex.isNotEmpty()) {
                val v = hex.toIntOrNull(16) ?: return ""
                if (v in 32..0xFFFF) sb.append(v.toChar())
            }
            return sb.toString()
        }

        private fun latin1(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)
    }
}
