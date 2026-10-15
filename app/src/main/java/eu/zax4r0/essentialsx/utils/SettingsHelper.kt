package eu.zax4r0.essentialsx.utils

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

object SettingsHelper {

    private const val TAG = "SettingsHelper"

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun getGlobalFloat(cr: ContentResolver, name: String, default: Float): Float =
        try {
            Settings.Global.getFloat(cr, name, default)
        } catch (e: Exception) {
            default
        }

    fun putGlobalFloat(cr: ContentResolver, name: String, value: Float): Boolean =
        try {
            Settings.Global.putFloat(cr, name, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write global float $name: ${e.message}")
            false
        }

    fun getGlobalInt(cr: ContentResolver, name: String, default: Int): Int =
        try {
            Settings.Global.getInt(cr, name, default)
        } catch (e: Exception) {
            default
        }

    fun putGlobalInt(cr: ContentResolver, name: String, value: Int): Boolean =
        try {
            Settings.Global.putInt(cr, name, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write global int $name: ${e.message}")
            false
        }

    fun getGlobalString(cr: ContentResolver, name: String): String? =
        try {
            Settings.Global.getString(cr, name)
        } catch (e: Exception) {
            null
        }

    fun putGlobalString(cr: ContentResolver, name: String, value: String): Boolean =
        try {
            Settings.Global.putString(cr, name, value)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write global string $name: ${e.message}")
            false
        }
}
