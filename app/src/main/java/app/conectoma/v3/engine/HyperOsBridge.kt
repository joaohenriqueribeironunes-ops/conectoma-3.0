package app.conectoma.v3.engine

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * HyperOS 1 is Android 13–15 (Xiaomi). Official client guidance: minSdk 23, target 34.
 * This bridge detects the OS, exposes Xiaomi props, and keeps reasoning compatible
 * without bundling proprietary SDKs (no install on the PC, no Mi App-Id).
 */
object HyperOsBridge {

    data class Snapshot(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val hyperOsName: String?,
        val hyperOsVersion: String?,
        val miuiVersion: String?,
        val isXiaomiFamily: Boolean,
        val isHyperOs: Boolean,
        val compatible: Boolean
    )

    fun snapshot(): Snapshot {
        val name = prop("ro.mi.os.version.name")
        val ver = prop("ro.mi.os.version.incremental") ?: prop("ro.mi.os.version.code")
        val miui = prop("ro.miui.ui.version.name")
        val family = listOf("xiaomi", "redmi", "poco", "blackshark")
            .any { Build.MANUFACTURER.contains(it, true) || Build.BRAND.contains(it, true) }
        val hyper = !name.isNullOrBlank() || (family && Build.VERSION.SDK_INT >= 33)
        return Snapshot(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            hyperOsName = name,
            hyperOsVersion = ver,
            miuiVersion = miui,
            isXiaomiFamily = family,
            isHyperOs = hyper,
            compatible = Build.VERSION.SDK_INT in 23..35
        )
    }

    fun reasoningHint(snap: Snapshot): String = buildString {
        append("Raciocínio artificial alinhado ao HyperOS 1: ")
        if (snap.isHyperOs) {
            append("ambiente nativo detectado")
            snap.hyperOsName?.let { append(" ($it)") }
            append(". ")
        } else {
            append("emulação de compatibilidade Android/HyperOS (min 23 / target 34). ")
        }
        append("O conectoma usa o mesmo processo do app, WorkManager e serviço de dados — padrão aceito no HyperOS 1 sem SDK Xiaomi proprietário.")
    }

    private fun prop(key: String): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        BufferedReader(InputStreamReader(p.inputStream)).use { it.readLine()?.trim()?.ifBlank { null } }
    }.getOrNull()
}
