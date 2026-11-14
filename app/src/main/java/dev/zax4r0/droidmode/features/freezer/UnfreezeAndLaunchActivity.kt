package dev.zax4r0.droidmode.features.freezer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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

        // Setup immediate launch UI so launcher is covered and does not flicker
        setupLaunchUI(targetPkg)

        CoroutineScope(Dispatchers.Main).launch {
            // Auto-grant usage stats permission via Shizuku if needed
            AppTrackerManager.ensureUsageStatsPermission(this@UnfreezeAndLaunchActivity)

            val unfreezeSuccess = AppFreezerController.setFrozen(this@UnfreezeAndLaunchActivity, targetPkg, false)
            if (unfreezeSuccess) {
                var launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                var retries = 0
                while (launchIntent == null && retries < 8) {
                    delay(80)
                    launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)
                    retries++
                }
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)

                    // Start monitoring to auto-freeze when user exits or screen turns off
                    AppTrackerManager.startTracking(applicationContext, targetPkg)
                } else {
                    Toast.makeText(this@UnfreezeAndLaunchActivity, "Cannot launch $targetPkg", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@UnfreezeAndLaunchActivity, "Failed to unfreeze $targetPkg (Check Shizuku)", Toast.LENGTH_SHORT).show()
            }
            DroidModeWidgetsUpdater.updateAll(applicationContext)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun setupLaunchUI(packageName: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#141416"))
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }

        val appIconView = ImageView(this).apply {
            val iconSize = dp(72)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            setImageDrawable(getAppIcon(packageName))
        }

        val appNameView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            text = getAppLabel(packageName)
            setTextColor(Color.parseColor("#E4E2E6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val progress = ProgressBar(this).apply {
            val pSize = dp(36)
            layoutParams = LinearLayout.LayoutParams(pSize, pSize).apply {
                topMargin = dp(24)
            }
            isIndeterminate = true
        }

        val statusView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
            text = "Starting app…"
            setTextColor(Color.parseColor("#919094"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }

        root.addView(appIconView)
        root.addView(appNameView)
        root.addView(progress)
        root.addView(statusView)

        setContentView(root)
    }

    private fun getAppIcon(packageName: String) = runCatching {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, flags)
        }
        packageManager.getApplicationIcon(info)
    }.getOrElse {
        packageManager.defaultActivityIcon
    }

    private fun getAppLabel(packageName: String): String = runCatching {
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, flags)
        }
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
