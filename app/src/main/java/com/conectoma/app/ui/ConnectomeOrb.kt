package com.conectoma.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.conectoma.app.core.Connectome
import com.conectoma.app.core.SpikingNetwork
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The living connectome: 640 sampled neurons of the FlyWire sub-connectome in 3D,
 * with real synaptic edges and spike activity driven by the spiking engine.
 */
@Composable
fun ConnectomeOrb(
    modifier: Modifier = Modifier,
    connectome: Connectome?,
    network: SpikingNetwork?,
    sampleSize: Int = 620,
) {
    var angle by remember { mutableFloatStateOf(0f) }

    val geometry = remember(connectome, sampleSize) {
        if (connectome == null) return@remember null
        val rnd = Random(2024)
        val n = connectome.nodeCount
        val step = (n / sampleSize).coerceAtLeast(1)
        val ids = IntArray(sampleSize) { (it * step).coerceAtMost(n - 1) }
        val indexOf = HashMap<Int, Int>(sampleSize * 2)
        val px = FloatArray(sampleSize)
        val py = FloatArray(sampleSize)
        val pz = FloatArray(sampleSize)
        val radius = FloatArray(sampleSize)
        for (i in 0 until sampleSize) {
            val golden = Math.PI * (3.0 - Math.sqrt(5.0))
            val y = 1.0 - (i.toDouble() / (sampleSize - 1)) * 2.0
            val r = Math.sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val theta = golden * i
            px[i] = (cos(theta) * r).toFloat() * 0.92f + (rnd.nextFloat() - 0.5f) * 0.16f
            py[i] = y.toFloat() * 0.92f + (rnd.nextFloat() - 0.5f) * 0.16f
            pz[i] = (sin(theta) * r).toFloat() * 0.92f + (rnd.nextFloat() - 0.5f) * 0.16f
            radius[i] = 1.1f + connectome.outDegree(ids[i]).coerceAtMost(220) / 70f
            indexOf[ids[i]] = i
        }
        // edges whose both endpoints are in the sample
        val ea = ArrayList<Int>(2200)
        val eb = ArrayList<Int>(2200)
        for (i in 0 until sampleSize) {
            val node = ids[i]
            val start = connectome.outStart(node)
            val end = connectome.outEnd(node)
            var drawn = 0
            var e = start
            while (e < end && drawn < 3) {
                val t = connectome.target(e)
                val j = indexOf[t]
                if (j != null && j != i) {
                    ea.add(i)
                    eb.add(j)
                    drawn++
                }
                e++
            }
        }
        Geometry(ids, px, py, pz, radius, ea.toIntArray(), eb.toIntArray())
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000.0).toFloat()
                    angle += dt * 0.16f
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        val g = geometry ?: return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.minDimension * 0.39f
        val cosA = cos(angle)
        val sinA = sin(angle)
        val tilt = 0.32f
        val cosT = cos(tilt)
        val sinT = sin(tilt)

        val sx = FloatArray(g.size)
        val sy = FloatArray(g.size)
        val sz = FloatArray(g.size)
        val order = IntArray(g.size)

        for (i in 0 until g.size) {
            var x = g.px[i]
            var y = g.py[i]
            var z = g.pz[i]
            val x1 = x * cosA - z * sinA
            val z1 = x * sinA + z * cosA
            val y1 = y * cosT - z1 * sinT
            val z2 = y * sinT + z1 * cosT
            x = x1
            y = y1
            z = z2
            val depth = 1f + 0.55f * z
            val persp = 2.1f / (2.1f + z * 0.9f)
            sx[i] = cx + x * scale * persp
            sy[i] = cy + y * scale * persp
            sz[i] = depth
            order[i] = i
        }

        // edges first, faint
        for (k in g.ea.indices) {
            val a = g.ea[k]
            val b = g.eb[k]
            val d = (sz[a] + sz[b]) * 0.5f
            val alpha = (0.05f + 0.13f * d) * 0.9f
            val hot = network?.let {
                (it.activity[g.ids[a]] + it.activity[g.ids[b]]) * 0.5f
            } ?: 0f
            drawLine(
                color = if (hot > 0.25f) Ink.cyan.copy(alpha = 0.5f * hot + 0.1f) else Ink.violet.copy(alpha = alpha),
                start = Offset(sx[a], sy[a]),
                end = Offset(sx[b], sy[b]),
                strokeWidth = if (hot > 0.3f) 1.7f else 0.7f,
                cap = StrokeCap.Round,
            )
        }

        val sorted = (0 until g.size).sortedBy { sz[it] }
        for (i in sorted) {
            val act = network?.activity[g.ids[i]] ?: 0f
            val base = 0.55f + g.radius[i] * 0.12f
            val r = base * (1f + act * 2.2f)
            val z = sz[i]
            val color = when {
                act > 0.35f -> Ink.cyan
                act > 0.12f -> Ink.amber
                else -> Ink.paper.copy(alpha = 0.35f + 0.4f * z)
            }
            if (act > 0.15f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = 0.32f * act), Color.Transparent),
                        center = Offset(sx[i], sy[i]),
                        radius = r * 5.5f,
                    ),
                    radius = r * 5.5f,
                    center = Offset(sx[i], sy[i]),
                )
            }
            drawCircle(color = color.copy(alpha = (0.35f + 0.6f * z).coerceIn(0f, 1f)), radius = r, center = Offset(sx[i], sy[i]))
        }
    }
}

private class Geometry(
    val ids: IntArray,
    val px: FloatArray,
    val py: FloatArray,
    val pz: FloatArray,
    val radius: FloatArray,
    val ea: IntArray,
    val eb: IntArray,
) {
    val size get() = ids.size
}
