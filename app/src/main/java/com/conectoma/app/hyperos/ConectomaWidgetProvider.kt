package com.conectoma.app.hyperos

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.conectoma.app.MainActivity
import com.conectoma.app.R

/** Home-screen widget: substrate size + last autonomous insight. */
class ConectomaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences("conectoma", Context.MODE_PRIVATE)
        val stats = prefs.getString("status_line", "34.813 neurônios · 3,75M sinapses") ?: ""
        val insight = prefs.getString("last_insight", "ainda não aprendeu nada sozinho") ?: ""
        val views = RemoteViews(context.packageName, R.layout.widget_conectoma)
        views.setTextViewText(R.id.widget_stats, stats)
        views.setTextViewText(R.id.widget_insight, insight)
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_title, pi)
        for (id in ids) manager.updateAppWidget(id, views)
    }
}
