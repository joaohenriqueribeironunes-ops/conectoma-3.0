package com.conectoma.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Lê um PDF escolhido pelo usuário no seletor do sistema: extrai o texto para o
 * conectoma aprender e renderiza as primeiras páginas para visualização.
 */
object PdfOnDevice {

    class Document(
        val displayName: String,
        val pageCount: Int,
        val chars: Int,
        val text: String,
        val previews: List<Bitmap>,
        val note: String? = null,
    )

    fun read(context: Context, uri: Uri, previewPages: Int = 3, maxPages: Int = 40): Document {
        val name = displayName(context, uri)
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val file = File(dir, "entrada.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        } ?: throw IllegalStateException("não consegui abrir o arquivo")

        var pageCount = 0
        val previews = ArrayList<Bitmap>(previewPages)
        var renderer: PdfRenderer? = null
        var descriptor: ParcelFileDescriptor? = null

        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            pageCount = renderer.pageCount
            val limit = minOf(previewPages, pageCount)
            for (i in 0 until limit) {
                val page = renderer.openPage(i)
                val width = 420
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                previews.add(bitmap)
            }
        } catch (t: Throwable) {
            renderer?.close()
            renderer = null
            descriptor?.close()
            return Document(
                displayName = name,
                pageCount = 0,
                chars = 0,
                text = "",
                previews = emptyList(),
                note = when (t) {
                    is SecurityException -> "PDF protegido por senha"
                    else -> "não consegui abrir o PDF: ${t.message ?: t.javaClass.simpleName}"
                },
            )
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }

        var text = ""
        var note: String? = null
        try {
            if (file.length() > 48L * 1024 * 1024) {
                note = "PDF muito grande (${file.length() / (1024 * 1024)} MB) — usei só as primeiras páginas visuais"
            } else {
                text = PdfText.extract(file.readBytes(), maxPages = maxPages)
                if (text.length < 80) {
                    note = "esse PDF quase não tem texto (talvez seja digitalizado); usei só as imagens"
                }
            }
        } catch (t: Throwable) {
            note = when (t) {
                is IllegalStateException -> t.message ?: "PDF inválido"
                else -> "não consegui extrair o texto: ${t.message ?: t.javaClass.simpleName}"
            }
        } finally {
            runCatching { file.delete() }
        }

        return Document(name, pageCount, text.length, text, previews, note)
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) name = cursor.getString(index)
                    }
                }
        }
        val candidate = name ?: uri.lastPathSegment ?: "documento.pdf"
        return candidate.substringAfterLast('/').take(80).ifBlank { "documento.pdf" }
    }
}
