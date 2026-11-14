package dev.zax4r0.droidmode.utils

import android.content.Context
import android.util.Log
import dev.zax4r0.droidmode.features.freezer.AppFreezerController
import dev.zax4r0.droidmode.features.widgets.DroidModeWidgetsUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

object BackupRestoreManager {
    private const val TAG = "BackupRestoreManager"
    private const val BACKUP_VERSION = 1

    private val PREF_FILES = listOf(
        "app_freezer",
        "caffeine_prefs",
        "smart_network_prefs",
        "wifi_priority_prefs",
        "animator_prefs",
        "update_manager_prefs"
    )

    suspend fun exportSettings(context: Context, outputStream: OutputStream): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject()
                root.put("version", BACKUP_VERSION)
                root.put("timestamp", System.currentTimeMillis())
                root.put("app", context.packageName)

                val prefsObj = JSONObject()
                var keyCount = 0

                for (prefName in PREF_FILES) {
                    val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val all = sp.all
                    val fileObj = JSONObject()
                    for ((k, v) in all) {
                        when (v) {
                            is Boolean -> fileObj.put(k, v)
                            is Int -> fileObj.put(k, v)
                            is Long -> fileObj.put(k, v)
                            is Float -> fileObj.put(k, v.toDouble())
                            is String -> fileObj.put(k, v)
                            is Set<*> -> {
                                val arr = JSONArray()
                                for (item in v) {
                                    arr.put(item.toString())
                                }
                                fileObj.put(k, arr)
                            }
                        }
                        keyCount++
                    }
                    prefsObj.put(prefName, fileObj)
                }

                root.put("preferences", prefsObj)
                outputStream.bufferedWriter().use { it.write(root.toString(2)) }
                Result.success(keyCount)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                Result.failure(e)
            }
        }

    suspend fun importSettings(context: Context, inputStream: InputStream): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val jsonStr = inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)

                val prefsObj = root.optJSONObject("preferences")
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid backup format: missing preferences"))

                var restoredKeys = 0

                for (prefName in PREF_FILES) {
                    val fileObj = prefsObj.optJSONObject(prefName) ?: continue
                    val sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    val editor = sp.edit()

                    val keys = fileObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = fileObj.get(key)
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is String -> editor.putString(key, value)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) {
                                    set.add(value.getString(i))
                                }
                                editor.putStringSet(key, set)
                            }
                        }
                        restoredKeys++
                    }
                    editor.apply()
                }

                // Refresh controllers and widgets
                AppFreezerController.refresh(context)
                DroidModeWidgetsUpdater.updateAll(context)

                Result.success(restoredKeys)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                Result.failure(e)
            }
        }
}
