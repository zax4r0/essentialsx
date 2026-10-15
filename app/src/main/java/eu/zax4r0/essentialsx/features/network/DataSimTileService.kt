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

class DataSimTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val subscriptionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch {
                NetworkModeController.refresh(this@DataSimTileService)
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
            NetworkModeController.refresh(this@DataSimTileService)
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
            NetworkModeController.toggleDefaultDataSim(this@DataSimTileService)
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val hasPerm = ShizukuHelper.hasPorterPermission()

        if (!hasPerm) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Data SIM"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.network_permission_required)
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_signal_cellular)
            tile.updateTile()
            return
        }

        val targetSim = NetworkModeController.getDefaultTargetSim()
        tile.label = "Data SIM"

        if (targetSim == null) {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "No active SIM"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_signal_cellular)
            tile.updateTile()
            return
        }

        tile.state = Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activeCount = NetworkModeController.sims.value.count { it.isActive }
            tile.subtitle = if (activeCount > 1) {
                "${targetSim.displayName} (Tap to switch)"
            } else {
                targetSim.displayName
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_signal_cellular)
        tile.updateTile()
    }
}
