package dev.zax4r0.droidmode

import android.app.Application
import dev.zax4r0.droidmode.features.animator.AnimatorController
import dev.zax4r0.droidmode.features.caffeine.CaffeineController
import dev.zax4r0.droidmode.features.network.NetworkModeController
import dev.zax4r0.droidmode.features.smartnet.SmartNetworkController
import dev.zax4r0.droidmode.features.widgets.DroidModeWidgetsUpdater
import dev.zax4r0.droidmode.features.wifi.WifiPriorityController
import dev.zax4r0.droidmode.utils.ShizukuHelper
import dev.zax4r0.droidmode.utils.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DroidModeApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init()
        CaffeineController.init(this)
        AnimatorController.init(this)
        WifiPriorityController.init(this)
        SmartNetworkController.init(this)
        UpdateManager.init(this)
        DroidModeWidgetsUpdater.updateAll(this)

        appScope.launch {
            // Wait briefly for Shizuku binder to attach on cold process start, then populate SIM & Wi-Fi state for widgets
            delay(600L)
            ShizukuHelper.updateState()
            NetworkModeController.refresh(this@DroidModeApplication)
            WifiPriorityController.refresh(scan = false)
            DroidModeWidgetsUpdater.updateAll(this@DroidModeApplication)
        }
    }
}
