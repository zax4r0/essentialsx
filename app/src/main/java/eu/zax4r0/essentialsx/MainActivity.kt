package eu.zax4r0.essentialsx

import android.os.Bundle
import eu.zax4r0.essentialsx.ui.ComposeActivity
import eu.zax4r0.essentialsx.ui.screens.HomeScreen
import eu.zax4r0.essentialsx.utils.ShizukuHelper

class MainActivity : ComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        essentialsContent {
            HomeScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.updateState()
    }
}
