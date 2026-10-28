package eu.zax4r0.essentialsx

import android.app.Application
import eu.zax4r0.essentialsx.features.animator.AnimatorController
import eu.zax4r0.essentialsx.features.caffeine.CaffeineController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import eu.zax4r0.essentialsx.utils.UpdateManager

class EssentialsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init()
        CaffeineController.init(this)
        AnimatorController.init(this)
        WifiPriorityController.init(this)
        SmartNetworkController.init(this)
        UpdateManager.init(this)
    }
}
