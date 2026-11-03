package com.conectoma.app.core

import kotlin.math.max
import kotlin.math.min

/**
 * Neuromodulacao global do motor sinaptico.
 *
 * Nao e um "botao magico": cada variavel altera ganhos que ja existem na rede.
 *  - Acetilcolina (ACh): excitabilidade e foco atencional.
 *  - GABA: inibicao e filtro de ruido.
 *  - Dopamina (DA): aprendizado (ja aplicada no STDP de tres fatores).
 *  - Serotonina (5-HT): saciedade/fadiga; evita perseveracao.
 *  - Noradrenalina (NA): novidade e alerta.
 *
 * A guarda anti-epilepsia observa a taxa de disparo. Se a populacao inteira
 * comecar a disparar em bloco, GABA sobe, ACh cai e o ruido diminui.
 */
class Neuromodulator {
    @Volatile var acetylcholine = 0.55f
        private set
    @Volatile var gaba = 0.28f
        private set
    @Volatile var dopamine = 0.18f
        private set
    @Volatile var serotonin = 0.30f
        private set
    @Volatile var norepinephrine = 0.38f
        private set

    @Volatile var lastRate = 0f
        private set
    @Volatile var guardTrips = 0
        private set

    private var highRateSteps = 0
    private var silentSteps = 0

    /** multiplicador aplicado aos contatos excitatórios */
    val excitability: Float
        get() = (0.55f + 1.10f * acetylcholine - 0.70f * gaba + 0.12f * norepinephrine)
            .coerceIn(0.30f, 1.85f)

    /** multiplicador aplicado aos contatos inibitórios */
    val inhibition: Float
        get() = (0.45f + 1.55f * gaba + 0.20f * serotonin)
            .coerceIn(0.35f, 2.20f)

    /** fracao da populacao que recebe ruido por passo */
    val noiseFraction: Float
        get() = (0.010f + 0.028f * (1f - acetylcholine) + 0.012f * gaba)
            .coerceIn(0.004f, 0.055f)

    /** amplitude do ruido de membrana */
    val noiseCurrent: Float
        get() = (1.2f + 3.2f * (1f - acetylcholine) + 1.0f * gaba)
            .coerceIn(0.6f, 5.0f)

    /** foco efetivo usado pelo decodificador de atencao */
    val focus: Float
        get() = (0.40f + 0.95f * acetylcholine + 0.18f * norepinephrine - 0.45f * gaba)
            .coerceIn(0.20f, 1.50f)

    fun onTurnStart(novelty: Float, load: Float, reward: Float) {
        // novidade abre a atencao; carga excessiva cansa e aumenta o filtro
        norepinephrine = (0.30f + 0.55f * novelty.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        acetylcholine = (0.50f + 0.28f * novelty + 0.16f * reward - 0.12f * load)
            .coerceIn(0.12f, 0.95f)
        gaba = (0.28f + 0.16f * load - 0.08f * novelty).coerceIn(0.10f, 0.75f)
        serotonin = (0.24f + 0.18f * load).coerceIn(0f, 0.85f)
        dopamine = (0.12f + 0.65f * reward.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    /** chamado a cada passo da rede, com a taxa de disparo observada */
    fun observe(spikes: Int, neurons: Int, meanActivity: Float, da: Float) {
        if (neurons <= 0) return
        val rate = spikes.toFloat() / neurons
        lastRate = rate
        dopamine = da.coerceIn(0f, 1f)

        // saciedade lenta: atividade alta e prolongada aumenta 5-HT
        serotonin = (serotonin + 0.0015f * (rate * 4f - serotonin)).coerceIn(0f, 0.95f)

        if (rate > 0.24f) {
            highRateSteps++
            gaba = (gaba + 0.035f).coerceAtMost(0.95f)
            acetylcholine = (acetylcholine - 0.025f).coerceAtLeast(0.05f)
            if (highRateSteps >= 3) {
                guardTrips++
                highRateSteps = 0
                // descarrega a populacao: evita o loop epileptico
                gaba = (gaba + 0.10f).coerceAtMost(0.98f)
            }
        } else {
            highRateSteps = 0
        }

        if (rate < 0.002f) {
            silentSteps++
            if (silentSteps >= 40) {
                silentSteps = 0
                acetylcholine = (acetylcholine + 0.03f).coerceAtMost(0.95f)
                gaba = (gaba - 0.02f).coerceAtLeast(0.10f)
            }
        } else {
            silentSteps = 0
        }

        // homeostase de foco: puxa lentamente para o centro
        acetylcholine = (acetylcholine + 0.002f * (0.52f - acetylcholine)).coerceIn(0.05f, 0.98f)
        gaba = (gaba + 0.002f * (0.30f - gaba)).coerceIn(0.06f, 0.98f)
        serotonin = (serotonin + 0.001f * (0.30f - serotonin)).coerceIn(0f, 0.95f)
        norepinephrine = (norepinephrine + 0.002f * (0.38f - norepinephrine)).coerceIn(0f, 1f)
    }

    fun snapshot(): String = String.format(
        java.util.Locale.US,
        "ACh %.2f · GABA %.2f · DA %.2f · 5-HT %.2f · NA %.2f",
        acetylcholine, gaba, dopamine, serotonin, norepinephrine,
    )

    fun clampAll() {
        acetylcholine = min(0.98f, max(0.03f, acetylcholine))
        gaba = min(0.98f, max(0.05f, gaba))
        dopamine = min(1f, max(0f, dopamine))
        serotonin = min(0.95f, max(0f, serotonin))
        norepinephrine = min(1f, max(0f, norepinephrine))
    }
}
