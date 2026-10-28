package eu.zax4r0.essentialsx.utils

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE_SHIZUKU = 1005
    private const val APP_PACKAGE = "eu.zax4r0.essentialsx"
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var permissionsGrantedOnce = false

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateState()
    }

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            _hasPermission.value = (grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    fun init() {
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            updateState()
        }
    }

    fun updateState() {
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        _isAvailable.value = available
        val granted = if (available) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        } else {
            false
        }
        _hasPermission.value = granted
        if (granted && !permissionsGrantedOnce) {
            permissionsGrantedOnce = true
            helperScope.launch {
                runCommand("pm grant $APP_PACKAGE android.permission.POST_NOTIFICATIONS")
                runCommand("pm grant $APP_PACKAGE android.permission.WRITE_SECURE_SETTINGS")
                runCommand("appops set $APP_PACKAGE REQUEST_INSTALL_PACKAGES allow")
            }
        }
    }

    fun isPorterRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPorterPermission(): Boolean = isPorterRunning() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() {
        if (!isPorterRunning()) return
        runCatching {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            }
        }.onFailure {
            Log.e(TAG, "Failed to request Shizuku permission", it)
        }
    }

    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    private fun startRemoteProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = "/"): Process {
        return newProcessMethod.invoke(null, cmd, env, dir) as Process
    }

    fun runCommand(cmd: String): Boolean {
        if (!hasPorterPermission()) return false
        return try {
            val process = startRemoteProcess(arrayOf("sh", "-c", cmd), null, "/")
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $cmd", e)
            false
        }
    }

    fun runCommandWithOutput(cmd: String): String? {
        if (!hasPorterPermission()) return null
        return try {
            val process = startRemoteProcess(arrayOf("sh", "-c", cmd), null, "/")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command with output: $cmd", e)
            null
        }
    }
}
