package com.conectoma.app.core

import kotlin.math.min
import kotlin.random.Random

/**
 * Memoria de trabalho com unidades de Izhikevich (modelo biologico de 2 variaveis).
 *
 * Equacoes classicas (Izhikevich 2003), integradas com dt em milissegundos
 * virtuais:
 *
 *   v' = 0.04 v^2 + 5 v + 140 - u + I
 *   u' = a (b v - u)
 *   se v >= 30 mV: v <- c, u <- u + d
 *
 * Cada slot guarda um conceito. O slot que dispara mantem o conceito ativo;
 * sem disparo, a ativacao decai. Isso da persistencia curta (contexto) sem
 * precisar de memoria externa.
 */
class WorkingMemory(val capacity: Int = 32) {

    val concept = IntArray(capacity) { -1 }
    val activation = FloatArray(capacity)
    val age = IntArray(capacity) { 99 }
    val v = FloatArray(capacity) { -65f }
    val u = FloatArray(capacity) { -13f }

    private val a = FloatArray(capacity)
    private val b = FloatArray(capacity)
    private val c = FloatArray(capacity)
    private val d = FloatArray(capacity)
    private val noise = Random(4242)

    /** turno em que o conceito entrou na mesa; usado para desempatar por recencia */
    private val stamp = IntArray(capacity) { -1 }
    private var clock = 0

    var spikes = 0
        private set

    init {
        for (i in 0 until capacity) {
            if (i % 5 == 0) {
                // fast spiking: inibicao/controle
                a[i] = 0.10f; b[i] = 0.20f; c[i] = -65f; d[i] = 2f
            } else {
                // regular spiking: manutencao de contexto
                a[i] = 0.02f; b[i] = 0.20f; c[i] = -65f; d[i] = 8f
            }
        }
    }

    /** avanca o relogio de contexto; chamado uma vez por turno */
    fun beginTurn() {
        clock++
    }

    /** adiciona ou reforca um conceito na mesa de trabalho */
    fun encode(ids: List<Int>, amplitude: Float) {
        for (id in ids) {
            if (id < 0) continue
            var slot = -1
            for (i in 0 until capacity) {
                if (concept[i] == id) {
                    slot = i
                    break
                }
            }
            if (slot < 0) {
                for (i in 0 until capacity) {
                    if (concept[i] < 0) {
                        slot = i
                        break
                    }
                }
            }
            if (slot < 0) {
                var weakest = 0
                for (i in 1 until capacity) {
                    if (activation[i] < activation[weakest]) weakest = i
                }
                slot = weakest
            }
            if (concept[slot] != id) {
                concept[slot] = id
                activation[slot] = amplitude.coerceIn(0f, 1f)
                v[slot] = -65f + 8f * activation[slot]
                u[slot] = b[slot] * v[slot]
            } else {
                activation[slot] = min(1f, activation[slot] + amplitude * 0.5f)
            }
            stamp[slot] = clock
            age[slot] = 0
        }
    }

    /**
     * Avanca a dinamica. `input[i]` e a corrente sinaptica que chega ao slot i
     * (normalmente a atividade media dos neuronios do complexo central ligados
     * aquela unidade).
     */
    fun step(input: FloatArray, modulator: Neuromodulator, dt: Float = 1f): Int {
        var fired = 0
        val gain = modulator.excitability
        val damping = 1f / modulator.inhibition.coerceAtLeast(0.35f)
        for (i in 0 until capacity) {
            val raw = if (i < input.size) input[i] else 0f
            val current = (raw * gain * damping).coerceIn(-28f, 42f) +
                (noise.nextFloat() - 0.5f) * 1.4f
            v[i] += dt * (0.04f * v[i] * v[i] + 5f * v[i] + 140f - u[i] + current)
            u[i] += dt * a[i] * (b[i] * v[i] - u[i])
            if (v[i] >= 30f) {
                v[i] = c[i]
                u[i] += d[i]
                activation[i] = min(1f, activation[i] + 0.30f)
                age[i] = 0
                fired++
            } else {
                if (v[i] < -85f) v[i] = -85f
                activation[i] *= 0.985f
                age[i]++
            }
        }
        spikes += fired
        return fired
    }

    /** decaimento entre turnos: o contexto sobrevive, mas perde forca */
    fun decay() {
        for (i in 0 until capacity) {
            activation[i] *= 0.92f
            age[i]++
            if (activation[i] < 0.012f && clock - stamp[i] > 8) {
                activation[i] = 0f
                concept[i] = -1
                stamp[i] = -1
                v[i] = -65f
                u[i] = -13f
            }
        }
    }

    fun clear() {
        concept.fill(-1)
        activation.fill(0f)
        age.fill(99)
        stamp.fill(-1)
        v.fill(-65f)
        u.fill(-13f)
        clock = 0
        spikes = 0
    }

    data class Slot(val conceptId: Int, val activation: Float, val age: Int)

    fun top(k: Int = 6): List<Slot> {
        val ranked = ArrayList<Pair<Slot, Float>>(capacity)
        for (i in 0 until capacity) {
            if (concept[i] >= 0 && activation[i] > 0.01f) {
                val ageInTurns = (clock - stamp[i]).coerceAtLeast(0)
                val recency = 1f / (1f + ageInTurns)
                // ativacao domina, mas o conceito atual vence empates
                val rank = activation[i] * (0.70f + 0.30f * recency)
                ranked.add(Slot(concept[i], activation[i], age[i]) to rank)
            }
        }
        ranked.sortByDescending { it.second }
        return ranked.take(k).map { it.first }
    }

    fun occupancy(): Int {
        var n = 0
        for (i in 0 until capacity) if (concept[i] >= 0 && activation[i] > 0.01f) n++
        return n
    }

    /** media de ativacao dos slots que contem um conceito */
    fun activationOf(id: Int): Float {
        for (i in 0 until capacity) if (concept[i] == id) return activation[i]
        return 0f
    }
}
