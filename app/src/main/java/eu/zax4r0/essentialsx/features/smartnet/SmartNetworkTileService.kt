package eu.zax4r0.essentialsx.features.smartnet

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.utils.ShizukuHelper

class SmartNetworkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (SmartNetworkController.tileClickMode.value == SmartNetworkController.TileClickMode.OPEN_DRAWER) {
            openSmartNetworkDrawer()
            return
        }

        if (!ShizukuHelper.hasPorterPermission()) {
            ShizukuHelper.requestPermission()
            openSmartNetworkDrawer()
            return
        }

        SmartNetworkController.toggle(this)
        updateTile()
    }

    private fun openSmartNetworkDrawer() {
        val intent = Intent(this, SmartNetworkDrawerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = SmartNetworkController.isEnabled.value
        tile.label = getString(R.string.tile_smart_network)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_smart_network)

        if (!enabled) {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Off • Hold for tray"
            }
            tile.updateTile()
            return
        }

        tile.state = Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activeFeatures = buildList {
                if (SmartNetworkController.autoWifiDataEnabled.value) add("Wi-Fi")
                if (SmartNetworkController.auto5g4gEnabled.value) add("5G/4G")
                if (SmartNetworkController.autoSimSwitchEnabled.value) add("SIM")
            }
            tile.subtitle = if (activeFeatures.isNotEmpty()) {
                activeFeatures.joinToString(" + ")
            } else {
                "Monitoring"
            }
        }
        tile.updateTile()
    }
}
