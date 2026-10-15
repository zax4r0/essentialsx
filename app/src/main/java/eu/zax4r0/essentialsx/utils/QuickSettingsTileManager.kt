package eu.zax4r0.essentialsx.utils

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import eu.zax4r0.essentialsx.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QuickSettingsTileManager {

    fun getSysuiQsTiles(context: Context): String {
        val secureString = runCatching {
            Settings.Secure.getString(context.contentResolver, "sysui_qs_tiles")
        }.getOrNull()

        if (!secureString.isNullOrBlank()) {
            return secureString
        }

        val shellOutput = ShizukuHelper.runCommandWithOutput("settings get secure sysui_qs_tiles")
        return shellOutput?.trim()?.takeIf { it != "null" } ?: ""
    }

    fun isTileAdded(context: Context, serviceClass: Class<*>): Boolean {
        val tiles = getSysuiQsTiles(context)
        val componentName = ComponentName(context, serviceClass)
        val shortSpec = "custom(${componentName.flattenToShortString()})"
        val fullSpec = "custom(${componentName.flattenToString()})"
        return tiles.contains(serviceClass.name) || tiles.contains(shortSpec) || tiles.contains(fullSpec)
    }

    suspend fun addTileToLast(
        context: Context,
        serviceClass: Class<*>,
        tileLabel: String,
        iconRes: Int,
        onComplete: (Boolean, String) -> Unit
    ) {
        val currentTiles = withContext(Dispatchers.IO) { getSysuiQsTiles(context) }
        val componentName = ComponentName(context, serviceClass)
        val tileSpec = "custom(${componentName.flattenToShortString()})"
        val fullSpec = "custom(${componentName.flattenToString()})"

        // Check if already added
        if (currentTiles.contains(serviceClass.name) || currentTiles.contains(tileSpec) || currentTiles.contains(fullSpec)) {
            withContext(Dispatchers.Main) {
                onComplete(true, "Tile is already added to Quick Settings")
            }
            return
        }

        val updatedTiles = if (currentTiles.isBlank()) tileSpec else "$currentTiles,$tileSpec"

        // Try direct Settings.Secure write if WRITE_SECURE_SETTINGS granted
        var success = runCatching {
            Settings.Secure.putString(context.contentResolver, "sysui_qs_tiles", updatedTiles)
        }.getOrDefault(false)

        // Try Shizuku / Porter shell write
        if (!success || ShizukuHelper.hasPorterPermission()) {
            val shellSuccess = withContext(Dispatchers.IO) {
                ShizukuHelper.runCommand("settings put secure sysui_qs_tiles \"$updatedTiles\"")
            }
            if (shellSuccess) success = true
        }

        // Verify if it was saved
        val verified = withContext(Dispatchers.IO) { getSysuiQsTiles(context) }
        if (verified.contains(serviceClass.name) || verified.contains(tileSpec) || verified.contains(fullSpec)) {
            withContext(Dispatchers.Main) {
                onComplete(true, "Tile added to the end of Quick Settings")
            }
            return
        }

        // Fallback to native StatusBarManager API if direct append not permitted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                componentName,
                tileLabel,
                Icon.createWithResource(context, iconRes),
                context.mainExecutor
            ) { result ->
                val ok = (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                val msg = if (ok) "Tile added to Quick Settings" else "Tile addition cancelled"
                onComplete(ok, msg)
            }
        } else {
            withContext(Dispatchers.Main) {
                onComplete(false, "Could not add tile. Please grant Porter permission.")
            }
        }
    }
}
