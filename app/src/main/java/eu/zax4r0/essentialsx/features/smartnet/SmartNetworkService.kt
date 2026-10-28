package eu.zax4r0.essentialsx.features.smartnet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import eu.zax4r0.essentialsx.MainActivity
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SmartNetworkService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScreenOn = true
    private var inVoiceCall = false

    // Per-subId live RF cache populated passively by TelephonyCallback / PhoneStateListener
    private val latestSignalMap = ConcurrentHashMap<Int, SignalStrength>()
    private val latest5gDisplayMap = ConcurrentHashMap<Int, Boolean>()
    private val simEmaScores = ConcurrentHashMap<Int, Int>()
    private val simLatencyCache = ConcurrentHashMap<Int, Pair<Long, Int>>() // subId -> (timestampMs, rttMs)

    // 5G/4G state tracking per subId
    private val consecutivePoor5gMap = ConcurrentHashMap<Int, Int>()
    private val lockedTo4gByEngineMap = ConcurrentHashMap<Int, Boolean>()
    private val next5gProbeEligibleMsMap = ConcurrentHashMap<Int, Long>()
    private val backoffStep5gMap = ConcurrentHashMap<Int, Int>()

    // Dual-SIM hysteresis state
    private var consecutiveCandidateBetterCount = 0
    private var lastCandidateSubId = -1
    private var lastSimSwitchMs = 0L

    // Wi-Fi <-> Mobile Data state
    private var wifiEmaScore: Int? = null
    private var wifiLatencyCache: Pair<Long, Int>? = null
    private var consecutivePoorWifiSamples = 0
    private var switchedToMobileByEngine = false
    private var nextWifiProbeEligibleMs = 0L
    private var wifiBackoffStep = 0
    private var isProbingWifiRecovery = false

    // Registered listener handles for cleanup
    private val registeredCallbacksApi31 = mutableMapOf<Int, Pair<TelephonyManager, TelephonyCallback>>()
    @Suppress("DEPRECATION")
    private val registeredListenersLegacy = mutableMapOf<Int, Pair<TelephonyManager, PhoneStateListener>>()

    private val evaluationRunnable = object : Runnable {
        override fun run() {
            if (!SmartNetworkController.isEnabled.value) {
                stopSelf()
                return
            }
            serviceScope.launch {
                evaluateAllNetworks()
            }
            // Adaptive low-power interval: 12s when screen is ON; no active loop when screen is OFF
            if (isScreenOn) {
                mainHandler.postDelayed(this, EVALUATION_INTERVAL_SCREEN_ON_MS)
            }
        }
    }

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    mainHandler.removeCallbacks(evaluationRunnable)
                    updateNotification("Standby (Screen Off • Zero-drain passive mode)")
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    mainHandler.removeCallbacks(evaluationRunnable)
                    mainHandler.post(evaluationRunnable)
                }
                "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED",
                "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED" -> {
                    serviceScope.launch {
                        NetworkModeController.refresh(this@SmartNetworkService)
                        registerPerSimListeners()
                        evaluateAllNetworks()
                    }
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (isScreenOn) {
                serviceScope.launch { evaluateAllNetworks() }
            }
        }

        override fun onLost(network: Network) {
            if (isScreenOn) {
                serviceScope.launch { evaluateAllNetworks() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
            addAction("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED")
        }
        runCatching { registerReceiver(systemEventReceiver, filter) }

        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        runCatching { cm?.registerNetworkCallback(req, networkCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SmartNetworkController.updateActiveStateFromService(false, "Idle • Engine turned off")
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = buildNotification("Initializing Smart Network Engine...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        SmartNetworkController.updateActiveStateFromService(true, "Monitoring RF & Link Quality...")

        serviceScope.launch {
            NetworkModeController.refresh(this@SmartNetworkService)
            registerPerSimListeners()
            evaluateAllNetworks()
        }

        mainHandler.removeCallbacks(evaluationRunnable)
        mainHandler.postDelayed(evaluationRunnable, EVALUATION_INTERVAL_SCREEN_ON_MS)

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(evaluationRunnable)
        runCatching { unregisterReceiver(systemEventReceiver) }
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(networkCallback) }
        unregisterPerSimListeners()
        serviceScope.cancel()
        SmartNetworkController.refreshTile(this)
        super.onDestroy()
    }

    private fun registerPerSimListeners() {
        unregisterPerSimListeners()
        val baseTm = getSystemService(TelephonyManager::class.java) ?: return
        val activeSims = NetworkModeController.sims.value.filter { it.isActive && it.slotIndex >= 0 }

        for (sim in activeSims) {
            val subTm = baseTm.createForSubscriptionId(sim.subId)
            // Seed initial signal strength immediately
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                subTm.signalStrength?.let { latestSignalMap[sim.subId] = it }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerCallbackApi31(sim.subId, subTm)
            } else {
                registerListenerLegacy(sim.subId, subTm)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCallbackApi31(subId: Int, subTm: TelephonyManager) {
        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.CallStateListener {

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                latestSignalMap[subId] = signalStrength
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                val overrideType = telephonyDisplayInfo.overrideNetworkType
                val is5g = telephonyDisplayInfo.networkType == TelephonyManager.NETWORK_TYPE_NR ||
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
                latest5gDisplayMap[subId] = is5g
            }

            override fun onCallStateChanged(state: Int) {
                inVoiceCall = (state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        runCatching {
            subTm.registerTelephonyCallback(mainExecutor, callback)
            registeredCallbacksApi31[subId] = Pair(subTm, callback)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerListenerLegacy(subId: Int, subTm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                if (signalStrength != null) {
                    latestSignalMap[subId] = signalStrength
                }
            }

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                inVoiceCall = (state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        runCatching {
            subTm.listen(
                listener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_CALL_STATE
            )
            registeredListenersLegacy[subId] = Pair(subTm, listener)
        }
    }

    private fun unregisterPerSimListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registeredCallbacksApi31.values.forEach { (tm, cb) ->
                runCatching { tm.unregisterTelephonyCallback(cb) }
            }
            registeredCallbacksApi31.clear()
        }
        @Suppress("DEPRECATION")
        registeredListenersLegacy.values.forEach { (tm, listener) ->
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
        registeredListenersLegacy.clear()
    }

    private suspend fun evaluateAllNetworks() {
        val nowMs = System.currentTimeMillis()
        val preset = SmartNetworkController.sensitivityPreset.value
        val cm = getSystemService(ConnectivityManager::class.java)
        val baseTm = getSystemService(TelephonyManager::class.java)

        val activeNetwork = cm?.activeNetwork
        val activeCaps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val isWifiActive = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isActiveNetworkValidated = activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        // 1. Evaluate Wi-Fi Quality if connected
        val wifiSnap = if (isWifiActive) {
            evaluateWifiSnapshot(activeNetwork, activeCaps, isActiveNetworkValidated, nowMs)
        } else {
            wifiEmaScore = null
            null
        }

        // 2. Evaluate Active Cellular SIMs
        val activeSims = NetworkModeController.sims.value.filter { it.isActive && it.slotIndex >= 0 }
        val simSnapshots = LinkedHashMap<Int, NetworkQualityScorer.SimQualitySnapshot>()

        for (sim in activeSims) {
            val subTm = baseTm?.createForSubscriptionId(sim.subId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && latestSignalMap[sim.subId] == null) {
                subTm?.signalStrength?.let { latestSignalMap[sim.subId] = it }
            }
            val sig = latestSignalMap[sim.subId]
            val is5gDisplay = latest5gDisplayMap[sim.subId]
                ?: (sim.currentMode == NetworkModeController.NetworkMode.MODE_NR_ONLY)
            val rf = NetworkQualityScorer.extractSignalMetrics(sig, is5gDisplay)
            val is5g = rf.isNrSignal || sim.currentMode == NetworkModeController.NetworkMode.MODE_NR_ONLY

            // Only run lightweight HTTP 204 probe on the default data SIM when not on Wi-Fi and RF is borderline
            val prelimScore = NetworkQualityScorer.computeCellularRawScore(
                rsrpDbm = rf.rsrpDbm,
                rsrqDb = rf.rsrqDb,
                sinrDb = rf.sinrDb,
                fallbackLevel = rf.level,
                is5g = is5g,
                isValidated = if (sim.isDefaultData && !isWifiActive) isActiveNetworkValidated else true,
                latencyMs = null
            )

            val latencyMs = if (sim.isDefaultData && !isWifiActive && isScreenOn && (prelimScore in 25..58 || !isActiveNetworkValidated)) {
                val cached = simLatencyCache[sim.subId]
                if (cached != null && nowMs - cached.first < PROBE_CACHE_TTL_MS) {
                    cached.second
                } else {
                    val measured = NetworkQualityScorer.measureHttp204LatencyMs(activeNetwork)
                    simLatencyCache[sim.subId] = Pair(nowMs, measured)
                    measured
                }
            } else {
                simLatencyCache[sim.subId]?.takeIf { nowMs - it.first < PROBE_CACHE_TTL_MS }?.second
            }

            val rawScore = NetworkQualityScorer.computeCellularRawScore(
                rsrpDbm = rf.rsrpDbm,
                rsrqDb = rf.rsrqDb,
                sinrDb = rf.sinrDb,
                fallbackLevel = rf.level,
                is5g = is5g,
                isValidated = if (sim.isDefaultData && !isWifiActive) isActiveNetworkValidated else true,
                latencyMs = latencyMs
            )

            val ema = NetworkQualityScorer.computeEmaScore(simEmaScores[sim.subId], rawScore)
            simEmaScores[sim.subId] = ema

            val ratLabel = when {
                is5g -> "5G NR"
                sim.currentMode == NetworkModeController.NetworkMode.MODE_LTE_ONLY || rf.rsrpDbm != null -> "4G LTE"
                else -> sim.currentMode.shortName
            }

            simSnapshots[sim.subId] = NetworkQualityScorer.SimQualitySnapshot(
                subId = sim.subId,
                slotIndex = sim.slotIndex,
                displayName = sim.displayName,
                isDefaultData = sim.isDefaultData,
                ratLabel = ratLabel,
                is5gActive = is5g,
                rsrpDbm = rf.rsrpDbm,
                rsrqDb = rf.rsrqDb,
                sinrDb = rf.sinrDb,
                signalLevel = rf.level,
                latencyMs = latencyMs,
                rawScore = rawScore,
                emaScore = ema,
                grade = NetworkQualityScorer.scoreToGrade(ema)
            )
        }

        val bestSimSnap = simSnapshots.values.maxByOrNull { it.emaScore }
        val defaultSimSnap = simSnapshots.values.firstOrNull { it.isDefaultData } ?: bestSimSnap

        // 3. Smart Wi-Fi <-> Mobile Data Switching
        if (SmartNetworkController.autoWifiDataEnabled.value && ShizukuHelper.hasPorterPermission() && !isProbingWifiRecovery) {
            if (isWifiActive && wifiSnap != null) {
                switchedToMobileByEngine = false
                if (wifiSnap.emaScore < preset.wifiFailoverThreshold) {
                    consecutivePoorWifiSamples++
                } else {
                    consecutivePoorWifiSamples = 0
                }

                val wifiAction = NetworkQualityScorer.evaluateWifiDataAction(
                    isWifiConnected = true,
                    switchedToMobileByEngine = false,
                    wifiEmaScore = wifiSnap.emaScore,
                    bestSimEmaScore = bestSimSnap?.emaScore ?: 0,
                    consecutivePoorWifiSamples = consecutivePoorWifiSamples,
                    inVoiceCall = inVoiceCall,
                    nowMs = nowMs,
                    nextWifiProbeEligibleMs = nextWifiProbeEligibleMs,
                    preset = preset
                )

                if (wifiAction == NetworkQualityScorer.ActionWifiData.SWITCH_TO_MOBILE_DATA) {
                    consecutivePoorWifiSamples = 0
                    switchedToMobileByEngine = true
                    val backoffMs = WIFI_RECOVERY_BASE_BACKOFF_MS * (1L shl wifiBackoffStep.coerceAtMost(3))
                    nextWifiProbeEligibleMs = nowMs + backoffMs
                    wifiBackoffStep = (wifiBackoffStep + 1).coerceAtMost(3)

                    ShizukuHelper.runCommand("svc data enable")
                    ShizukuHelper.runCommand("cmd wifi set-wifi-enabled disabled")
                    SmartNetworkController.recordAction(
                        "Switched Wi-Fi -> Mobile Data (Wi-Fi score ${wifiSnap.emaScore}/100 vs ${bestSimSnap?.displayName ?: "SIM"} ${bestSimSnap?.emaScore ?: 0}/100)"
                    )
                }
            } else if (!isWifiActive && switchedToMobileByEngine) {
                val wifiAction = NetworkQualityScorer.evaluateWifiDataAction(
                    isWifiConnected = false,
                    switchedToMobileByEngine = true,
                    wifiEmaScore = 0,
                    bestSimEmaScore = bestSimSnap?.emaScore ?: 0,
                    consecutivePoorWifiSamples = 0,
                    inVoiceCall = inVoiceCall,
                    nowMs = nowMs,
                    nextWifiProbeEligibleMs = nextWifiProbeEligibleMs,
                    preset = preset
                )

                if (wifiAction == NetworkQualityScorer.ActionWifiData.PROBE_WIFI_RECOVERY) {
                    probeWifiRecovery()
                }
            }
        }

        // If on healthy Wi-Fi, pause cellular switching to save battery & avoid unnecessary modem resets
        if (isWifiActive && (wifiSnap == null || wifiSnap.emaScore >= preset.wifiStayThreshold)) {
            val status = "Wi-Fi Active (${wifiSnap?.emaScore ?: 90}/100 • ${wifiSnap?.grade ?: "Good"}) • Cellular Standby"
            SmartNetworkController.updateSnapshots(wifiSnap, simSnapshots, status)
            updateNotification(status)
            return
        }

        if (!ShizukuHelper.hasPorterPermission()) {
            val status = "Waiting for Porter / Shizuku permission"
            SmartNetworkController.updateSnapshots(wifiSnap, simSnapshots, status)
            updateNotification(status)
            return
        }

        // 4. Smart 5G <-> 4G Quality Switching on Default Data SIM
        if (SmartNetworkController.auto5g4gEnabled.value && defaultSimSnap != null) {
            val targetSimInfo = activeSims.firstOrNull { it.subId == defaultSimSnap.subId }
            if (targetSimInfo != null) {
                val subId = defaultSimSnap.subId
                val poorCount = if (defaultSimSnap.is5gActive && defaultSimSnap.emaScore < preset.poor5gScoreThreshold) {
                    (consecutivePoor5gMap[subId] ?: 0) + 1
                } else {
                    0
                }
                consecutivePoor5gMap[subId] = poorCount

                val action5g4g = NetworkQualityScorer.evaluate5g4gAction(
                    isCurrently5g = defaultSimSnap.is5gActive,
                    lockedTo4gByEngine = lockedTo4gByEngineMap[subId] == true,
                    emaScore = defaultSimSnap.emaScore,
                    sinrDb = defaultSimSnap.sinrDb,
                    rsrpDbm = defaultSimSnap.rsrpDbm,
                    consecutivePoor5gSamples = poorCount,
                    inVoiceCall = inVoiceCall,
                    nowMs = nowMs,
                    next5gProbeEligibleMs = next5gProbeEligibleMsMap[subId] ?: 0L,
                    preset = preset
                )

                when (action5g4g) {
                    NetworkQualityScorer.Action5g4g.DOWNGRADE_TO_4G -> {
                        consecutivePoor5gMap[subId] = 0
                        lockedTo4gByEngineMap[subId] = true
                        val step = backoffStep5gMap[subId] ?: 0
                        val backoffMs = NR_RECOVERY_BASE_BACKOFF_MS * (1L shl step.coerceAtMost(3))
                        next5gProbeEligibleMsMap[subId] = nowMs + backoffMs
                        backoffStep5gMap[subId] = (step + 1).coerceAtMost(3)

                        NetworkModeController.setModeForSim(
                            this,
                            targetSimInfo,
                            NetworkModeController.NetworkMode.MODE_LTE_ONLY
                        )
                        SmartNetworkController.recordAction(
                            "Locked ${targetSimInfo.displayName} to 4G Only (5G score ${defaultSimSnap.emaScore}/100, SINR ${defaultSimSnap.sinrDb ?: "?"}dB)"
                        )
                    }
                    NetworkQualityScorer.Action5g4g.PROBE_5G_RECOVERY -> {
                        lockedTo4gByEngineMap[subId] = false
                        NetworkModeController.setModeForSim(
                            this,
                            targetSimInfo,
                            NetworkModeController.NetworkMode.MODE_5G_PREF
                        )
                        SmartNetworkController.recordAction(
                            "Restored ${targetSimInfo.displayName} to 5G Auto (4G score ${defaultSimSnap.emaScore}/100 healthy)"
                        )
                    }
                    NetworkQualityScorer.Action5g4g.HOLD -> Unit
                }
            }
        }

        // 5. Smart Dual-SIM Default Data Switching
        if (SmartNetworkController.autoSimSwitchEnabled.value && activeSims.size >= 2 && defaultSimSnap != null) {
            val candidateSnap = simSnapshots.values
                .filter { it.subId != defaultSimSnap.subId }
                .maxByOrNull { it.emaScore }

            if (candidateSnap != null) {
                if (candidateSnap.emaScore >= defaultSimSnap.emaScore + preset.simHysteresisDelta) {
                    if (lastCandidateSubId == candidateSnap.subId) {
                        consecutiveCandidateBetterCount++
                    } else {
                        lastCandidateSubId = candidateSnap.subId
                        consecutiveCandidateBetterCount = 1
                    }
                } else {
                    consecutiveCandidateBetterCount = 0
                }

                val shouldSwitch = NetworkQualityScorer.shouldSwitchDataSim(
                    currentSimScore = defaultSimSnap.emaScore,
                    candidateSimScore = candidateSnap.emaScore,
                    consecutiveCandidateBetterSamples = consecutiveCandidateBetterCount,
                    inVoiceCall = inVoiceCall,
                    nowMs = nowMs,
                    lastSimSwitchMs = lastSimSwitchMs,
                    preset = preset
                )

                if (shouldSwitch) {
                    consecutiveCandidateBetterCount = 0
                    lastSimSwitchMs = nowMs
                    NetworkModeController.setDefaultDataSubId(this, candidateSnap.subId)
                    SmartNetworkController.recordAction(
                        "Switched Data SIM to ${candidateSnap.displayName} (Score ${candidateSnap.emaScore} vs ${defaultSimSnap.displayName} ${defaultSimSnap.emaScore})"
                    )
                }
            }
        }

        // 6. Update UI telemetry & notification
        val statusSummary = if (defaultSimSnap != null) {
            "Active: ${defaultSimSnap.displayName} (${defaultSimSnap.ratLabel} • Score ${defaultSimSnap.emaScore}/100 ${defaultSimSnap.grade})"
        } else {
            "Monitoring active networks..."
        }
        SmartNetworkController.updateSnapshots(wifiSnap, simSnapshots, statusSummary)
        updateNotification(statusSummary)
        SmartNetworkController.refreshTile(this)
    }

    private fun evaluateWifiSnapshot(
        activeNetwork: Network?,
        caps: NetworkCapabilities?,
        isValidated: Boolean,
        nowMs: Long
    ): NetworkQualityScorer.WifiQualitySnapshot {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val transportInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps?.transportInfo as? WifiInfo
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val wifiInfo = transportInfo ?: wm?.connectionInfo
        val rssi = wifiInfo?.rssi?.takeIf { it in -120..-20 } ?: -70
        val linkSpeed = wifiInfo?.linkSpeed?.coerceAtLeast(1) ?: 72

        val prelimScore = NetworkQualityScorer.computeWifiRawScore(rssi, linkSpeed, isValidated, null)
        val latencyMs = if (isScreenOn && (prelimScore < 55 || !isValidated)) {
            val cached = wifiLatencyCache
            if (cached != null && nowMs - cached.first < PROBE_CACHE_TTL_MS) {
                cached.second
            } else {
                val measured = NetworkQualityScorer.measureHttp204LatencyMs(activeNetwork)
                wifiLatencyCache = Pair(nowMs, measured)
                measured
            }
        } else {
            wifiLatencyCache?.takeIf { nowMs - it.first < PROBE_CACHE_TTL_MS }?.second
        }

        val raw = NetworkQualityScorer.computeWifiRawScore(rssi, linkSpeed, isValidated, latencyMs)
        val ema = NetworkQualityScorer.computeEmaScore(wifiEmaScore, raw)
        wifiEmaScore = ema

        return NetworkQualityScorer.WifiQualitySnapshot(
            isConnected = true,
            rssiDbm = rssi,
            linkSpeedMbps = linkSpeed,
            isValidated = isValidated,
            latencyMs = latencyMs,
            rawScore = raw,
            emaScore = ema,
            grade = NetworkQualityScorer.scoreToGrade(ema)
        )
    }

    private fun probeWifiRecovery() {
        isProbingWifiRecovery = true
        serviceScope.launch {
            try {
                ShizukuHelper.runCommand("cmd wifi set-wifi-enabled enabled")
                delay(12_000L)
                val cm = getSystemService(ConnectivityManager::class.java)
                val activeNet = cm?.activeNetwork
                val caps = activeNet?.let { cm.getNetworkCapabilities(it) }
                val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val wifiValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

                if (wifiConnected && wifiValidated) {
                    val snap = evaluateWifiSnapshot(activeNet, caps, true, System.currentTimeMillis())
                    if (snap.emaScore >= 50) {
                        switchedToMobileByEngine = false
                        wifiBackoffStep = 0
                        SmartNetworkController.recordAction(
                            "Restored Wi-Fi connection (Score ${snap.emaScore}/100 • ${snap.rssiDbm} dBm)"
                        )
                        return@launch
                    }
                }
                // Still weak or not available -> turn Wi-Fi back off and wait next backoff
                val nowMs = System.currentTimeMillis()
                val backoffMs = WIFI_RECOVERY_BASE_BACKOFF_MS * (1L shl wifiBackoffStep.coerceAtMost(3))
                nextWifiProbeEligibleMs = nowMs + backoffMs
                wifiBackoffStep = (wifiBackoffStep + 1).coerceAtMost(3)
                ShizukuHelper.runCommand("cmd wifi set-wifi-enabled disabled")
            } finally {
                isProbingWifiRecovery = false
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.smart_net_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPending = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SmartNetworkService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_smart_network)
            .setContentTitle(getString(R.string.smart_net_notif_title))
            .setContentText(content)
            .setContentIntent(launchPending)
            .addAction(0, getString(R.string.smart_net_notif_stop), stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "smart_network_engine_channel"
        const val NOTIFICATION_ID = 2002
        const val ACTION_STOP = "action_stop_smart_network"

        private const val EVALUATION_INTERVAL_SCREEN_ON_MS = 12_000L
        private const val PROBE_CACHE_TTL_MS = 30_000L
        private const val NR_RECOVERY_BASE_BACKOFF_MS = 180_000L   // 3 min -> 6 min -> 12 min -> 24 min
        private const val WIFI_RECOVERY_BASE_BACKOFF_MS = 120_000L // 2 min -> 4 min -> 8 min -> 16 min
    }
}
