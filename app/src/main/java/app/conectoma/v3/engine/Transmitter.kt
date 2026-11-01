package app.conectoma.v3.engine

/**
 * FlyWire predicted transmitters (Lin et al. / Eckstein et al. annotations):
 * acetylcholine (excitatory), GABA, glutamate (mostly inhibitory in fly brain),
 * dopamine, octopamine, serotonin.
 */
enum class Transmitter(val excitatory: Boolean, val pulseHue: Float) {
    ACH(true, 0.55f),
    GABA(false, 0.72f),
    GLUT(false, 0.18f),
    DA(true, 0.92f),
    OCT(true, 0.08f),
    SER(true, 0.82f);

    companion object {
        fun ofIndex(i: Int): Transmitter = entries[i.mod(entries.size)]
    }
}

enum class NeuronClass {
    SENSORY,
    KENYON,
    MUSHROOM_OUTPUT,
    DOPAMINERGIC,
    INTERNEURON,
    DESCENDING;

    companion object {
        fun ofNeuron(id: Int): NeuronClass = when {
            id < 2_400 -> SENSORY
            id < 8_200 -> KENYON
            id < 9_400 -> MUSHROOM_OUTPUT
            id < 10_200 -> DOPAMINERGIC
            id < 32_000 -> INTERNEURON
            else -> DESCENDING
        }
    }
}
