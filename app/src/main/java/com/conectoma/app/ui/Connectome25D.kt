package com.conectoma.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.conectoma.app.core.Connectome
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Visualização 2.5D do conectoma — substitui o antigo orbe 3D.
 *
 * O que mudou em relação ao pipeline 3D:
 *  - sem matrizes de rotação, sem projeção em perspectiva, sem alocação por
 *    frame e sem `sortedBy` (o sort por profundidade sumiu: a ordem de desenho
 *    é fixa, traseira → frontal, decidida na construção da geometria);
 *  - sem leitura de `network.activity`: isto é um mapa estático da fiação,
 *    não um monitor de disparos (telemetria em tempo real ficou fora da UI);
 *  - a geometria é construída UMA vez em `remember` e desenhada num único
 *    passe; o Canvas só redesenha quando o tamanho muda, então o custo por
 *    quadro é zero enquanto a tela está parada.
 *
 * Modelo 2.5D: quatro planos 2D sobrepostos (camadas) com escala e deslocamento
 * fixos por camada — o deslocamento progressivo cria o efeito de paralaxe e o
 * achatamento isométrico (y × 0.62) dá a leitura de profundidade.
 *
 * Camadas (de trás para frente), atribuídas pelos metadados reais do neurônio:
 *   L0 sensorial/optico   → mais atrás, menor, deslocado para cima
 *   L1 complexo central   → plano intermediário
 *   L2 corpo cogumelo     → plano próximo
 *   L3 motor/profundo     → mais à frente, maior
 */
@Composable
fun Connectome25D(
    modifier: Modifier = Modifier,
    connectome: Connectome?,
    sampleSize: Int = 560,
) {
    // geometria estática: coordenadas normalizadas (-1..1) por camada
    val geometry = remember(connectome, sampleSize) { buildGeometry(connectome, sampleSize) }

    Canvas(modifier) {
        val g = geometry ?: return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.minDimension * 0.46f
        val iso = 0.62f // achatamento isométrico do plano

        // um único passe, da camada mais traseira para a mais frontal
        for (layer in 0..3) {
            // 1. sinapses desta camada (linhas finas e fixas)
            for (k in g.ea.indices) {
                if (g.edgeLayer[k] != layer) continue
                val a = g.ea[k]
                val b = g.eb[k]
                drawLine(
                    color = LAYER_EDGE[layer].copy(alpha = g.edgeAlpha[k]),
                    start = Offset(cx + g.px[a] * scale, cy + g.py[a] * scale * iso),
                    end = Offset(cx + g.px[b] * scale, cy + g.py[b] * scale * iso),
                    strokeWidth = g.edgeWidth[k],
                    cap = StrokeCap.Round,
                )
            }
            // 2. neurônios desta camada (sprites 2D estáticos)
            for (i in g.ids.indices) {
                if (g.layer[i] != layer) continue
                val x = cx + g.px[i] * scale
                val y = cy + g.py[i] * scale * iso
                val r = g.radius[i]
                if (r >= 2.4f) {
                    // halo discreto nos neurônios mais conectados
                    drawCircle(
                        color = LAYER_DOT[layer].copy(alpha = 0.10f),
                        radius = r * 2.6f,
                        center = Offset(x, y),
                    )
                }
                drawCircle(
                    color = LAYER_DOT[layer].copy(alpha = g.alpha[i]),
                    radius = r,
                    center = Offset(x, y),
                )
            }
        }
    }
}

/**
 * Constrói a geometria 2.5D uma única vez (fora do frame).
 *
 * Amostragem uniforme do conectoma real, atribuição de camada pelos metadados
 * do neurônio, posicionamento por camada e coleta das sinapses cujas duas
 * pontas estão na amostra. Tudo em arrays primitivos — sem objetos por vértice.
 */
private fun buildGeometry(connectome: Connectome?, sampleSize: Int): Geometry? {
    if (connectome == null) return null
    val n = connectome.nodeCount
    if (n <= 0) return null
    val rnd = Random(2024)
    val step = (n / sampleSize).coerceAtLeast(1)
    val count = if (step <= 1) n else sampleSize
    val ids = IntArray(count) { (it * step).coerceAtMost(n - 1) }
    val indexOf = HashMap<Int, Int>(count * 2)

    val px = FloatArray(count)
    val py = FloatArray(count)
    val radius = FloatArray(count)
    val alpha = FloatArray(count)
    val layer = IntArray(count)
    val neurons = connectome.neurons

    for (i in 0 until count) {
        val id = ids[i]
        indexOf[id] = i
        layer[i] = layerOf(neurons.getOrNull(id))
        // distribuição em elipse por camada: pontos fixos, sem aleatoriedade por frame
        val t = (i + 1) / (count + 1f)
        val ring = sqrt((i % 7 + 1) / 7f)
        val ang = t * Math.PI * 2.0 * 3.0 + i * 0.618
        val jitterX = (rnd.nextFloat() - 0.5f) * 0.10f
        val jitterY = (rnd.nextFloat() - 0.5f) * 0.10f
        // offset fixo por camada = paralaxe (cada plano deslocado no 2.5D)
        px[i] = (Math.cos(ang) * ring).toFloat() * 0.86f + jitterX + LAYER_DX[layer[i]]
        py[i] = (Math.sin(ang) * ring).toFloat() * 0.86f + jitterY + LAYER_DY[layer[i]]
        val degree = connectome.outDegree(id).coerceAtMost(240)
        radius[i] = (1.05f + degree / 78f) * LAYER_SCALE[layer[i]]
        // escala por camada: traseira menor (parece mais distante)
        radius[i] = radius[i].coerceIn(0.9f, 4.6f)
        alpha[i] = LAYER_ALPHA[layer[i]]
    }

    // sinapses cujas duas pontas caem na amostra (no máximo 3 por neurônio)
    val ea = ArrayList<Int>(2400)
    val eb = ArrayList<Int>(2400)
    val el = ArrayList<Int>(2400)
    val eaW = ArrayList<Float>(2400)
    val eaA = ArrayList<Float>(2400)
    for (i in 0 until count) {
        val node = ids[i]
        var drawn = 0
        var e = connectome.outStart(node)
        val end = connectome.outEnd(node)
        while (e < end && drawn < 3) {
            val j = indexOf[connectome.target(e)]
            if (j != null && j != i) {
                // linha entre camadas diferentes usa a cor da camada de trás
                val l = minOf(layer[i], layer[j])
                ea.add(i)
                eb.add(j)
                el.add(l)
                eaW.add(if (layer[i] == layer[j]) 0.9f else 0.6f)
                eaA.add(if (layer[i] == layer[j]) 0.20f else 0.11f)
                drawn++
            }
            e++
        }
    }
    return Geometry(
        ids = ids,
        px = px,
        py = py,
        radius = radius,
        alpha = alpha,
        layer = layer,
        ea = ea.toIntArray(),
        eb = eb.toIntArray(),
        edgeLayer = el.toIntArray(),
        edgeWidth = eaW.toFloatArray(),
        edgeAlpha = eaA.toFloatArray(),
    )
}

/**
 * Classe funcional do neurônio → camada 2.5D.
 * Espelha a leitura do `ConnectomeMapping` (sensorial, CX, MB, motor) mas é
 * deliberadamente barata: só `superClass`/`cellType` em caixa baixa, feito uma
 * vez na construção da geometria.
 */
private fun layerOf(n: com.conectoma.app.core.Neuron?): Int {
    if (n == null) return 3
    val sc = n.superClass.lowercase()
    val ct = n.cellType.lowercase()
    val isSensory = sc == "sensory" || sc == "optic" || sc.contains("visual") ||
        ct.contains("olfactory") || ct.contains("mechanosensory") ||
        ct.contains("gustatory") || ct.contains("thermosensory")
    if (isSensory) return 0
    val isCx = ct.startsWith("cb.") || ct.contains("fan-shaped") ||
        ct.contains("ellipsoid") || ct.contains("protocerebral bridge")
    if (isCx) return 1
    val isMb = ct.contains("mbon") || ct.contains("mbin") || ct.contains("kenyon") ||
        ct == "dan" || ct == "pam"
    if (isMb) return 2
    return 3
}

/** arrays estáticos calculados uma vez; nenhum campo é mutado depois */
private class Geometry(
    val ids: IntArray,
    val px: FloatArray,
    val py: FloatArray,
    val radius: FloatArray,
    val alpha: FloatArray,
    val layer: IntArray,
    val ea: IntArray,
    val eb: IntArray,
    val edgeLayer: IntArray,
    val edgeWidth: FloatArray,
    val edgeAlpha: FloatArray,
)

// ------------------------------------------------------------- paleta por camada

private val LAYER_DOT = listOf(
    Color(0xFF3FC8C0), // L0 sensorial (aqua)
    Color(0xFF5FE3D6), // L1 complexo central (cyan)
    Color(0xFFF5C24B), // L2 corpo cogumelo (amber)
    Color(0xFF8B7BFF), // L3 motor/profundo (violet)
)
private val LAYER_EDGE = listOf(
    Color(0xFF3FC8C0),
    Color(0xFF5FE3D6),
    Color(0xFFF5C24B),
    Color(0xFF8B7BFF),
)

// offsets de paralaxe, escala e alpha fixos por camada (traseira → frontal)
private val LAYER_DX = floatArrayOf(-0.05f, -0.02f, 0.02f, 0.05f)
private val LAYER_DY = floatArrayOf(-0.17f, -0.06f, 0.06f, 0.17f)
private val LAYER_SCALE = floatArrayOf(0.74f, 0.86f, 0.95f, 1.04f)
private val LAYER_ALPHA = floatArrayOf(0.42f, 0.58f, 0.72f, 0.86f)
