package com.conectoma.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Economia de bateria para o aprendizado autônomo.
 *
 * A cada ciclo o app pergunta [read]: pode usar a rede e quanto tempo esperar
 * até o próximo passo? A política prioriza a bateria do dispositivo:
 *
 *  - modo de economia de energia ligado  → nada de rede, intervalo de 20 min;
 *  - bateria <= 20% e descarregando     → só aprendizado local, 12 min;
 *  - bateria <= 50% e descarregando     → rede liberada, mas a cada 6 min;
 *  - carregando (Wi-Fi)                 → ciclo rápido de 90 s;
 *  - carregando (dados móveis)          → 3 min;
 *  - caso contrário                     → 5 min.
 *
 * Nada aqui bloqueia o usuário: o interruptor de aprendizado autônomo na UI
 * continua sendo a decisão final — esta classe só ajusta o custo em bateria.
 */
object BatteryEconomy {

    data class Report(
        /** nível de bateria 0..100 */
        val level: Int,
        val charging: Boolean,
        /** modo economia de energia do sistema ativo */
        val powerSave: Boolean,
        /** rede não medida (Wi-Fi/ethernet) */
        val unmetered: Boolean,
        /** rede disponível e política de bateria permite buscar na internet */
        val allowNetwork: Boolean,
        /** espera recomendada até o próximo ciclo autônomo */
        val delayMs: Long,
    )

    fun read(context: Context): Report {
        val bm = runCatching {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull()
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.coerceIn(0, 100) ?: 100
        val charging = runCatching { bm?.isCharging ?: false }.getOrDefault(false)

        val powerSave = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        }.getOrDefault(false)

        val caps = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.getNetworkCapabilities(cm.activeNetwork)
        }.getOrNull()
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val allowNetwork = when {
            powerSave -> false
            !online -> false
            !charging && level <= 20 -> false
            else -> true
        }
        val delayMs = when {
            powerSave -> 20 * 60_000L
            !charging && level <= 20 -> 12 * 60_000L
            !charging && level <= 50 -> 6 * 60_000L
            charging && unmetered -> 90_000L
            charging -> 3 * 60_000L
            else -> 5 * 60_000L
        }
        return Report(level, charging, powerSave, unmetered, allowNetwork, delayMs)
    }
}
