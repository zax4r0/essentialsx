package dev.zax4r0.droidmode.sidecar

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val aliasComp = intent.component
        val targetPkg = intent.getStringExtra("target_package")
            ?: getTargetPackageFromMeta(aliasComp)
            ?: componentNameToPackage(aliasComp?.className)

        if (!targetPkg.isNullOrBlank()) {
            val forward = Intent().apply {
                component = ComponentName(
                    "dev.zax4r0.droidmode",
                    "dev.zax4r0.droidmode.features.freezer.UnfreezeAndLaunchActivity"
                )
                putExtra("target_package", targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(forward)
            } catch (e: Exception) {
                Log.e("TrampolineActivity", "Failed to forward to UnfreezeAndLaunchActivity", e)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun getTargetPackageFromMeta(aliasComp: ComponentName?): String? {
        val comp = aliasComp ?: return null
        return runCatching {
            val info = packageManager.getActivityInfo(comp, PackageManager.GET_META_DATA)
            info.metaData?.getString("target_package")
        }.getOrNull()
    }

    private fun componentNameToPackage(className: String?): String? {
        if (className == null) return null
        val prefix = "dev.zax4r0.droidmode.sidecar.Alias_"
        if (className.startsWith(prefix)) {
            return className.substring(prefix.length).replace('_', '.')
        }
        val simple = className.substringAfterLast('.')
        if (simple.startsWith("Alias_")) {
            return simple.removePrefix("Alias_").replace('_', '.')
        }
        return null
    }
}
