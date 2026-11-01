package app.conectoma.v3.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.conectoma.v3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AutonomousLearner(
    private val motor: SynapticMotor,
    private val dictionary: DictionaryEngine
) {
    suspend fun tick(): LearningTrace? = withContext(Dispatchers.IO) {
        val pair = dictionary.distantPair() ?: return@withContext null
        val (a, b) = pair
        val defA = dictionary.fetchDefinition(a.word)
        val defB = dictionary.fetchDefinition(b.word)
        if (defA != null) dictionary.remember(a.word, defA, "autônomo")
        if (defB != null) dictionary.remember(b.word, defB, "autônomo")
        val image = dictionary.fetchImage(a.word) ?: dictionary.fetchImage(b.word)
        val bridge = dictionary.nearest(a.word + " " + b.word, 5)
            .map { it.first.word }
            .firstOrNull { it != a.word && it != b.word }
        if (bridge != null) {
            motor.learnPair(a.word, bridge, "ponte autônoma")
        }
        val trace = motor.learnPair(
            a.word,
            b.word,
            "autônomo: conceitos distantes “${a.word}” ↔ “${b.word}”"
        )
        trace.copy(imageUrl = image)
    }
}

class AutonomousWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ConectomaRuntime ?: return Result.retry()
        app.autonomous.tick()
        app.graph.decay()
        return Result.success()
    }
}

object AutonomousScheduler {
    private const val UNIQUE = "conectoma-autonomous"

    fun start(context: Context) {
        val req = PeriodicWorkRequestBuilder<AutonomousWorker>(
            ConnectomeConstants.AUTONOMOUS_INTERVAL_MIN,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}

class AutonomousLearnService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("conectoma", "Conectoma", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, "conectoma")
            .setSmallIcon(R.drawable.ic_stat_conectoma)
            .setContentTitle("Conectoma 3.0")
            .setContentText("Aprendizado autônomo disposto a continuar.")
            .setOngoing(true)
            .build()
        startForeground(3, n)
        AutonomousScheduler.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutonomousScheduler.start(context)
    }
}

interface ConectomaRuntime {
    val graph: ConnectomeGraph
    val autonomous: AutonomousLearner
}
