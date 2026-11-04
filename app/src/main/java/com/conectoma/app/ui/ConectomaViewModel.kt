package com.conectoma.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.conectoma.app.core.Brain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Mensagem do chat — apenas o texto final refinado.
 * Sem campo de telemetria: métricas internas ficam em [UiState.stats] e
 * somente nos painéis do Cérebro, nunca no balão da conversa.
 */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
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
    val spontaneousEnabled: Boolean = true,
    val lastReport: String? = null,
    val pulse: Int = 0,
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

    // ids e instantes compartilhados entre coroutines: só átomicos/voláteis
    private val seq = AtomicLong(0L)
    private val loading = AtomicBoolean(false)
    private val tickerStarted = AtomicBoolean(false)

    @Volatile private var lastInteraction = System.currentTimeMillis()
    @Volatile private var lastSpontaneous = System.currentTimeMillis()

    init {
        load()
    }

    /** single-flight: só um boot por vez, e nunca dois tickers concorrentes. */
    fun load() {
        if (!loading.compareAndSet(false, true)) return
        // fora da thread principal: montar 3,75 M de sinapses não pode travar a UI
        viewModelScope.launch(Dispatchers.Default) {
            try {
                _state.update { it.copy(ready = false, error = null, stage = "abrindo o conectoma") }
                brain.initialize { stage, progress ->
                    _state.update { it.copy(stage = stage, progress = progress) }
                }
                if (brain.ready) {
                    val prefs = getApplication<Application>()
                        .getSharedPreferences("conectoma", Application.MODE_PRIVATE)
                    val autonomyOn = prefs.getBoolean("autonomy", false)
                    val spontaneousOn = prefs.getBoolean("spontaneous", true)
                    // never let a formatting slip take the app down at boot
                    val greeting = runCatching {
                        "Conectoma acordado.\n\n" +
                            "Sou o seu assistente pessoal de estudos: leio, guardo e respondo " +
                            "sobre qualquer coisa que você trouxer — texto, URL ou PDF.\n\n" +
                            "Me fale de alguma coisa, cole um URL, ou me deixe aprender sozinho."
                    }.getOrElse { "Conectoma acordado." }
                    _state.update {
                        it.copy(
                            ready = true, progress = 1f, stage = "pronto",
                            autonomyEnabled = autonomyOn,
                            spontaneousEnabled = spontaneousOn,
                            stats = runCatching { brain.stats() }.getOrNull(),
                            messages = listOf(
                                ChatMessage(seq.incrementAndGet(), false, greeting)
                            ),
                        )
                    }
                    startTicker()
                } else {
                    _state.update { it.copy(error = brain.loadError ?: "falha desconhecida") }
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun startTicker() {
        if (!tickerStarted.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(1200)
                val st = runCatching { brain.stats() }.getOrNull() ?: continue
                _state.update { it.copy(stats = st) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                // economia de bateria: o próprio aparelho decide o intervalo
                // (20 min em economia de energia, 12 min com bateria baixa,
                //  90 s carregando no Wi-Fi…) e se a rede pode ser usada
                val eco = runCatching {
                    com.conectoma.app.core.BatteryEconomy.read(getApplication())
                }.getOrNull()
                delay(eco?.delayMs ?: 5 * 60_000L)
                if (!_state.value.autonomyEnabled) continue
                val allowNetwork = eco?.allowNetwork ?: false
                val media = runCatching { brain.autonomousStep(allowNetwork = allowNetwork) }.getOrNull() ?: continue
                _state.update {
                    it.copy(
                        autonomyLog = (listOf(media.description) + it.autonomyLog).take(24),
                        pulse = if (media.insight > 0.45f) it.pulse + 1 else it.pulse,
                        stats = brain.stats() ?: it.stats,
                    )
                }
            }
        }
        // voz espontânea: sem pergunta, sem comando — fala quando o estado interno pede
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(10_000)
                val st = _state.value
                if (!st.ready || !st.spontaneousEnabled || st.busy) continue
                val now = System.currentTimeMillis()
                if (now - lastInteraction < IDLE_BEFORE_SPEAKING) continue
                if (now - lastSpontaneous < SPONTANEOUS_INTERVAL) continue
                val said = runCatching { brain.spontaneous() }.getOrNull() ?: continue
                lastSpontaneous = now
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(seq.incrementAndGet(), false, said.text),
                        autonomyLog = (listOf("falou sozinho sobre ${said.topic}") + it.autonomyLog).take(24),
                        stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                    )
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank() || _state.value.busy) return
        lastInteraction = System.currentTimeMillis()
        val user = ChatMessage(seq.incrementAndGet(), true, text.trim())
        _state.update { it.copy(messages = it.messages + user , busy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val response = runCatching { brain.ask(text) }
            _state.update { st ->
                st.copy(
                    busy = false,
                    // só o texto final refinado: sem disparos, cobertura, dopamina ou ms
                    messages = st.messages + ChatMessage(
                        seq.incrementAndGet(), false,
                        response.getOrNull()?.text ?: "Não consegui raciocinar agora: ${response.exceptionOrNull()?.message}",
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
        lastInteraction = System.currentTimeMillis()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val report = brain.ingest(clean)
            val firstFact = runCatching { brain.memory.recentFacts(1).firstOrNull() }.getOrNull()
            _state.update {
                it.copy(
                    busy = false,
                    ingests = (listOf(report) + it.ingests).take(12),
                    lastReport = report.error?.let { e -> "Falhou: $e" }
                        ?: "“${report.title}” — ${report.newWords} palavras novas, ${report.links} sinapses reforçadas, " +
                        "${brain.memory.size} materiais na memória",
                    pulse = if (report.reward > 0.5f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                    messages = if (report.error == null && firstFact != null) {
                        it.messages + ChatMessage(
                            seq.incrementAndGet(), false,
                            "Li **${report.title}**.\n\nUm trecho que guardei:\n\n" +
                                "“${firstFact.text.take(240)}”\n\n" +
                                "Agora posso responder usando o que li, não só o dicionário.",
                        )
                    } else it.messages,
                )
            }
        }
    }

    /** Lê um PDF escolhido no seletor do sistema, mostra as páginas e aprende com o texto. */
    fun learnPdf(uri: android.net.Uri) {
        lastInteraction = System.currentTimeMillis()
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
            val firstFact = runCatching { brain.memory.recentFacts(1).firstOrNull() }.getOrNull()
            _state.update {
                it.copy(
                    busy = false,
                    ingests = (listOf(report) + it.ingests).take(12),
                    lastReport = "“${doc.displayName}” — ${report.newWords} palavras novas, " +
                        "${report.links} sinapses, ${doc.chars} caracteres lidos",
                    pulse = if (report.reward > 0.5f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                    messages = it.messages + ChatMessage(
                        seq.incrementAndGet(), false,
                        "Li **${doc.displayName}** por inteiro, palavra por palavra. " +
                            (firstFact?.let { f -> "\n\nGuardei na memória: “${f.text.take(240)}”\n\n" } ?: "") +
                            "O que você quer saber sobre ele?",
                    ),
                )
            }
        }
    }

    fun autonomousOnce() {
        lastInteraction = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true) }
            val media = runCatching { brain.autonomousStep(allowNetwork = true) }.getOrNull()
            if (media == null) {
                _state.update { it.copy(busy = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    busy = false,
                    autonomyLog = (listOf(media.description) + it.autonomyLog).take(24),
                    messages = it.messages + ChatMessage(
                        seq.incrementAndGet(), false,
                        "Fui aprender sozinho: ${media.description}.",
                    ),
                    pulse = if (media.insight > 0.45f) it.pulse + 1 else it.pulse,
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                )
            }
        }
    }

    /** ponder without the network: bridge two distant concepts already known */
    fun thinkAloud() {
        lastInteraction = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(busy = true) }
            val media = runCatching { brain.autonomousStep(allowNetwork = false) }.getOrNull()
            val text = if (media == null) "Não consegui pensar agora."
            else "Pensando sozinho: ${media.description}."
            _state.update {
                it.copy(
                    busy = false,
                    messages = it.messages + ChatMessage(seq.incrementAndGet(), false, text),
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

    fun setSpontaneous(enabled: Boolean) {
        _state.update { it.copy(spontaneousEnabled = enabled) }
        getApplication<Application>().getSharedPreferences("conectoma", Application.MODE_PRIVATE)
            .edit().putBoolean("spontaneous", enabled).apply()
        if (enabled) {
            lastInteraction = 0L
            lastSpontaneous = 0L
        }
    }

    /** pedido explícito: faz o conectoma falar sem pergunta */
    fun spontaneousOnce() {
        lastInteraction = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.Default) {
            val said = runCatching { brain.spontaneous() }.getOrNull()
            if (said == null) {
                _state.update { it.copy(lastReport = "nada novo para falar agora") }
                return@launch
            }
            lastSpontaneous = System.currentTimeMillis()
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(seq.incrementAndGet(), false, said.text),
                    autonomyLog = (listOf("falou sozinho sobre ${said.topic}") + it.autonomyLog).take(24),
                    stats = runCatching { brain.stats() }.getOrNull() ?: it.stats,
                )
            }
        }
    }

    /** estado da bateria — a UI mostra o ritmo real do aprendizado autônomo */
    fun battery(): com.conectoma.app.core.BatteryEconomy.Report? =
        runCatching {
            com.conectoma.app.core.BatteryEconomy.read(getApplication<Application>())
        }.getOrNull()

    fun saveNow() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { brain.save() }
            _state.update { it.copy(lastReport = "memória gravada") }
        }
    }

    /**
     * Guarda a memória acumulada no aparelho num arquivo portátil
     * (`conectoma-seed.bin`, em Android/data/…/files). Colocando esse arquivo
     * em `app/src/main/assets/` e recompilando, o APK nasce com todo o
     * conhecimento que o conectoma já entendeu do mundo.
     */
    fun exportMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { brain.save() }
            val file = runCatching { brain.exportSeed() }.getOrNull()
            val msg = if (file != null) {
                "Memória guardada no aparelho:\n\n`${file.absolutePath}`\n\n" +
                    "É esse arquivo que vira o pacote de conhecimento do próximo APK."
            } else {
                "Ainda não há memória para guardar — ensine ou leia algo antes."
            }
            _state.update {
                it.copy(
                    lastReport = "memória exportada",
                    messages = it.messages + ChatMessage(seq.incrementAndGet(), false, msg),
                )
            }
        }
    }

    fun distance(a: String, b: String): Triple<String, String, Float>? {
        val semantics = brain.semanticsOrNull ?: return null
        val ia = semantics.idOf(a)
        val ib = semantics.idOf(b)
        if (ia < 0 || ib < 0) return null
        return Triple(semantics.name(ia), semantics.name(ib), semantics.distance(ia, ib))
    }

    fun lookup(word: String): Int = brain.semanticsOrNull?.idOf(word) ?: -1

    override fun onCleared() {
        runCatching { brain.save() }
        super.onCleared()
    }

    companion object {
        private const val IDLE_BEFORE_SPEAKING = 30_000L
        private const val SPONTANEOUS_INTERVAL = 45_000L
    }
}
