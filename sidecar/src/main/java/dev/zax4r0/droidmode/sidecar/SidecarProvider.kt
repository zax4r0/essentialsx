package dev.zax4r0.droidmode.sidecar

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

class SidecarProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        when (method) {
            METHOD_SET_STATE -> {
                val targetPkg = extras?.getString(EXTRA_PACKAGE)
                val alias = extras?.getString(EXTRA_ALIAS)
                    ?: targetPkg?.let { toAliasClassName(it) }
                    ?: return null
                val enabled = extras?.getBoolean(EXTRA_ENABLED, false) ?: false
                setComponentEnabled(ctx, alias, enabled)
                return Bundle().apply { putBoolean(EXTRA_SUCCESS, true) }
            }
            METHOD_SET_ALL_STATES -> {
                val pkgs = extras?.getStringArray(EXTRA_PACKAGES) ?: emptyArray()
                val states = extras?.getBooleanArray(EXTRA_STATES) ?: booleanArrayOf()
                for (i in pkgs.indices) {
                    val pkg = pkgs[i]
                    val enabled = if (i < states.size) states[i] else false
                    val alias = toAliasClassName(pkg)
                    setComponentEnabled(ctx, alias, enabled)
                }
                return Bundle().apply { putBoolean(EXTRA_SUCCESS, true) }
            }
        }
        return null
    }

    private fun setComponentEnabled(context: Context, aliasClassName: String, enabled: Boolean) {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val comp = ComponentName(context, aliasClassName)
        try {
            context.packageManager.setComponentEnabledSetting(
                comp,
                newState,
                PackageManager.DONT_KILL_APP
            )
            Log.d(TAG, "setComponentEnabled: $aliasClassName -> $newState")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setComponentEnabled for $aliasClassName", e)
        }
    }

    private fun toAliasClassName(packageName: String): String =
        "dev.zax4r0.droidmode.sidecar.Alias_${packageName.replace('.', '_')}"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "SidecarProvider"
        const val AUTHORITY = "dev.zax4r0.droidmode.sidecar.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

        const val METHOD_SET_STATE = "set_state"
        const val METHOD_SET_ALL_STATES = "set_all_states"

        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ALIAS = "alias"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_STATES = "states"
        const val EXTRA_SUCCESS = "success"
    }
}
