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
import android.net.TelephonyNetworkSpecifier
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class SmartNetworkService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScreenOn = true
    private var inVoiceCall = false

    // Per-subId live RF & Carrier Aggregation cache populated passively by TelephonyCallback / PhoneStateListener
    private val latestSignalMap = ConcurrentHashMap<Int, SignalStrength>()
    private val latest5gDisplayMap = ConcurrentHashMap<Int, Boolean>()
    private val latestLteCaOverrideMap = ConcurrentHashMap<Int, Boolean>()
    private val latestNrAdvancedOverrideMap = ConcurrentHashMap<Int, Boolean>()
    private val latestBandwidthsKhzMap = ConcurrentHashMap<Int, IntArray>()
    private val latestServiceStateCaMap = ConcurrentHashMap<Int, Boolean>()
    private val simEmaScores = ConcurrentHashMap<Int, Int>()
    private val simLatencyCache = ConcurrentHashMap<Int, Pair<Long, Int>>() // subId -> (timestampMs, rttMs)
    private val simProbeFailuresMap = ConcurrentHashMap<Int, Int>()         // subId -> consecutive HTTP 204 failures
    private val simQuarantineUntilMsMap = ConcurrentHashMap<Int, Long>()    // subId -> quarantine expiry timestamp

    // 5G/4G state tracking per subId
    private val consecutivePoor5gMap = ConcurrentHashMap<Int, Int>()
    private val lockedTo4gByEngineMap = ConcurrentHashMap<Int, Boolean>()
    private val next5gProbeEligibleMsMap = ConcurrentHashMap<Int, Long>()
    private val backoffStep5gMap = ConcurrentHashMap<Int, Int>()

    // Dual-SIM hysteresis & post-switch verification state
    private var consecutiveCandidateBetterCount = 0
    private var lastCandidateSubId = -1
    private var lastSimSwitchMs = 0L
    private var isVerifyingSimSwitch = false

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                subTm.signalStrength?.let { latestSignalMap[sim.subId] = it }
                runCatching { subTm.serviceState }?.getOrNull()?.let { ss ->
                    updateServiceStateCache(sim.subId, ss)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerCallbackApi31(sim.subId, subTm)
            } else {
                registerListenerLegacy(sim.subId, subTm)
            }
        }
    }

    private fun updateServiceStateCache(subId: Int, serviceState: ServiceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            latestBandwidthsKhzMap[subId] = serviceState.cellBandwidths
        }
        val isCaReflected = runCatching {
            val method = ServiceState::class.java.getDeclaredMethod("isUsingCarrierAggregation")
            method.isAccessible = true
            method.invoke(serviceState) as? Boolean ?: false
        }.getOrDefault(false)
        latestServiceStateCaMap[subId] = isCaReflected
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCallbackApi31(subId: Int, subTm: TelephonyManager) {
        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener,
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
                latestLteCaOverrideMap[subId] = (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA)
                latestNrAdvancedOverrideMap[subId] = (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                updateServiceStateCache(subId, serviceState)
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
            @Deprecated("Deprecated in Java")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                if (signalStrength != null) {
                    latestSignalMap[subId] = signalStrength
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onServiceStateChanged(serviceState: ServiceState?) {
                if (serviceState != null) {
                    updateServiceStateCache(subId, serviceState)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                inVoiceCall = (state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        runCatching {
            subTm.listen(
                listener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                        PhoneStateListener.LISTEN_SERVICE_STATE or
                        PhoneStateListener.LISTEN_CALL_STATE
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
        if (isVerifyingSimSwitch) return
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

        // 2. Evaluate Active Cellular SIMs (RF + Data Pack / HTTP 204 Internet Verification)
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

            // Lightweight HTTP 204 heartbeat on the active Data SIM when on Mobile Data & Screen ON:
            // Detects expired data packs (captive portal 200/302 or blackholed packets) even on 5-bar 5G!
            val latencyMs = if (sim.isDefaultData && !isWifiActive && isScreenOn) {
                val cached = simLatencyCache[sim.subId]
                val cacheTtl = if ((simProbeFailuresMap[sim.subId] ?: 0) > 0 || !isActiveNetworkValidated) {
                    10_000L // Fast re-check if previous probe failed or unvalidated
                } else {
                    PROBE_CACHE_TTL_MS // 36s low-power heartbeat when healthy
                }
                if (cached != null && nowMs - cached.first < cacheTtl) {
                    cached.second
                } else {
                    val measured = probeWithSingleRetry(activeNetwork)
                    simLatencyCache[sim.subId] = Pair(nowMs, measured)
                    if (measured < 0) {
                        val fails = (simProbeFailuresMap[sim.subId] ?: 0) + 1
                        simProbeFailuresMap[sim.subId] = fails
                    } else {
                        simProbeFailuresMap[sim.subId] = 0
                        simQuarantineUntilMsMap.remove(sim.subId)
                    }
                    measured
                }
            } else {
                simLatencyCache[sim.subId]?.takeIf { nowMs - it.first < PROBE_CACHE_TTL_MS }?.second
            }

            val probeFailures = simProbeFailuresMap[sim.subId] ?: 0
            val quarantineUntil = simQuarantineUntilMsMap[sim.subId] ?: 0L
            val isQuarantined = nowMs < quarantineUntil
            val hasInternet = probeFailures < 2 && !isQuarantined

            val caInfo = NetworkQualityScorer.parseCarrierAggregation(
                is5g = is5g,
                isLteCaOverride = latestLteCaOverrideMap[sim.subId] == true,
                isNrAdvancedOverride = latestNrAdvancedOverrideMap[sim.subId] == true,
                cellBandwidthsKhz = latestBandwidthsKhzMap[sim.subId],
                serviceStateCaReflection = latestServiceStateCaMap[sim.subId] == true
            )

            val rawScore = NetworkQualityScorer.computeCellularRawScore(
                rsrpDbm = rf.rsrpDbm,
                rsrqDb = rf.rsrqDb,
                sinrDb = rf.sinrDb,
                fallbackLevel = rf.level,
                is5g = is5g,
                isValidated = if (sim.isDefaultData && !isWifiActive) isActiveNetworkValidated else true,
                latencyMs = latencyMs,
                consecutiveProbeFailures = if (isQuarantined) 2 else probeFailures,
                isCarrierAggregated = caInfo.isCarrierAggregated,
                totalBandwidthMhz = caInfo.totalBandwidthMhz
            )

            // If internet is dead (data pack expired), bypass slow EMA decay and drop score immediately
            val ema = if (!hasInternet) {
                rawScore
            } else {
                NetworkQualityScorer.computeEmaScore(simEmaScores[sim.subId], rawScore)
            }
            simEmaScores[sim.subId] = ema

            val ratLabel = if (is5g || sim.currentMode == NetworkModeController.NetworkMode.MODE_LTE_ONLY || rf.rsrpDbm != null) {
                caInfo.ratDisplayLabel
            } else {
                sim.currentMode.shortName
            }

            simSnapshots[sim.subId] = NetworkQualityScorer.SimQualitySnapshot(
                subId = sim.subId,
                slotIndex = sim.slotIndex,
                displayName = sim.displayName,
                isDefaultData = sim.isDefaultData,
                ratLabel = ratLabel,
                is5gActive = is5g,
                isCarrierAggregated = caInfo.isCarrierAggregated,
                componentCarrierCount = caInfo.componentCarrierCount,
                totalBandwidthMhz = caInfo.totalBandwidthMhz,
                rsrpDbm = rf.rsrpDbm,
                rsrqDb = rf.rsrqDb,
                sinrDb = rf.sinrDb,
                signalLevel = rf.level,
                latencyMs = latencyMs,
                hasInternet = hasInternet,
                isQuarantined = isQuarantined,
                rawScore = rawScore,
                emaScore = ema,
                grade = NetworkQualityScorer.scoreToGrade(ema, hasInternet = hasInternet, isQuarantined = isQuarantined)
            )
        }

        val bestSimSnap = simSnapshots.values
            .filter { !it.isQuarantined }
            .maxByOrNull { it.emaScore } ?: simSnapshots.values.maxByOrNull { it.emaScore }
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
        // Note: Only downgrade 5G->4G if the issue is RF quality, not an expired data pack on clean 5G
        if (SmartNetworkController.auto5g4gEnabled.value && defaultSimSnap != null && defaultSimSnap.hasInternet) {
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

        // 5. Smart Dual-SIM Default Data Switching (with Pre-Switch Probe + Post-Switch Verification & Quarantine)
        if (SmartNetworkController.autoSimSwitchEnabled.value && activeSims.size >= 2 && defaultSimSnap != null) {
            val candidateSnap = simSnapshots.values
                .filter { it.subId != defaultSimSnap.subId }
                .maxByOrNull { it.emaScore }

            if (candidateSnap != null) {
                val candidateQuarantineUntil = simQuarantineUntilMsMap[candidateSnap.subId] ?: 0L

                // If current SIM has NO internet (expired data pack), fast-track switch without waiting 3 ticks!
                if (!defaultSimSnap.hasInternet && nowMs >= candidateQuarantineUntil) {
                    consecutiveCandidateBetterCount = preset.simSamplesRequired
                } else if (candidateSnap.emaScore >= defaultSimSnap.emaScore + preset.simHysteresisDelta) {
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
                    preset = preset,
                    candidateQuarantineUntilMs = candidateQuarantineUntil
                )

                if (shouldSwitch) {
                    consecutiveCandidateBetterCount = 0
                    performVerifiedSimSwitch(defaultSimSnap, candidateSnap, nowMs)
                    return
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

    /**
     * Switches default mobile data to [candidateSnap], attempting a pre-switch network probe first (on DSDA devices),
     * followed by an immediate post-switch HTTP 204 verification and automatic rollback if the candidate SIM's
     * data pack is expired!
     */
    private suspend fun performVerifiedSimSwitch(
        previousSimSnap: NetworkQualityScorer.SimQualitySnapshot,
        candidateSnap: NetworkQualityScorer.SimQualitySnapshot,
        nowMs: Long
    ) {
        isVerifyingSimSwitch = true
        try {
            // Step A: Try pre-switch probe via TelephonyNetworkSpecifier (works on DSDA devices without switching default SIM)
            val preProbeResult = tryPreSwitchProbeOnCandidate(candidateSnap.subId)
            if (preProbeResult == PreProbeStatus.CONFIRMED_NO_INTERNET) {
                simQuarantineUntilMsMap[candidateSnap.subId] = nowMs + SIM_NO_DATA_QUARANTINE_MS
                simProbeFailuresMap[candidateSnap.subId] = 2
                SmartNetworkController.recordAction(
                    "Blocked switch to ${candidateSnap.displayName} (Pre-switch ping failed: data pack expired • Quarantined 20m)"
                )
                return
            }

            // Step B: Perform the switch to candidate SIM
            lastSimSwitchMs = nowMs
            NetworkModeController.setDefaultDataSubId(this, candidateSnap.subId)
            SmartNetworkController.recordAction(
                "Switching Data to ${candidateSnap.displayName} • Verifying internet..."
            )

            // Wait up to 6s for candidate SIM's PDN data bearer to attach
            delay(6_000L)

            val cm = getSystemService(ConnectivityManager::class.java)
            val activeNet = cm?.activeNetwork
            val rtt = probeWithSingleRetry(activeNet)
            val newSimHasInternet = (rtt > 0)

            val verdict = NetworkQualityScorer.evaluatePostSwitchVerification(
                previousSimHadInternet = previousSimSnap.hasInternet,
                newSimHasInternet = newSimHasInternet
            )

            val verifyTimeMs = System.currentTimeMillis()
            when (verdict) {
                NetworkQualityScorer.PostSwitchVerdict.ROLLBACK_AND_QUARANTINE_NEW_SIM -> {
                    // Candidate SIM's data pack is expired! Roll back to previous SIM immediately and quarantine candidate
                    simQuarantineUntilMsMap[candidateSnap.subId] = verifyTimeMs + SIM_NO_DATA_QUARANTINE_MS
                    simProbeFailuresMap[candidateSnap.subId] = 2
                    NetworkModeController.setDefaultDataSubId(this, previousSimSnap.subId)
                    SmartNetworkController.recordAction(
                        "Rolled back to ${previousSimSnap.displayName} (${candidateSnap.displayName} has no internet / pack expired • Quarantined 20m)"
                    )
                }
                NetworkQualityScorer.PostSwitchVerdict.CONFIRM_AND_QUARANTINE_PREVIOUS_SIM -> {
                    // Previous SIM had expired data pack and new SIM has working internet -> quarantine previous SIM!
                    simQuarantineUntilMsMap[previousSimSnap.subId] = verifyTimeMs + SIM_NO_DATA_QUARANTINE_MS
                    simProbeFailuresMap[candidateSnap.subId] = 0
                    simLatencyCache[candidateSnap.subId] = Pair(verifyTimeMs, rtt)
                    SmartNetworkController.recordAction(
                        "Switched to ${candidateSnap.displayName} (${rtt}ms ping OK • Quarantined expired ${previousSimSnap.displayName} for 20m)"
                    )
                }
                NetworkQualityScorer.PostSwitchVerdict.QUARANTINE_BOTH_AND_HOLD -> {
                    simQuarantineUntilMsMap[previousSimSnap.subId] = verifyTimeMs + SIM_NO_DATA_QUARANTINE_MS
                    simQuarantineUntilMsMap[candidateSnap.subId] = verifyTimeMs + SIM_NO_DATA_QUARANTINE_MS
                    SmartNetworkController.recordAction(
                        "Warning: Both ${previousSimSnap.displayName} and ${candidateSnap.displayName} failed HTTP 204 internet check"
                    )
                }
                NetworkQualityScorer.PostSwitchVerdict.CONFIRM_SWITCH -> {
                    simProbeFailuresMap[candidateSnap.subId] = 0
                    simLatencyCache[candidateSnap.subId] = Pair(verifyTimeMs, rtt)
                    SmartNetworkController.recordAction(
                        "Switched Data SIM to ${candidateSnap.displayName} (Verified ${rtt}ms ping • Score ${candidateSnap.emaScore} vs ${previousSimSnap.emaScore})"
                    )
                }
            }
        } finally {
            isVerifyingSimSwitch = false
        }
    }

    private enum class PreProbeStatus {
        CONFIRMED_HAS_INTERNET,
        CONFIRMED_NO_INTERNET,
        UNSUPPORTED_ON_DSDS
    }

    /**
     * Attempts to request a temporary cellular [Network] bound to [candidateSubId] before switching.
     * On DSDA devices where the secondary SIM can open a data bearer simultaneously, this verifies
     * internet before switching; on single-radio DSDS devices where the request times out, returns
     * [PreProbeStatus.UNSUPPORTED_ON_DSDS] so post-switch verification handles it.
     */
    private suspend fun tryPreSwitchProbeOnCandidate(candidateSubId: Int): PreProbeStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PreProbeStatus.UNSUPPORTED_ON_DSDS
        val cm = getSystemService(ConnectivityManager::class.java) ?: return PreProbeStatus.UNSUPPORTED_ON_DSDS

        val deferredNet = CompletableDeferred<Network?>()
        val specifier = TelephonyNetworkSpecifier.Builder()
            .setSubscriptionId(candidateSubId)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                deferredNet.complete(network)
            }

            override fun onUnavailable() {
                deferredNet.complete(null)
            }
        }

        return try {
            cm.requestNetwork(request, cb, mainHandler, 2200)
            val candidateNet = withTimeoutOrNull(2400L) { deferredNet.await() }
                ?: return PreProbeStatus.UNSUPPORTED_ON_DSDS

            val rtt = NetworkQualityScorer.measureHttp204LatencyMs(candidateNet, timeoutMs = 1500)
            if (rtt > 0) PreProbeStatus.CONFIRMED_HAS_INTERNET else PreProbeStatus.CONFIRMED_NO_INTERNET
        } catch (_: Exception) {
            PreProbeStatus.UNSUPPORTED_ON_DSDS
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    private suspend fun probeWithSingleRetry(network: Network?): Int {
        val first = NetworkQualityScorer.measureHttp204LatencyMs(network, timeoutMs = 1500)
        if (first > 0) return first
        delay(1200L)
        return NetworkQualityScorer.measureHttp204LatencyMs(network, timeoutMs = 1800)
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
        private const val PROBE_CACHE_TTL_MS = 36_000L
        private const val NR_RECOVERY_BASE_BACKOFF_MS = 180_000L   // 3 min -> 6 min -> 12 min -> 24 min
        private const val WIFI_RECOVERY_BASE_BACKOFF_MS = 120_000L // 2 min -> 4 min -> 8 min -> 16 min
        private const val SIM_NO_DATA_QUARANTINE_MS = 1_200_000L   // 20 min quarantine for SIM with expired data pack
    }
}
