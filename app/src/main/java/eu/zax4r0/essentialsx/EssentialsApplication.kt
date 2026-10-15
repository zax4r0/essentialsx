package eu.zax4r0.essentialsx

import android.app.Application
import eu.zax4r0.essentialsx.features.animator.AnimatorController
import eu.zax4r0.essentialsx.features.caffeine.CaffeineController
import eu.zax4r0.essentialsx.utils.ShizukuHelper

class EssentialsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ShizukuHelper.init()
        CaffeineController.init(this)
        AnimatorController.init(this)
    }
}
