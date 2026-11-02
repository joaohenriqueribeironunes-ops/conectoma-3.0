package com.conectoma.app

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConectomaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    /**
     * Writes any uncaught exception next to the app's external files, so a crash can
     * be reported without a cable. Keep it tiny and dependency-free: it runs while
     * the process is dying.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { persistCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun persistCrash(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            append("Conectoma ").append(BuildConfig.VERSION_NAME)
            append(" crash em ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            append("aparelho: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append("\nthread: ").append(thread.name)
            append("\n\n").append(trace)
        }
        val targets = listOfNotNull(
            runCatching { getExternalFilesDir(null) }.getOrNull(),
            runCatching { filesDir }.getOrNull(),
        )
        for (dir in targets) {
            runCatching {
                dir.mkdirs()
                File(dir, "crash-log.txt").writeText(report)
            }
        }
    }
}
