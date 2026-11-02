package com.conectoma.app

import com.conectoma.app.core.PdfText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class PdfTextTest {

    /** Monta um PDF 1.4 de uma página com Fluxo comprimido e CMap ToUnicode. */
    private fun samplePdf(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        fun write(b: ByteArray) = out.write(b)
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            1 begincodespacerange
            <00> <FF>
            endcodespacerange
            1 beginbfrange
            <20> <7E> <0020>
            endbfrange
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)

        val deflated = deflate(content)
        write("%PDF-1.4\n")
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        write("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
            "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
        write("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 6 0 R >>\nendobj\n")
        write("5 0 obj\n<< /Length ${deflated.size} /Filter /FlateDecode >>\nstream\n")
        write(deflated)
        write("\nendstream\nendobj\n")
        write("6 0 obj\n<< /Length ${cmap.size} >>\nstream\n")
        write(cmap)
        write("\nendstream\nendobj\n")
        write("trailer\n<< /Root 1 0 R /Size 7 >>\n")
        write("%%EOF\n")
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(data.size + 128)
        val n = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(n)
    }

    @Test
    fun extractsTextFromACompressedContentStream() {
        val content = "BT /F1 24 Tf 72 700 Td (Hello conectoma) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val text = PdfText.extract(samplePdf(content))
        assertTrue("texto extraído: '$text'", text.contains("Hello conectoma"))
    }

    @Test
    fun handlesTJArraysAndHexStrings() {
        val content = ("BT /F1 12 Tf 72 700 Td [(Neural) -300 (plasticity)] TJ " +
            "0 -20 Td <41 42 43> Tj ET").toByteArray(Charsets.ISO_8859_1)
        val text = PdfText.extract(samplePdf(content))
        assertTrue("texto: '$text'", text.contains("Neural"))
        assertTrue("texto: '$text'", text.contains("plasticity"))
        assertTrue("texto: '$text'", text.contains("ABC"))
    }

    @Test
    fun rejectsNonPdf() {
        runCatching { PdfText.extract("isto não é um pdf".toByteArray()) }
            .onSuccess { throw AssertionError("deveria recusar") }
    }

    @Test
    fun extractsARealPdfWhenProvided() {
        val path = System.getenv("CONECTOMA_PDF_TEST") ?: return
        val file = File(path)
        if (!file.exists()) return
        val text = PdfText.extract(file.readBytes(), maxPages = 20)
        println("PDF real: ${file.name} -> ${text.length} caracteres")
        println("amostra: " + text.take(400).replace("\n", " | "))
        assertTrue("extraiu pouco texto: ${text.length}", text.length > 5)
    }
}
