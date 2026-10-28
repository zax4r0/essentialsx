package eu.zax4r0.essentialsx.features.smartnet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmartNetworkController {

    private const val PREFS_NAME = "smart_network_prefs"
    private const val KEY_ENABLED = "smart_net_enabled"
    private const val KEY_AUTO_WIFI_DATA = "smart_net_auto_wifi_data"
    private const val KEY_AUTO_5G_4G = "smart_net_auto_5g_4g"
    private const val KEY_AUTO_SIM_SWITCH = "smart_net_auto_sim_switch"
    private const val KEY_SENSITIVITY = "smart_net_sensitivity"
    private const val KEY_TILE_CLICK_MODE = "smart_net_tile_click_mode"

    enum class SensitivityPreset(
        val displayName: String,
        val poor5gScoreThreshold: Int,
        val poor5gSamplesRequired: Int,
        val simLowThreshold: Int,
        val simHysteresisDelta: Int,
        val simSamplesRequired: Int,
        val simCooldownMs: Long,
        val wifiFailoverThreshold: Int,
        val wifiStayThreshold: Int,
        val wifiPoorSamplesRequired: Int
    ) {
        BALANCED(
            displayName = "Balanced (Recommended)",
            poor5gScoreThreshold = 38,
            poor5gSamplesRequired = 3,
            simLowThreshold = 48,
            simHysteresisDelta = 18,
            simSamplesRequired = 3,
            simCooldownMs = 60_000L,
            wifiFailoverThreshold = 38,
            wifiStayThreshold = 40,
            wifiPoorSamplesRequired = 2
        ),
        CONSERVATIVE(
            displayName = "Battery Saver (Conservative)",
            poor5gScoreThreshold = 30,
            poor5gSamplesRequired = 4,
            simLowThreshold = 40,
            simHysteresisDelta = 24,
            simSamplesRequired = 4,
            simCooldownMs = 90_000L,
            wifiFailoverThreshold = 30,
            wifiStayThreshold = 35,
            wifiPoorSamplesRequired = 3
        ),
        AGGRESSIVE(
            displayName = "Performance (Aggressive)",
            poor5gScoreThreshold = 46,
            poor5gSamplesRequired = 2,
            simLowThreshold = 58,
            simHysteresisDelta = 12,
            simSamplesRequired = 2,
            simCooldownMs = 30_000L,
            wifiFailoverThreshold = 45,
            wifiStayThreshold = 48,
            wifiPoorSamplesRequired = 2
        )
    }

    enum class TileClickMode(val displayName: String) {
        TOGGLE_ENGINE("Quick Toggle (Tap: ON/OFF • Hold: Smart Network Tray)"),
        OPEN_DRAWER("Smart Tray Drawer (Tap or Hold: Open Smart Network Tray)")
    }

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _autoWifiDataEnabled = MutableStateFlow(true)
    val autoWifiDataEnabled: StateFlow<Boolean> = _autoWifiDataEnabled

    private val _auto5g4gEnabled = MutableStateFlow(true)
    val auto5g4gEnabled: StateFlow<Boolean> = _auto5g4gEnabled

    private val _autoSimSwitchEnabled = MutableStateFlow(true)
    val autoSimSwitchEnabled: StateFlow<Boolean> = _autoSimSwitchEnabled

    private val _sensitivityPreset = MutableStateFlow(SensitivityPreset.BALANCED)
    val sensitivityPreset: StateFlow<SensitivityPreset> = _sensitivityPreset

    private val _tileClickMode = MutableStateFlow(TileClickMode.TOGGLE_ENGINE)
    val tileClickMode: StateFlow<TileClickMode> = _tileClickMode

    private val _wifiSnapshot = MutableStateFlow<NetworkQualityScorer.WifiQualitySnapshot?>(null)
    val wifiSnapshot: StateFlow<NetworkQualityScorer.WifiQualitySnapshot?> = _wifiSnapshot

    private val _simSnapshots = MutableStateFlow<Map<Int, NetworkQualityScorer.SimQualitySnapshot>>(emptyMap())
    val simSnapshots: StateFlow<Map<Int, NetworkQualityScorer.SimQualitySnapshot>> = _simSnapshots

    private val _engineStatus = MutableStateFlow("Idle • Engine turned off")
    val engineStatus: StateFlow<String> = _engineStatus

    private val _lastActionLog = MutableStateFlow("No automatic switches yet")
    val lastActionLog: StateFlow<String> = _lastActionLog

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _autoWifiDataEnabled.value = prefs.getBoolean(KEY_AUTO_WIFI_DATA, true)
        _auto5g4gEnabled.value = prefs.getBoolean(KEY_AUTO_5G_4G, true)
        _autoSimSwitchEnabled.value = prefs.getBoolean(KEY_AUTO_SIM_SWITCH, true)

        val presetName = prefs.getString(KEY_SENSITIVITY, SensitivityPreset.BALANCED.name)
        _sensitivityPreset.value = runCatching {
            SensitivityPreset.valueOf(presetName ?: SensitivityPreset.BALANCED.name)
        }.getOrDefault(SensitivityPreset.BALANCED)

        val clickModeName = prefs.getString(KEY_TILE_CLICK_MODE, TileClickMode.TOGGLE_ENGINE.name)
        _tileClickMode.value = runCatching {
            TileClickMode.valueOf(clickModeName ?: TileClickMode.TOGGLE_ENGINE.name)
        }.getOrDefault(TileClickMode.TOGGLE_ENGINE)

        if (_isEnabled.value) {
            startService(context)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _isEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            startService(context)
        } else {
            stopService(context)
        }
        refreshTile(context)
    }

    fun toggle(context: Context) {
        setEnabled(context, !_isEnabled.value)
    }

    fun setAutoWifiDataEnabled(context: Context, enabled: Boolean) {
        _autoWifiDataEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_WIFI_DATA, enabled).apply()
        refreshTile(context)
    }

    fun setAuto5g4gEnabled(context: Context, enabled: Boolean) {
        _auto5g4gEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_5G_4G, enabled).apply()
        refreshTile(context)
    }

    fun setAutoSimSwitchEnabled(context: Context, enabled: Boolean) {
        _autoSimSwitchEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SIM_SWITCH, enabled).apply()
        refreshTile(context)
    }

    fun setSensitivityPreset(context: Context, preset: SensitivityPreset) {
        _sensitivityPreset.value = preset
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SENSITIVITY, preset.name).apply()
    }

    fun setTileClickMode(context: Context, mode: TileClickMode) {
        _tileClickMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TILE_CLICK_MODE, mode.name).apply()
        refreshTile(context)
    }

    fun updateSnapshots(
        wifi: NetworkQualityScorer.WifiQualitySnapshot?,
        sims: Map<Int, NetworkQualityScorer.SimQualitySnapshot>,
        status: String
    ) {
        _wifiSnapshot.value = wifi
        _simSnapshots.value = sims
        _engineStatus.value = status
    }

    fun recordAction(actionMessage: String) {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _lastActionLog.value = "[$timeFmt] $actionMessage"
    }

    fun updateActiveStateFromService(active: Boolean, status: String) {
        _isEnabled.value = active
        _engineStatus.value = status
    }

    private fun startService(context: Context) {
        runCatching {
            val intent = Intent(context, SmartNetworkService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun stopService(context: Context) {
        runCatching {
            val intent = Intent(context, SmartNetworkService::class.java).apply {
                action = SmartNetworkService.ACTION_STOP
            }
            context.startService(intent)
        }
        _engineStatus.value = "Idle • Engine turned off"
    }

    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, SmartNetworkTileService::class.java)
            )
        }
        eu.zax4r0.essentialsx.features.widgets.EssentialsWidgetsUpdater.updateAll(context)
    }
}
