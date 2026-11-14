package dev.zax4r0.droidmode.features.freezer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.zax4r0.droidmode.features.widgets.DroidModeWidgetsUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UnfreezeAndLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPkg = intent.getStringExtra("target_package")
        if (targetPkg.isNullOrBlank()) {
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val unfreezeSuccess = AppFreezerController.setFrozen(this@UnfreezeAndLaunchActivity, targetPkg, false)
            if (unfreezeSuccess) {
                var launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                var retries = 0
                while (launchIntent == null && retries < 5) {
                    delay(100)
                    launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                    retries++
                }
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this@UnfreezeAndLaunchActivity, "Cannot launch $targetPkg", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@UnfreezeAndLaunchActivity, "Failed to unfreeze $targetPkg (Check Shizuku)", Toast.LENGTH_SHORT).show()
            }
            DroidModeWidgetsUpdater.updateAll(applicationContext)
            finish()
        }
    }
}
