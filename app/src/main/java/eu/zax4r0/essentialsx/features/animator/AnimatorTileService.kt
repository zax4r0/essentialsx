package eu.zax4r0.essentialsx.features.animator

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.zax4r0.essentialsx.R

class AnimatorTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        AnimatorController.refreshScales(this)
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        AnimatorController.toggleQuickTile(this)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val currentScale = AnimatorController.windowScale.value
        val isFast = currentScale <= 0.5f

        tile.state = if (isFast) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_custom_animator)
        val subtitle = "${currentScale}x Speed"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_speed)
        tile.updateTile()
    }
}
