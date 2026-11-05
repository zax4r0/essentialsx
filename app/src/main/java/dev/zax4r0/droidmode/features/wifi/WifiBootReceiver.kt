package dev.zax4r0.droidmode.features.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.zax4r0.droidmode.features.smartnet.SmartNetworkController
import dev.zax4r0.droidmode.features.smartnet.SmartNetworkService

class WifiBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (WifiPriorityController.isEnabled.value) {
            ContextCompat.startForegroundService(context, Intent(context, WifiPriorityService::class.java))
        }
        if (SmartNetworkController.isEnabled.value) {
            ContextCompat.startForegroundService(context, Intent(context, SmartNetworkService::class.java))
        }
    }
}
