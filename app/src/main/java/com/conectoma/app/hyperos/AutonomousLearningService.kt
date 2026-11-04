package com.conectoma.app.hyperos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.conectoma.app.MainActivity
import com.conectoma.app.core.Brain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background autonomous learning as a data-sync foreground service so MIUI/HyperOS
 * does not freeze the loop when the app leaves the foreground.
 */
class AutonomousLearningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, notification("iniciando o conectoma…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) {
            loop = scope.launch {
                val brain = Brain.get(applicationContext)
                if (!brain.ready) brain.initialize { stage, _ -> update("carregando: $stage") }
                val prefs = getSharedPreferences("conectoma", MODE_PRIVATE)
                while (isActive) {
                    // economia de bateria dita o ritmo: 90 s carregando no Wi-Fi,
                    // até 20 min em modo de economia de energia do sistema
                    val eco = runCatching {
                        com.conectoma.app.core.BatteryEconomy.read(applicationContext)
                    }.getOrNull()
                    delay(eco?.delayMs ?: 5 * 60_000L)
                    // interruptor desligado na UI = serviço para de vez,
                    // mesmo que alguma chamada anterior o tenha deixado vivo
                    if (!prefs.getBoolean("autonomy", false)) {
                        stopSelf()
                        return@launch
                    }
                    if (!brain.ready) continue
                    val result = runCatching {
                        brain.autonomousStep(allowNetwork = eco?.allowNetwork ?: false)
                    }.getOrNull() ?: continue
                    update(result.description)
                    // atenção: NÃO regravamos a chave "autonomy" aqui — ela é do
                    // usuário. Reescrevê-la reativava o aprendizado autônomo
                    // depois de o interruptor ser desligado
                    prefs.edit()
                        .putString("last_insight", result.description)
                        .apply()
                    if (result.insight > 0.55f) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(BURST_ID, notification("impulso dopaminérgico: ${result.description}"))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun update(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AutonomousLearningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Conectoma · aprendizado autônomo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "parar", stop).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Aprendizado autônomo", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CHANNEL = "conectoma.autonomy"
        private const val NOTIF_ID = 42
        private const val BURST_ID = 43
        const val ACTION_STOP = "com.conectoma.app.STOP_AUTONOMY"

        fun start(context: Context) {
            val intent = Intent(context, AutonomousLearningService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutonomousLearningService::class.java))
        }
    }
}
