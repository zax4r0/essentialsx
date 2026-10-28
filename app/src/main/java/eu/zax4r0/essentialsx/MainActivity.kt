package eu.zax4r0.essentialsx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import eu.zax4r0.essentialsx.ui.ComposeActivity
import eu.zax4r0.essentialsx.ui.screens.MainScreen
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import eu.zax4r0.essentialsx.utils.UpdateManager

class MainActivity : ComposeActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        essentialsContent {
            MainScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.updateState()
        UpdateManager.onActivityResumed(this)
    }

    override fun onPause() {
        UpdateManager.onActivityPaused(this)
        super.onPause()
    }
}
