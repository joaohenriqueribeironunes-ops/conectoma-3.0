package com.conectoma.app.hyperos

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.conectoma.app.MainActivity

/**
 * Quick Settings tile. On HyperOS the tile sits in the same control centre surface
 * as the system toggles; tapping it opens the connectome and flips autonomous mode.
 */
class HyperOSTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("conectoma", MODE_PRIVATE)
        val on = prefs.getBoolean("autonomy", false)
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Conectoma"
            subtitle = if (on) "aprendendo sozinho" else "em repouso"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("conectoma", MODE_PRIVATE)
        val on = !prefs.getBoolean("autonomy", false)
        prefs.edit().putBoolean("autonomy", on).apply()
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (on) "aprendendo sozinho" else "em repouso"
            updateTile()
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("autonomy", on)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
