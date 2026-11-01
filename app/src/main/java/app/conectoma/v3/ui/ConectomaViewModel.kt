package app.conectoma.v3.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.conectoma.v3.ConectomaApp
import app.conectoma.v3.engine.ConnectomeConstants
import app.conectoma.v3.engine.HyperOsBridge
import app.conectoma.v3.engine.ImpulseEvent
import app.conectoma.v3.engine.LearningTrace
import app.conectoma.v3.engine.Lexeme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val booting: Boolean = true,
    val bootProgress: Int = 0,
    val neurons: Int = ConnectomeConstants.NEURONS,
    val synapses: Int = ConnectomeConstants.SYNAPSES,
    val meanWeight: Float = 0.2f,
    val active: Int = 0,
    val lexicon: Int = 0,
    val understood: Long = 0,
    val impulses: Long = 0,
    val pulse: ImpulseEvent? = null,
    val traces: List<LearningTrace> = emptyList(),
    val log: List<String> = emptyList(),
    val listening: Boolean = false,
    val hyper: HyperOsBridge.Snapshot = HyperOsBridge.snapshot(),
    val hyperHint: String = "",
    val query: String = "",
    val lookup: Lexeme? = null,
    val neighbors: List<Pair<Lexeme, Float>> = emptyList()
)

class ConectomaViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ConectomaApp
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        _state.value = _state.value.copy(
            hyperHint = HyperOsBridge.reasoningHint(_state.value.hyper),
            lexicon = app.dictionary.known()
        )
        viewModelScope.launch(Dispatchers.Default) {
            if (!app.graph.generated) {
                app.graph.generate { done ->
                    _state.value = _state.value.copy(
                        bootProgress = ((done.toFloat() / ConnectomeConstants.SYNAPSES) * 100).toInt()
                    )
                }
            }
            refresh("conectoma gerado: ${ConnectomeConstants.NEURONS} neurônios, ${ConnectomeConstants.SYNAPSES} sinapses")
            _state.value = _state.value.copy(booting = false)
        }
    }

    fun setListening(v: Boolean) {
        _state.value = _state.value.copy(listening = v)
    }

    fun speak(text: String) = viewModelScope.launch {
        val traces = withContext(Dispatchers.Default) { app.motor.learnUtterance(text) }
        push("fala: $text", traces)
    }

    fun teach(text: String) = speak(text)

    fun learnUrl(url: String) = viewModelScope.launch {
        runCatching {
            val lesson = withContext(Dispatchers.IO) { app.urls.learn(url) }
            val traces = withContext(Dispatchers.Default) { app.motor.learnFromUrl(lesson) }
            push("url ${lesson.title} (${lesson.tokens} tokens)", traces)
        }.onFailure {
            note("falha ao ler URL: ${it.message}")
        }
    }

    fun autonomousOnce() = viewModelScope.launch {
        val trace = withContext(Dispatchers.IO) { app.autonomous.tick() }
        if (trace != null) push("ciclo autônomo", listOf(trace))
        else note("ainda há poucos conceitos para um par distante")
    }

    fun lookup(word: String) = viewModelScope.launch {
        _state.value = _state.value.copy(query = word)
        val lex = app.dictionary.lookup(word) ?: withContext(Dispatchers.IO) {
            val def = app.dictionary.fetchDefinition(word)
            if (def != null) app.dictionary.remember(word, def, "dicionário") else null
        }
        val neighbors = if (lex != null) app.dictionary.nearest(word) else emptyList()
        _state.value = _state.value.copy(
            lookup = lex,
            neighbors = neighbors,
            lexicon = app.dictionary.known()
        )
        if (lex != null && neighbors.size >= 2) {
            val traces = withContext(Dispatchers.Default) {
                listOf(app.motor.learnPair(word, neighbors[1].first.word, "consulta de dicionário"))
            }
            push("consulta: $word", traces)
        }
    }

    private fun push(msg: String, traces: List<LearningTrace>) {
        val pulse = traces.maxByOrNull { it.impulse.dopamine }?.impulse
        val log = (_state.value.log + msg + traces.map {
            val tag = if (it.impulse.understood) "impulso DA" else "ensaio"
            "$tag ${it.left} ↔ ${it.right}  sem=${fmt(it.semantic)}  mat=${fmt(it.math)}  hebb=${fmt(it.hebb)}"
        }).takeLast(40)
        _state.value = _state.value.copy(
            traces = (traces + _state.value.traces).take(24),
            pulse = pulse ?: _state.value.pulse,
            log = log,
            meanWeight = app.graph.meanWeight(),
            active = app.graph.activeCount(),
            lexicon = app.dictionary.known(),
            understood = app.dopamine.understoodCount,
            impulses = app.dopamine.totalImpulses
        )
    }

    private fun note(msg: String) {
        _state.value = _state.value.copy(log = (_state.value.log + msg).takeLast(40))
    }

    private fun refresh(msg: String) {
        _state.value = _state.value.copy(
            meanWeight = if (app.graph.generated) app.graph.meanWeight() else 0f,
            active = app.graph.activeCount(),
            lexicon = app.dictionary.known(),
            log = _state.value.log + msg
        )
    }

    private fun fmt(v: Float) = "%.2f".format(v)
}
