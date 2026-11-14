package dev.zax4r0.droidmode.sidecar

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

class SidecarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_STATE) return
        val targetPkg = intent.getStringExtra(EXTRA_PACKAGE)
        val alias = intent.getStringExtra(EXTRA_ALIAS)
            ?: targetPkg?.let { "dev.zax4r0.droidmode.sidecar.Alias_${it.replace('.', '_')}" }
            ?: return

        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val comp = ComponentName(context, alias)
        try {
            context.packageManager.setComponentEnabledSetting(
                comp,
                newState,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "SidecarReceiver set $alias -> $newState")
        } catch (e: Exception) {
            Log.e(TAG, "SidecarReceiver failed for $alias", e)
        }
    }

    companion object {
        private const val TAG = "SidecarReceiver"
        const val ACTION_SET_STATE = "dev.zax4r0.droidmode.sidecar.SET_STATE"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ALIAS = "alias"
        const val EXTRA_ENABLED = "enabled"
    }
}
