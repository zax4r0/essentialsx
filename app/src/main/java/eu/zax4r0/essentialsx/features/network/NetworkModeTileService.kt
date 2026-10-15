package eu.zax4r0.essentialsx.features.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NetworkModeTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val subscriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch {
                NetworkModeController.refresh(this@NetworkModeTileService)
                updateTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val filter = IntentFilter().apply {
            addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
            addAction("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED")
            addAction("android.telephony.action.CARRIER_CONFIG_CHANGED")
        }
        runCatching { registerReceiver(subscriptionReceiver, filter) }
        scope.launch {
            NetworkModeController.refresh(this@NetworkModeTileService)
            updateTile()
        }
    }

    override fun onStopListening() {
        runCatching { unregisterReceiver(subscriptionReceiver) }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!ShizukuHelper.hasPorterPermission()) {
            ShizukuHelper.requestPermission()
            updateTile()
            return
        }
        scope.launch {
            NetworkModeController.toggleDefaultDataSimMode(this@NetworkModeTileService)
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val hasPerm = ShizukuHelper.hasPorterPermission()

        if (!hasPerm) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_network_switch)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.network_permission_required)
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_signal_cellular)
            tile.updateTile()
            return
        }

        val targetSim = NetworkModeController.getDefaultTargetSim()
        tile.label = getString(R.string.tile_network_switch)

        if (targetSim == null) {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "No active SIM"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_signal_cellular)
            tile.updateTile()
            return
        }

        val mode = targetSim.currentMode
        val (state, modeLabel) = when (mode) {
            NetworkModeController.NetworkMode.MODE_NR_ONLY -> Pair(Tile.STATE_ACTIVE, "5G Only")
            NetworkModeController.NetworkMode.MODE_LTE_ONLY -> Pair(Tile.STATE_ACTIVE, "4G Only")
            NetworkModeController.NetworkMode.MODE_5G_PREF -> Pair(Tile.STATE_INACTIVE, "5G Auto")
            NetworkModeController.NetworkMode.MODE_3G_ONLY -> Pair(Tile.STATE_ACTIVE, "3G Only")
            NetworkModeController.NetworkMode.MODE_2G_ONLY -> Pair(Tile.STATE_ACTIVE, "2G Only")
        }

        tile.state = state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "${targetSim.displayName}: $modeLabel"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_signal_cellular)
        tile.updateTile()
    }
}
