package eu.zax4r0.essentialsx.features.wifi

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object WifiPriorityController {

    private const val TAG = "WifiPriorityController"
    private const val PREFS_NAME = "wifi_priority_prefs"
    private const val KEY_ENABLED = "wifi_priority_enabled"
    private const val KEY_ORDER = "wifi_priority_order"

    const val CHECK_INTERVAL_MS = 30_000L
    private const val SCAN_WAIT_MS = 4_000L
    private const val RETRY_INTERVAL_MS = 120_000L // don't re-try the same target faster than this
    private const val MAX_SCAN_AGE_SEC = 60.0

    data class SavedNetwork(val id: Int, val ssid: String)

    private data class Snapshot(
        val wifiOn: Boolean,
        val connected: String?,
        val saved: List<SavedNetwork>,
        val visible: Set<String>
    )

    // Saved networks in priority order (top = highest).
    private val _networks = MutableStateFlow<List<SavedNetwork>>(emptyList())
    val networks: StateFlow<List<SavedNetwork>> = _networks

    private val _inRange = MutableStateFlow<Set<String>>(emptySet())
    val inRange: StateFlow<Set<String>> = _inRange

    private val _connectedSsid = MutableStateFlow<String?>(null)
    val connectedSsid: StateFlow<String?> = _connectedSsid

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    @Volatile private var order: List<String> = emptyList()
    @Volatile private var lastAttemptSsid: String? = null
    @Volatile private var lastAttemptAt = 0L

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabled.value = prefs.getBoolean(KEY_ENABLED, false)
        order = readOrder(prefs.getString(KEY_ORDER, null))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _isEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        val intent = Intent(context, WifiPriorityService::class.java)
        if (enabled) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
    }

    fun move(context: Context, from: Int, to: Int) {
        val list = _networks.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        _networks.value = list
        order = list.map { it.ssid }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, JSONArray(order).toString()).apply()
    }

    suspend fun refresh(scan: Boolean = false) {
        if (!ShizukuHelper.hasPorterPermission()) return
        withContext(Dispatchers.IO) {
            if (scan) {
                ShizukuHelper.runCommand("cmd wifi start-scan")
                delay(SCAN_WAIT_MS)
            }
            publish(readSnapshot())
        }
    }

    // One pass of the whole rule: the highest-priority saved network in range wins.
    suspend fun tick() {
        if (!ShizukuHelper.hasPorterPermission()) return
        withContext(Dispatchers.IO) {
            ShizukuHelper.runCommand("cmd wifi start-scan")
            delay(SCAN_WAIT_MS)
            val snapshot = readSnapshot()
            publish(snapshot)
            if (!snapshot.wifiOn) return@withContext

            val target = pickTarget(_networks.value, snapshot.visible, snapshot.connected)
                ?: return@withContext
            val now = System.currentTimeMillis()
            if (target.ssid == lastAttemptSsid && now - lastAttemptAt < RETRY_INTERVAL_MS) return@withContext
            lastAttemptSsid = target.ssid
            lastAttemptAt = now

            Log.i(TAG, "Switching to ${target.ssid} (id ${target.id})")
            connectSaved(target.id)
        }
    }

    private fun readSnapshot(): Snapshot {
        val status = ShizukuHelper.runCommandWithOutput("cmd wifi status | head -n 6")
        val saved = parseSavedNetworks(ShizukuHelper.runCommandWithOutput("cmd wifi list-networks"))
        val visible = parseVisibleSsids(ShizukuHelper.runCommandWithOutput("cmd wifi list-scan-results"))
        return Snapshot(isWifiOn(status), parseConnectedSsid(status), saved, visible)
    }

    private fun publish(snapshot: Snapshot) {
        _networks.value = applyOrder(snapshot.saved, order)
        _inRange.value = snapshot.visible
        _connectedSsid.value = snapshot.connected
    }

    private val hiddenApiExempted by lazy { HiddenApiBypass.addHiddenApiExemptions("L") }

    // IWifiManager.enableNetwork(netId, disableOthers, pkg): for a privileged caller this connects
    // to an already-saved network (what Settings does on tap), for any security type, no password.
    // The package must match the caller's uid, which is "root" for root Shizuku and shell otherwise.
    private fun connectSaved(netId: Int): Boolean = try {
        hiddenApiExempted
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("wifi"))
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val pkg = if (Shizuku.getUid() == 0) "root" else "com.android.shell"
        Class.forName("android.net.wifi.IWifiManager")
            .getMethod(
                "enableNetwork",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java
            )
            .invoke(wifi, netId, true, pkg) as Boolean
    } catch (e: Exception) {
        Log.e(TAG, "Failed to connect to network id $netId", e)
        false
    }

    private fun readOrder(json: String?): List<String> = runCatching {
        val array = JSONArray(json ?: return emptyList())
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    // ---- pure logic (unit tested) ----

    // Highest-priority network that is in range; null if we're already on it.
    internal fun pickTarget(ranked: List<SavedNetwork>, visible: Set<String>, connected: String?): SavedNetwork? =
        ranked.firstOrNull { it.ssid in visible }?.takeIf { it.ssid != connected }

    // Stored order first; networks saved since then go to the bottom. One entry per SSID.
    internal fun applyOrder(saved: List<SavedNetwork>, order: List<String>): List<SavedNetwork> {
        val bySsid = LinkedHashMap<String, SavedNetwork>()
        saved.forEach { bySsid.putIfAbsent(it.ssid, it) }
        val ranked = order.mapNotNull { bySsid.remove(it) }
        return ranked + bySsid.values
    }

    private val savedRow = Regex("""^\s*(\d+)\s+(.+?)\s+(\S+)\s*$""")
    private val scanRow = Regex(
        """^\s*[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\s+\d+\s+-?\d+\S*\s+(>?)([\d.]+)\s+(.*?)\s*(?:\[[A-Z].*)?$"""
    )
    private val connectedRow = Regex("""^Wifi is connected to "(.*)"\s*$""", RegexOption.MULTILINE)

    // `cmd wifi list-networks`: one row per (id, security type), so dedupe by id.
    internal fun parseSavedNetworks(output: String?): List<SavedNetwork> {
        if (output.isNullOrBlank()) return emptyList()
        val byId = LinkedHashMap<Int, SavedNetwork>()
        output.lineSequence().forEach { line ->
            val match = savedRow.find(line) ?: return@forEach
            val id = match.groupValues[1].toIntOrNull() ?: return@forEach
            val ssid = match.groupValues[2].trim()
            if (ssid.isNotEmpty()) byId.putIfAbsent(id, SavedNetwork(id, ssid))
        }
        return byId.values.toList()
    }

    // `cmd wifi list-scan-results`: only fresh results count (old ones show Age > 1000).
    internal fun parseVisibleSsids(output: String?): Set<String> {
        if (output.isNullOrBlank()) return emptySet()
        val ssids = LinkedHashSet<String>()
        output.lineSequence().forEach { line ->
            val match = scanRow.find(line) ?: return@forEach
            val age = if (match.groupValues[1] == ">") Double.MAX_VALUE
            else match.groupValues[2].toDoubleOrNull() ?: Double.MAX_VALUE
            val ssid = match.groupValues[3].trim()
            if (age <= MAX_SCAN_AGE_SEC && ssid.isNotEmpty()) ssids.add(ssid)
        }
        return ssids
    }

    internal fun parseConnectedSsid(status: String?): String? =
        status?.let { connectedRow.find(it)?.groupValues?.get(1) }?.takeIf { it.isNotEmpty() }

    internal fun isWifiOn(status: String?): Boolean =
        status?.lineSequence()?.any { it.trim() == "Wifi is enabled" } == true
}
