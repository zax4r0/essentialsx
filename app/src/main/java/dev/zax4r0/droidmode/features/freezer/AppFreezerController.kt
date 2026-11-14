package dev.zax4r0.droidmode.features.freezer

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import dev.zax4r0.droidmode.features.widgets.DroidModeWidgetsUpdater
import dev.zax4r0.droidmode.utils.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

data class FreezableApp(
    val packageName: String,
    val label: String,
    val isSelected: Boolean,
    val isFrozen: Boolean
)

enum class BulkFreezeAction { FREEZE, UNFREEZE, NONE }

object AppFreezerController {
    private const val TAG = "AppFreezerController"
    private const val PREFS = "app_freezer"
    private const val KEY_SELECTED = "selected_packages"

    private val _apps = MutableStateFlow<List<FreezableApp>>(emptyList())
    val apps: StateFlow<List<FreezableApp>> = _apps

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _operation = MutableStateFlow<String?>(null)
    val operation: StateFlow<String?> = _operation

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val selected = selectedPackages(context)
        _apps.value = selected.map { FreezableApp(it, it, isSelected = true, isFrozen = false) }
    }

    @Suppress("DEPRECATION")
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val appContext = context.applicationContext
            val pm = appContext.packageManager
            val selected = selectedPackages(appContext)
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS
            val launchable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                pm.queryIntentActivities(launcherIntent, flags)
            }

            val packageNames = (launchable.map { it.activityInfo.packageName } + selected)
                .asSequence()
                .filter { it != appContext.packageName && it != SidecarBuilder.SIDECAR_PACKAGE }
                .distinct()
                .toList()

            _apps.value = packageNames.mapNotNull { packageName ->
                runCatching {
                    val info = getApplicationInfo(pm, packageName)
                    FreezableApp(
                        packageName = packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        isSelected = packageName in selected,
                        isFrozen = isPackageFrozen(pm, packageName, info)
                    )
                }.getOrNull()
            }.sortedWith(
                compareByDescending<FreezableApp> { it.isSelected }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
        } finally {
            _isLoading.value = false
        }
    }

    fun setSelected(context: Context, packageName: String, selected: Boolean) {
        val updated = selectedPackages(context).toMutableSet().apply {
            if (selected) add(packageName) else remove(packageName)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SELECTED, updated).apply()
        _apps.value = _apps.value.map {
            if (it.packageName == packageName) it.copy(isSelected = selected) else it
        }.sortedWith(
            compareByDescending<FreezableApp> { it.isSelected }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
        DroidModeWidgetsUpdater.updateAll(context.applicationContext)
    }

    suspend fun setFrozen(context: Context, packageName: String, frozen: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!validPackageName(packageName) || packageName == context.packageName) return@withContext false
            if (!ShizukuHelper.hasPorterPermission()) return@withContext false

            _operation.value = if (frozen) "Freezing…" else "Unfreezing…"
            try {
                val userId = currentUserId()
                val success = setEnabledWithShizuku(packageName, !frozen) ||
                    ShizukuHelper.runCommand(
                        if (frozen) {
                            "am force-stop $packageName; pm disable-user --user $userId $packageName"
                        } else {
                            "pm enable --user $userId $packageName"
                        }
                    )
                if (success) {
                    _apps.value = _apps.value.map {
                        if (it.packageName == packageName) it.copy(isFrozen = frozen) else it
                    }
                    if (SidecarBuilder.isSidecarInstalled(context)) {
                        SidecarBuilder.syncAliasState(context, packageName, frozen)
                    }
                    DroidModeWidgetsUpdater.updateAll(context.applicationContext)
                }
                success
            } finally {
                _operation.value = null
            }
        }

    suspend fun performBulkAction(context: Context): Pair<BulkFreezeAction, Int> {
        val selected = _apps.value.filter { it.isSelected }
        val action = nextBulkAction(selected.size, selected.count { it.isFrozen })
        if (action == BulkFreezeAction.NONE) return action to 0

        var changed = 0
        selected.forEach { app ->
            val targetFrozen = action == BulkFreezeAction.FREEZE
            if (app.isFrozen != targetFrozen && setFrozen(context, app.packageName, targetFrozen)) changed++
        }
        if (SidecarBuilder.isSidecarInstalled(context)) {
            SidecarBuilder.syncAllStates(context, _apps.value.filter { it.isSelected })
        }
        refresh(context)
        return action to changed
    }

    fun nextBulkAction(selectedCount: Int, frozenCount: Int): BulkFreezeAction = when {
        selectedCount == 0 -> BulkFreezeAction.NONE
        frozenCount == selectedCount -> BulkFreezeAction.UNFREEZE
        else -> BulkFreezeAction.FREEZE
    }

    private fun selectedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTED, emptySet())?.toSet().orEmpty()

    private fun validPackageName(packageName: String): Boolean =
        packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))

    private fun isPackageFrozen(pm: PackageManager, packageName: String, info: ApplicationInfo): Boolean {
        val setting = runCatching { pm.getApplicationEnabledSetting(packageName) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            !info.enabled
    }

    @Suppress("DEPRECATION")
    private fun getApplicationInfo(pm: PackageManager, packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())
            )
        } else {
            pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        }

    // Android assigns each user a block of 100,000 UIDs; the app's UID identifies its profile.
    private fun currentUserId(): Int = android.os.Process.myUid() / 100_000

    private fun setEnabledWithShizuku(packageName: String, enabled: Boolean): Boolean {
        return try {
            if (!enabled) ShizukuHelper.runCommand("am force-stop $packageName")
            val pm = getService("package", "android.content.pm.IPackageManager\$Stub") ?: return false
            val userId = currentUserId()
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            val caller = if (Shizuku.getUid() == 0) "dev.zax4r0.droidmode" else "com.android.shell"

            val attempts = listOf<() -> Any?>(
                {
                    HiddenApiBypass.invoke(
                        pm.javaClass, pm, "setApplicationEnabledSetting", packageName, newState,
                        0, userId, caller
                    )
                },
                {
                    HiddenApiBypass.invoke(
                        pm.javaClass, pm, "setApplicationEnabledSetting", packageName, newState,
                        0, userId
                    )
                }
            )
            attempts.any { attempt ->
                runCatching {
                    attempt()
                    true
                }.getOrDefault(false)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Package setApplicationEnabledSetting binder call failed", error)
            false
        }
    }

    private fun getService(serviceName: String, stubClassName: String): Any? = runCatching {
        val binder = SystemServiceHelper.getSystemService(serviceName) ?: return null
        val stubClass = Class.forName(stubClassName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.invoke(stubClass, null, "asInterface", ShizukuBinderWrapper(binder))
        } else {
            stubClass.getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))
        }
    }.getOrNull()
}
