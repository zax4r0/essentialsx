package dev.zax4r0.droidmode.sidecar

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPkg = intent.getStringExtra("target_package")
            ?: getTargetPackageFromMeta()
            ?: componentNameToPackage(componentName?.className)

        if (!targetPkg.isNullOrBlank()) {
            val forward = Intent().apply {
                component = ComponentName("dev.zax4r0.droidmode", "dev.zax4r0.droidmode.features.freezer.UnfreezeAndLaunchActivity")
                putExtra("target_package", targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(forward)
            } catch (_: Exception) {
            }
        }
        finish()
    }

    private fun getTargetPackageFromMeta(): String? = runCatching {
        val info = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
        info.metaData?.getString("target_package")
    }.getOrNull()

    private fun componentNameToPackage(className: String?): String? {
        if (className == null) return null
        val prefix = "dev.zax4r0.droidmode.sidecar.Alias_"
        if (className.startsWith(prefix)) {
            return className.substring(prefix.length).replace('_', '.')
        }
        val prefix2 = "dev.zax4r0.droidmode.sidecar.Trampoline_"
        if (className.startsWith(prefix2)) {
            return className.substring(prefix2.length).replace('_', '.')
        }
        return null
    }
}
