package eu.zax4r0.essentialsx

import android.app.Application
import eu.zax4r0.essentialsx.features.animator.AnimatorController
import eu.zax4r0.essentialsx.features.caffeine.CaffeineController
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.widgets.EssentialsWidgetsUpdater
import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import eu.zax4r0.essentialsx.utils.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EssentialsApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init()
        CaffeineController.init(this)
        AnimatorController.init(this)
        WifiPriorityController.init(this)
        SmartNetworkController.init(this)
        UpdateManager.init(this)
        EssentialsWidgetsUpdater.updateAll(this)

        appScope.launch {
            // Wait briefly for Shizuku binder to attach on cold process start, then populate SIM & Wi-Fi state for widgets
            delay(600L)
            ShizukuHelper.updateState()
            NetworkModeController.refresh(this@EssentialsApplication)
            WifiPriorityController.refresh(scan = false)
            EssentialsWidgetsUpdater.updateAll(this@EssentialsApplication)
        }
    }
}
