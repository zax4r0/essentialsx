package dev.zax4r0.droidmode.features.freezer

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import dev.zax4r0.droidmode.utils.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object AppTrackerManager {
    private const val TAG = "AppTrackerManager"
    private val scope = CoroutineScope(Dispatchers.Default)
    private var trackingJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    private val monitoredPackages = ConcurrentHashMap.newKeySet<String>()
    private val foregroundConfirmed = ConcurrentHashMap<String, Boolean>()
    private val launchTimestamp = ConcurrentHashMap<String, Long>()

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun ensureUsageStatsPermission(context: Context): Boolean {
        if (hasUsageStatsPermission(context)) return true
        if (ShizukuHelper.hasPorterPermission()) {
            val pkg = context.packageName
            val cmd = "appops set $pkg GET_USAGE_STATS allow; appops set $pkg android:get_usage_stats allow; pm grant $pkg android.permission.PACKAGE_USAGE_STATS"
            ShizukuHelper.runCommand(cmd)
            delay(150)
            return hasUsageStatsPermission(context)
        }
        return false
    }

    fun startTracking(context: Context, packageName: String) {
        val appContext = context.applicationContext
        if (!AppFreezerController.isAutoFreezeOnExit(appContext) && !AppFreezerController.isAutoFreezeOnLock(appContext)) {
            Log.d(TAG, "Auto-freeze disabled in settings, skipping tracking for $packageName")
            return
        }

        monitoredPackages.add(packageName)
        foregroundConfirmed[packageName] = false
        launchTimestamp[packageName] = System.currentTimeMillis()

        registerScreenOffReceiver(appContext)

        synchronized(this) {
            if (trackingJob?.isActive == true) return
            trackingJob = scope.launch {
                ensureUsageStatsPermission(appContext)
                monitorLoop(appContext)
            }
        }
    }

    private fun registerScreenOffReceiver(context: Context) {
        synchronized(this) {
            if (isReceiverRegistered) return
            screenOffReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        Log.d(TAG, "Screen off detected, auto-freezing active packages: $monitoredPackages")
                        val currentContext = ctx ?: context
                        scope.launch {
                            val pkgsToFreeze = monitoredPackages.toList()
                            for (pkg in pkgsToFreeze) {
                                if (AppFreezerController.isAutoFreezeOnLock(currentContext)) {
                                    AppFreezerController.setFrozen(currentContext, pkg, true)
                                    if (SidecarBuilder.isSidecarInstalled(currentContext)) {
                                        SidecarBuilder.syncAliasState(currentContext, pkg, true)
                                    }
                                }
                                monitoredPackages.remove(pkg)
                                foregroundConfirmed.remove(pkg)
                                launchTimestamp.remove(pkg)
                            }
                            stopTracking(currentContext)
                        }
                    }
                }
            }
            context.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            isReceiverRegistered = true
        }
    }

    private fun unregisterScreenOffReceiver(context: Context) {
        synchronized(this) {
            if (!isReceiverRegistered) return
            try {
                screenOffReceiver?.let { context.unregisterReceiver(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering screen off receiver", e)
            }
            screenOffReceiver = null
            isReceiverRegistered = false
        }
    }

    private suspend fun monitorLoop(context: Context) {
        val awayCount = ConcurrentHashMap<String, Int>()

        while (scope.isActive && monitoredPackages.isNotEmpty()) {
            delay(750)
            if (monitoredPackages.isEmpty()) break

            val currentFg = getForegroundPackage(context)
            val now = System.currentTimeMillis()
            val pkgs = monitoredPackages.toList()

            for (pkg in pkgs) {
                val isFg = (currentFg == pkg)
                if (isFg) {
                    foregroundConfirmed[pkg] = true
                    awayCount[pkg] = 0
                } else {
                    val wasFg = foregroundConfirmed[pkg] == true
                    val started = launchTimestamp[pkg] ?: now

                    if (wasFg) {
                        val count = (awayCount[pkg] ?: 0) + 1
                        awayCount[pkg] = count

                        val isHome = isLauncher(context, currentFg)
                        if (isHome || count >= 2) {
                            Log.d(TAG, "App $pkg left foreground (currentFg=$currentFg, isHome=$isHome). Re-freezing.")
                            freezeAndClean(context, pkg)
                        }
                    } else {
                        if (now - started > 12_000) {
                            Log.w(TAG, "App $pkg never reached foreground within timeout. Safety re-freezing.")
                            freezeAndClean(context, pkg)
                        }
                    }
                }
            }
        }

        if (monitoredPackages.isEmpty()) {
            stopTracking(context)
        }
    }

    private suspend fun freezeAndClean(context: Context, packageName: String) {
        monitoredPackages.remove(packageName)
        foregroundConfirmed.remove(packageName)
        launchTimestamp.remove(packageName)

        if (AppFreezerController.isAutoFreezeOnExit(context)) {
            AppFreezerController.setFrozen(context, packageName, true)
            if (SidecarBuilder.isSidecarInstalled(context)) {
                SidecarBuilder.syncAliasState(context, packageName, true)
            }
        }
    }

    private fun stopTracking(context: Context) {
        synchronized(this) {
            trackingJob?.cancel()
            trackingJob = null
            unregisterScreenOffReceiver(context)
        }
    }

    fun getForegroundPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager != null && hasUsageStatsPermission(context)) {
            val time = System.currentTimeMillis()
            val events = usageStatsManager.queryEvents(time - 10_000, time)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                    latestPkg = event.packageName
                    latestTime = event.timeStamp
                }
            }
            if (latestPkg != null) return latestPkg
        }

        return getForegroundPackageFallback()
    }

    private fun getForegroundPackageFallback(): String? {
        if (!ShizukuHelper.hasPorterPermission()) return null
        val output = ShizukuHelper.runCommandWithOutput("dumpsys activity activities") ?: return null
        val match = Regex("""topResumedActivity=ActivityRecord\{[^ ]+ [^ ]+ ([^/]+)/""").find(output)
        return match?.groupValues?.get(1)
    }

    private fun isLauncher(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName == "com.google.android.apps.nexuslauncher" ||
            packageName == "com.android.launcher3" ||
            packageName.contains("launcher", ignoreCase = true)) {
            return true
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }
}
