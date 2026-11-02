package com.conectoma.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.conectoma.app.core.Brain
import com.conectoma.app.core.Numbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val meta: String? = null,
)

data class UiState(
    val stage: String = "iniciando",
    val progress: Float = 0f,
    val ready: Boolean = false,
    val error: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val stats: Brain.Stats? = null,
    val ingests: List<Brain.IngestReport> = emptyList(),
    val autonomyLog: List<String> = emptyList(),
    val autonomyEnabled: Boolean = false,
    val lastReport: String? = null,
    val pulse: Int = 0,
    val thinking: Int = 0,
    val pdfName: String? = null,
    val pdfPages: Int = 0,
    val pdfChars: Int = 0,
    val pdfNote: String? = null,
    val pdfPreviews: List<android.graphics.Bitmap> = emptyList(),
)

class ConectomaViewModel(app: Application) : AndroidViewModel(app) {

    val brain: Brain = Brain.get(app)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var seq = 0L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(ready = false, error = null, stage = "abrindo o conectoma") }
            brain.initialize { stage, progress ->
                _state.update { it.copy(stage = stage, progress = progress) }
            }
            if (brain.ready) {
                val autonomyOn = getApplication<Application>()
                    .getSharedPreferences("conectoma", Application.MODE_PRIVATE)
                    .getBoolean("autonomy", false)
                // never let a formatting slip take the app down at boot
                val greeting = runCatching {
                    "Conectoma acordado.\n\n${brain.connectome.nodeCount} neurônios do cérebro da mosca, " +
                        "${Numbers.grouped(brain.connectome.edgeCount)} sinapses, " +
                        "${Numbers.grouped(brain.semantics.baseCount)} palavras no léxico.\n\n" +
                        "Me fale de alguma coisa, cole um URL, ou me deixe aprender sozinho."
                }.getOrElse { "Conectoma acordado." }
                _state.update {
                    it.copy(
                        ready = true, progress = 1f, stage = "pronto",
                        autonomyEnabled = autonomyOn,
                        stats = runCatching { brain.stats() }.getOrNull(),
                        messages = listOf(
                            ChatMessage(++seq, false, greeting, "substrato carregado")
                        ),
                    )
                }
                startTicker()
            } else {
                _state.update { it.copy(error = brain.loadError ?: "falha desconhecida") }
            }
        }
    }

    private fun startTicker() {
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1200)
                val st = runCatching { brain.stats() }.getOrNull() ?: continue
                _state.update { it.copy(stats = st) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(20_000)
                if (!_state.value.autonomyEnabled) continue
                val media = brain.autonomousStep(allowNetwork = true)
                _state.update {
                    it.copy(
                        autonomyLog = (listOf(media.description) + it.autonomyLog).take(24),
                        pulse = if (media.insight > 0.45f) it.pulse + 1 else it.pulse,
                        stats = brain.stats(),
                    )
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || _state.value.busy) return
        val user = ChatMessage(++seq, true, text.trim())
        _state.update { it.copy(messages = it.messages + user, busy = true, thinking = it.thinking + 1) }
        viewModelScope.launch(Dispatchers.Default) {
            val response = runCatching { brain.ask(text) }
            _state.update { st ->
                st.copy(
                    busy = false,
                    thinking = (st.thinking - 1).coerceAtLeast(0),
                    messages = st.messages + ChatMessage(
                        ++seq, false,
                        response.getOrNull()?.text ?: "Não consegui raciocinar agora: ${response.exceptionOrNull()?.message}",
                        response.getOrNull()?.let {
                            "${it.spikes} disparos · ${it.newLinks} links novos · dopamina ${pct(it.dopamine)} · ${it.elapsedMs} ms"
                        },
                    ),
                    pulse = if ((response.getOrNull()?.dopamine ?: 0f) > 0.25f) st.pulse + 1 else st.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: st.stats,
                )
            }
        }
    }

    fun learnUrl(url: String) {
        val clean = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        if (clean.length < 8) {
            _state.update { it.copy(lastReport = "URL inválido") }
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val report = brain.ingest(clean)
            _state.update {
                it.copy(
                    busy = false,
                    ingests = (listOf(report) + it.ingests).take(12),
                    lastReport = report.error?.let { e -> "Falhou: $e" }
                        ?: "“${report.title}” — ${report.newWords} palavras novas, ${report.links} sinapses reforçadas",
                    pulse = if (report.reward > 0.5f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                )
            }
        }
    }

    /** Lê um PDF escolhido no seletor do sistema, mostra as páginas e aprende com o texto. */
    fun learnPdf(uri: android.net.Uri) {
        _state.update { it.copy(busy = true, pdfNote = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching { com.conectoma.app.core.PdfOnDevice.read(getApplication(), uri) }
            val doc = loaded.getOrNull()
            if (doc == null) {
                _state.update {
                    it.copy(
                        busy = false,
                        pdfNote = "falhou ao abrir: ${loaded.exceptionOrNull()?.message ?: "erro"}",
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    pdfName = doc.displayName,
                    pdfPages = doc.pageCount,
                    pdfChars = doc.chars,
                    pdfPreviews = doc.previews,
                    pdfNote = doc.note,
                )
            }
            if (doc.text.length < 80) {
                _state.update {
                    it.copy(
                        busy = false,
                        lastReport = "${doc.displayName}: sem texto selecionável (${doc.pageCount} páginas só de imagem)",
                    )
                }
                return@launch
            }
            val report = brain.ingestText("PDF: ${doc.displayName}", "pdf://${doc.displayName}", doc.text)
            _state.update {
                it.copy(
                    busy = false,
                    ingests = (listOf(report) + it.ingests).take(12),
                    lastReport = "“${doc.displayName}” — ${report.newWords} palavras novas, " +
                        "${report.links} sinapses, ${doc.chars} caracteres lidos",
                    pulse = if (report.reward > 0.5f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                    messages = it.messages + ChatMessage(
                        ++seq, false,
                        "Li **${doc.displayName}** (${doc.pageCount} páginas). " +
                            "Extraí ${Numbers.grouped(doc.chars)} caracteres, aprendi ${report.newWords} palavras " +
                            "e reforcei ${report.links} sinapses. O que você quer saber sobre ele?",
                        "PDF · ${report.concepts.take(4).joinToString(" · ")}",
                    ),
                )
            }
        }
    }

    fun autonomousOnce() {        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(thinking = it.thinking + 1) }
            val media = brain.autonomousStep(allowNetwork = true)
            _state.update {
                it.copy(
                    thinking = (it.thinking - 1).coerceAtLeast(0),
                    autonomyLog = (listOf(media.description) + it.autonomyLog).take(24),
                    messages = it.messages + ChatMessage(
                        ++seq, false,
                        "Fui aprender sozinho: ${media.description}.",
                        "autônomo · ${media.newWords} palavras novas · insight ${"%.2f".format(media.insight)}",
                    ),
                    pulse = if (media.insight > 0.45f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                )
            }
        }
    }

    /** ponder without the network: bridge two distant concepts already known */
    fun thinkAloud() {
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(thinking = it.thinking + 1) }
            val media = runCatching { brain.autonomousStep(allowNetwork = false) }.getOrNull()
            val text = if (media == null) "Não consegui pensar agora."
            else "Pensando sozinho: ${media.description}."
            _state.update {
                it.copy(
                    thinking = (it.thinking - 1).coerceAtLeast(0),
                    messages = it.messages + ChatMessage(++seq, false, text, "reflexão interna"),
                    pulse = if ((media?.insight ?: 0f) > 0.45f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                )
            }
        }
    }

    fun setAutonomy(enabled: Boolean) {
        _state.update { it.copy(autonomyEnabled = enabled) }
        getApplication<Application>().getSharedPreferences("conectoma", Application.MODE_PRIVATE)
            .edit().putBoolean("autonomy", enabled).apply()
        runCatching {
            if (enabled) {
                com.conectoma.app.hyperos.AutonomousLearningService.start(getApplication())
            } else {
                com.conectoma.app.hyperos.AutonomousLearningService.stop(getApplication())
            }
        }
    }

    fun saveNow() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { brain.save() }
            _state.update { it.copy(lastReport = "memória gravada") }
        }
    }

    fun distance(a: String, b: String): Triple<String, String, Float>? {
        val ia = brain.semantics.idOf(a)
        val ib = brain.semantics.idOf(b)
        if (ia < 0 || ib < 0) return null
        return Triple(brain.semantics.name(ia), brain.semantics.name(ib), brain.semantics.distance(ia, ib))
    }

    fun lookup(word: String): Int = brain.semantics.idOf(word)

    override fun onCleared() {
        runCatching { brain.save() }
        super.onCleared()
    }

    companion object {
        fun pct(v: Float) = Numbers.percent(v)
        fun formatEdge(e: Int) = Numbers.grouped(e)
    }
}
