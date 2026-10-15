package eu.zax4r0.essentialsx.features.caffeine

import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.zax4r0.essentialsx.R

class CaffeineTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateTile()
            if (CaffeineController.isActive.value) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
        if (CaffeineController.isActive.value) {
            handler.removeCallbacks(refreshRunnable)
            handler.post(refreshRunnable)
        }
    }

    override fun onStopListening() {
        handler.removeCallbacks(refreshRunnable)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        CaffeineController.cycleTimeout(this)
        updateTile()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = CaffeineController.isActive.value
        val remaining = CaffeineController.remainingSecondsFlow.value

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_caffeine)
        val subtitle = if (active) {
            if (remaining < 0) "Infinite (∞)" else CaffeineController.formatDuration(remaining)
        } else {
            "Off"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_coffee)
        tile.updateTile()
    }
}
