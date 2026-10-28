package eu.zax4r0.essentialsx.features.smartnet

import android.net.Network
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object NetworkQualityScorer {

    private const val EMA_ALPHA = 0.35

    enum class Action5g4g {
        HOLD,
        DOWNGRADE_TO_4G,
        PROBE_5G_RECOVERY
    }

    enum class ActionWifiData {
        HOLD,
        SWITCH_TO_MOBILE_DATA,
        PROBE_WIFI_RECOVERY
    }

    enum class PostSwitchVerdict {
        CONFIRM_SWITCH,
        CONFIRM_AND_QUARANTINE_PREVIOUS_SIM,
        ROLLBACK_AND_QUARANTINE_NEW_SIM,
        QUARANTINE_BOTH_AND_HOLD
    }

    data class CarrierAggregationInfo(
        val isCarrierAggregated: Boolean,
        val componentCarrierCount: Int,
        val totalBandwidthMhz: Int?,
        val ratDisplayLabel: String
    )

    data class SimQualitySnapshot(
        val subId: Int,
        val slotIndex: Int,
        val displayName: String,
        val isDefaultData: Boolean,
        val ratLabel: String,          // e.g. "5G+ (2CC • 120MHz)", "4G+ (3CC • 50MHz)", "5G NR", "4G LTE"
        val is5gActive: Boolean,
        val isCarrierAggregated: Boolean = false,
        val componentCarrierCount: Int = 1,
        val totalBandwidthMhz: Int? = null,
        val rsrpDbm: Int?,             // e.g. -88 dBm
        val rsrqDb: Int?,              // e.g. -9 dB
        val sinrDb: Int?,              // e.g. +14 dB
        val signalLevel: Int,          // 0..4
        val latencyMs: Int?,           // null if not probed, -1 if timeout/captive portal
        val hasInternet: Boolean = true,
        val isQuarantined: Boolean = false,
        val rawScore: Int,             // 0..100
        val emaScore: Int,             // 0..100 smoothed
        val grade: String              // "Excellent", "Good", "Fair", "Congested", "Poor", "No Internet"
    )

    data class WifiQualitySnapshot(
        val isConnected: Boolean,
        val rssiDbm: Int,              // e.g. -62 dBm
        val linkSpeedMbps: Int,        // e.g. 433 Mbps
        val isValidated: Boolean,
        val latencyMs: Int?,
        val rawScore: Int,
        val emaScore: Int,
        val grade: String
    )

    data class ExtractedRfMetrics(
        val rsrpDbm: Int?,
        val rsrqDb: Int?,
        val sinrDb: Int?,
        val level: Int,
        val isNrSignal: Boolean
    )

    /**
     * Strictly verifies that the HTTP probe returned genuine 204 No Content.
     * Carrier recharge captive portals on expired data packs return 200 OK (HTML) or 302/307 Redirect.
     */
    fun isGenuineHttp204Response(responseCode: Int): Boolean {
        return responseCode == HttpURLConnection.HTTP_NO_CONTENT
    }

    /**
     * Parses Carrier Aggregation (CA) status, Component Carrier count (1CC..5CC), and total aggregated
     * bandwidth in MHz from Android's [TelephonyDisplayInfo] override types and [android.telephony.ServiceState.getCellBandwidths].
     */
    fun parseCarrierAggregation(
        is5g: Boolean,
        isLteCaOverride: Boolean,
        isNrAdvancedOverride: Boolean,
        cellBandwidthsKhz: IntArray?,
        serviceStateCaReflection: Boolean = false
    ): CarrierAggregationInfo {
        val validBwKhz = cellBandwidthsKhz?.filter { it > 0 }.orEmpty()
        val bwCount = validBwKhz.size
        val totalMhz = if (validBwKhz.isNotEmpty()) {
            (validBwKhz.sum() / 1000.0).roundToInt().coerceAtLeast(1)
        } else {
            null
        }

        val isCa = isLteCaOverride || isNrAdvancedOverride || serviceStateCaReflection || bwCount >= 2
        val ccCount = when {
            bwCount >= 2 -> bwCount
            isCa -> 2
            else -> 1
        }

        val label = if (is5g) {
            when {
                isCa && ccCount >= 2 && totalMhz != null -> "5G+ (${ccCount}CC • ${totalMhz}MHz)"
                isCa && totalMhz != null -> "5G+ (${totalMhz}MHz)"
                isCa -> "5G+ NR-CA"
                totalMhz != null -> "5G NR (${totalMhz}MHz)"
                else -> "5G NR"
            }
        } else {
            when {
                isCa && ccCount >= 2 && totalMhz != null -> "4G+ (${ccCount}CC • ${totalMhz}MHz)"
                isCa && totalMhz != null -> "4G+ (${totalMhz}MHz)"
                isCa -> "4G+ LTE-CA"
                totalMhz != null -> "4G LTE (${totalMhz}MHz)"
                else -> "4G LTE"
            }
        }

        return CarrierAggregationInfo(
            isCarrierAggregated = isCa,
            componentCarrierCount = ccCount,
            totalBandwidthMhz = totalMhz,
            ratDisplayLabel = label
        )
    }

    /**
     * Computes the composite RF & Link Health score [0..100] for a cellular connection.
     *
     * Weights:
     * - SINR (45%): [-5 dB -> 0, +20 dB -> 100]
     * - RSRQ (35%): [-19 dB -> 0, -6 dB -> 100]
     * - RSRP (20%): [-120 dBm -> 0, -75 dBm -> 100]
     *
     * Also incorporates Carrier Aggregation (4G+ / 5G+) and aggregated channel bandwidth (MHz).
     * If [consecutiveProbeFailures] >= 2, hard-caps score <= 12 so a 5-bar SIM with an expired
     * data pack immediately triggers failover to the other SIM.
     */
    fun computeCellularRawScore(
        rsrpDbm: Int?,
        rsrqDb: Int?,
        sinrDb: Int?,
        fallbackLevel: Int,
        is5g: Boolean,
        isValidated: Boolean,
        latencyMs: Int?,
        consecutiveProbeFailures: Int = 0,
        isCarrierAggregated: Boolean = false,
        totalBandwidthMhz: Int? = null
    ): Int {
        // Hard-cap if data pack is expired / internet probe failed consecutively
        if (consecutiveProbeFailures >= 2) {
            return 12
        }

        val rsrpNorm = rsrpDbm?.let { normalizeLinear(it.toDouble(), -120.0, -75.0) }
            ?: normalizeLinear(fallbackLevel.toDouble(), 0.0, 4.0)

        val rsrqNorm = rsrqDb?.let { normalizeLinear(it.toDouble(), -19.0, -6.0) }
            ?: rsrpNorm

        val sinrNorm = sinrDb?.let { normalizeLinear(it.toDouble(), -5.0, 20.0) }
            ?: ((rsrpNorm * 0.6) + (rsrqNorm * 0.4))

        var composite = (0.45 * sinrNorm) + (0.35 * rsrqNorm) + (0.20 * rsrpNorm)

        // Small capacity bonus for clean 5G NR when SINR & RSRQ are genuinely healthy
        if (is5g && (sinrDb == null || sinrDb >= 6) && (rsrqDb == null || rsrqDb >= -12)) {
            composite += 4.0
        }

        // Carrier Aggregation (4G+ / 5G+) & Total Channel Bandwidth (Shannon capacity) modifier
        if (sinrDb == null || sinrDb >= 2) {
            if (isCarrierAggregated) {
                composite += when {
                    totalBandwidthMhz != null && totalBandwidthMhz >= 50 -> 8.0
                    totalBandwidthMhz != null && totalBandwidthMhz >= 30 -> 6.0
                    else -> 5.0
                }
            } else if (totalBandwidthMhz != null) {
                when {
                    totalBandwidthMhz <= 5 -> composite -= 4.0   // Narrow 5MHz channel saturates easily
                    totalBandwidthMhz >= 40 -> composite += 4.0  // Wideband single carrier (e.g. n78 40-100MHz)
                }
            }
        }

        // Link validation & micro-probe RTT modifier
        if (!isValidated) {
            composite -= 20.0
        }
        if (latencyMs != null) {
            when {
                latencyMs < 0 -> composite -= 22.0       // Probe timeout / captive portal redirect
                latencyMs <= 75 -> composite += 6.0      // Snappy low-latency bearer
                latencyMs in 450..800 -> composite -= 16.0
                latencyMs > 800 -> composite -= 25.0
            }
        }

        return composite.roundToInt().coerceIn(0, 100)
    }

    /**
     * Computes the composite Wi-Fi quality score [0..100].
     */
    fun computeWifiRawScore(
        rssiDbm: Int,
        linkSpeedMbps: Int,
        isValidated: Boolean,
        latencyMs: Int?
    ): Int {
        val rssiNorm = normalizeLinear(rssiDbm.toDouble(), -90.0, -55.0)
        val speedNorm = normalizeLinear(linkSpeedMbps.toDouble(), 1.0, 150.0)

        val linkHealthNorm = when {
            !isValidated && (latencyMs == null || latencyMs < 0) -> 0.0
            !isValidated -> 20.0
            latencyMs == null -> 85.0
            latencyMs < 0 -> 5.0
            latencyMs <= 60 -> 100.0
            latencyMs <= 180 -> 80.0
            latencyMs <= 450 -> 45.0
            else -> 10.0
        }

        var composite = (0.45 * rssiNorm) + (0.25 * speedNorm) + (0.30 * linkHealthNorm)

        // Dead backhaul cap: if Wi-Fi has no internet validation and probe failed, cap below failover threshold
        if (!isValidated && latencyMs != null && latencyMs < 0) {
            composite = composite.coerceAtMost(32.0)
        }

        return composite.roundToInt().coerceIn(0, 100)
    }

    fun computeEmaScore(
        previousEma: Int?,
        currentRaw: Int,
        alpha: Double = EMA_ALPHA
    ): Int {
        if (previousEma == null) return currentRaw.coerceIn(0, 100)
        val smoothed = (alpha * currentRaw) + ((1.0 - alpha) * previousEma)
        return smoothed.roundToInt().coerceIn(0, 100)
    }

    fun scoreToGrade(score: Int, hasInternet: Boolean = true, isQuarantined: Boolean = false): String = when {
        !hasInternet -> "No Internet"
        isQuarantined -> "Pack Expired"
        score >= 80 -> "Excellent"
        score >= 62 -> "Good"
        score >= 42 -> "Fair"
        score >= 25 -> "Congested"
        else -> "Poor"
    }

    /**
     * Pure decision function for Smart 5G <-> 4G switching.
     */
    fun evaluate5g4gAction(
        isCurrently5g: Boolean,
        lockedTo4gByEngine: Boolean,
        emaScore: Int,
        sinrDb: Int?,
        rsrpDbm: Int?,
        consecutivePoor5gSamples: Int,
        inVoiceCall: Boolean,
        nowMs: Long,
        next5gProbeEligibleMs: Long,
        preset: SmartNetworkController.SensitivityPreset
    ): Action5g4g {
        if (inVoiceCall) return Action5g4g.HOLD

        if (isCurrently5g) {
            val isSeverelyDegraded = emaScore < preset.poor5gScoreThreshold ||
                    (sinrDb != null && sinrDb <= -3 && emaScore < 48) ||
                    (rsrpDbm != null && rsrpDbm <= -116 && emaScore < 45)

            if (isSeverelyDegraded && consecutivePoor5gSamples >= preset.poor5gSamplesRequired) {
                return Action5g4g.DOWNGRADE_TO_4G
            }
            return Action5g4g.HOLD
        }

        if (lockedTo4gByEngine && nowMs >= next5gProbeEligibleMs && emaScore >= 60) {
            return Action5g4g.PROBE_5G_RECOVERY
        }

        return Action5g4g.HOLD
    }

    /**
     * Pure decision function for Smart Dual-SIM default data switching with hysteresis,
     * debounce, call-safety, cooldown, and expired-data-pack quarantine check.
     */
    fun shouldSwitchDataSim(
        currentSimScore: Int,
        candidateSimScore: Int,
        consecutiveCandidateBetterSamples: Int,
        inVoiceCall: Boolean,
        nowMs: Long,
        lastSimSwitchMs: Long,
        preset: SmartNetworkController.SensitivityPreset,
        candidateQuarantineUntilMs: Long = 0L
    ): Boolean {
        if (inVoiceCall) return false
        if (nowMs < candidateQuarantineUntilMs) return false
        if (nowMs - lastSimSwitchMs < preset.simCooldownMs) return false
        if (currentSimScore >= preset.simLowThreshold) return false
        if (candidateSimScore < currentSimScore + preset.simHysteresisDelta) return false
        if (consecutiveCandidateBetterSamples < preset.simSamplesRequired) return false
        return true
    }

    /**
     * Evaluates the outcome after switching default mobile data from one SIM to another
     * and running an immediate HTTP 204 internet check on the newly active SIM.
     */
    fun evaluatePostSwitchVerification(
        previousSimHadInternet: Boolean,
        newSimHasInternet: Boolean
    ): PostSwitchVerdict {
        return when {
            previousSimHadInternet && !newSimHasInternet ->
                PostSwitchVerdict.ROLLBACK_AND_QUARANTINE_NEW_SIM
            !previousSimHadInternet && newSimHasInternet ->
                PostSwitchVerdict.CONFIRM_AND_QUARANTINE_PREVIOUS_SIM
            !previousSimHadInternet && !newSimHasInternet ->
                PostSwitchVerdict.QUARANTINE_BOTH_AND_HOLD
            else ->
                PostSwitchVerdict.CONFIRM_SWITCH
        }
    }

    /**
     * Pure decision function for Smart Wi-Fi <-> Mobile Data switching.
     */
    fun evaluateWifiDataAction(
        isWifiConnected: Boolean,
        switchedToMobileByEngine: Boolean,
        wifiEmaScore: Int,
        bestSimEmaScore: Int,
        consecutivePoorWifiSamples: Int,
        inVoiceCall: Boolean,
        nowMs: Long,
        nextWifiProbeEligibleMs: Long,
        preset: SmartNetworkController.SensitivityPreset
    ): ActionWifiData {
        if (inVoiceCall) return ActionWifiData.HOLD

        if (isWifiConnected) {
            if (wifiEmaScore >= preset.wifiStayThreshold) {
                return ActionWifiData.HOLD
            }
            val wifiDegraded = wifiEmaScore < preset.wifiFailoverThreshold
            val mobileSignificantlyBetter = bestSimEmaScore >= (wifiEmaScore + 15) && bestSimEmaScore >= 45
            if (wifiDegraded && mobileSignificantlyBetter && consecutivePoorWifiSamples >= preset.wifiPoorSamplesRequired) {
                return ActionWifiData.SWITCH_TO_MOBILE_DATA
            }
            return ActionWifiData.HOLD
        }

        if (switchedToMobileByEngine && nowMs >= nextWifiProbeEligibleMs) {
            return ActionWifiData.PROBE_WIFI_RECOVERY
        }

        return ActionWifiData.HOLD
    }

    /**
     * Extracts NR (5G) or LTE (4G) RF metrics from Android's [SignalStrength] without requiring location permission.
     */
    fun extractSignalMetrics(signalStrength: SignalStrength?, is5gDisplay: Boolean): ExtractedRfMetrics {
        if (signalStrength == null) {
            return ExtractedRfMetrics(null, null, null, 0, is5gDisplay)
        }

        val overallLevel = signalStrength.level.coerceIn(0, 4)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val nrList = signalStrength.getCellSignalStrengths(CellSignalStrengthNr::class.java)
            val nr = nrList.firstOrNull()
            if (nr != null) {
                val rsrp = validMetricOrNull(nr.ssRsrp, -140, -40) ?: validMetricOrNull(nr.csiRsrp, -140, -40)
                val rsrq = validMetricOrNull(nr.ssRsrq, -30, 0) ?: validMetricOrNull(nr.csiRsrq, -30, 0)
                val sinr = validMetricOrNull(nr.ssSinr, -25, 45) ?: validMetricOrNull(nr.csiSinr, -25, 45)
                if (rsrp != null || rsrq != null || sinr != null) {
                    return ExtractedRfMetrics(
                        rsrpDbm = rsrp,
                        rsrqDb = rsrq,
                        sinrDb = sinr,
                        level = nr.level.coerceIn(0, 4),
                        isNrSignal = true
                    )
                }
            }

            val lteList = signalStrength.getCellSignalStrengths(CellSignalStrengthLte::class.java)
            val lte = lteList.firstOrNull()
            if (lte != null) {
                val rsrp = validMetricOrNull(lte.rsrp, -140, -40) ?: validMetricOrNull(lte.dbm, -140, -40)
                val rsrq = validMetricOrNull(lte.rsrq, -30, 0)
                val sinr = validMetricOrNull(lte.rssnr, -25, 40)
                return ExtractedRfMetrics(
                    rsrpDbm = rsrp,
                    rsrqDb = rsrq,
                    sinrDb = sinr,
                    level = lte.level.coerceIn(0, 4),
                    isNrSignal = is5gDisplay
                )
            }

            val wcdma = signalStrength.getCellSignalStrengths(CellSignalStrengthWcdma::class.java).firstOrNull()
            if (wcdma != null) {
                val dbm = validMetricOrNull(wcdma.dbm, -125, -45)
                return ExtractedRfMetrics(dbm, null, null, wcdma.level.coerceIn(0, 4), false)
            }

            val gsm = signalStrength.getCellSignalStrengths(CellSignalStrengthGsm::class.java).firstOrNull()
            if (gsm != null) {
                val dbm = validMetricOrNull(gsm.dbm, -125, -45)
                return ExtractedRfMetrics(dbm, null, null, gsm.level.coerceIn(0, 4), false)
            }
        }

        return ExtractedRfMetrics(null, null, null, overallLevel, is5gDisplay)
    }

    /**
     * Lightweight HTTP 204 micro-probe (< 200 bytes) over a specific [Network] to detect dead backhaul,
     * expired data packs (captive portal 200/302 redirects), or high RTT.
     * Returns RTT in milliseconds only when genuine HTTP 204 No Content is received, or -1 otherwise.
     */
    fun measureHttp204LatencyMs(network: Network?, timeoutMs: Int = 1500): Int {
        return try {
            val url = URL("https://connectivitycheck.gstatic.com/generate_204")
            val start = System.nanoTime()
            val conn = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                requestMethod = "HEAD"
            }
            try {
                val code = conn.responseCode
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
                if (isGenuineHttp204Response(code)) elapsedMs.coerceAtLeast(1) else -1
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun validMetricOrNull(value: Int, minValid: Int, maxValid: Int): Int? {
        if (value == CellInfo.UNAVAILABLE || value == Int.MAX_VALUE || value == Int.MIN_VALUE) return null
        return if (value in minValid..maxValid) value else null
    }

    private fun normalizeLinear(value: Double, min: Double, max: Double): Double {
        if (max <= min) return 50.0
        val clamped = value.coerceIn(min, max)
        return ((clamped - min) / (max - min)) * 100.0
    }
}
