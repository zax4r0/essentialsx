package eu.zax4r0.essentialsx.features.smartnet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkQualityScorerTest {

    @Test
    fun `congested 5G with strong RSRP bars scores much lower than clean moderate-RSRP 4G`() {
        // Android's stock mistake: RSRP is strong (-80 dBm, 4 bars), but tower is heavily congested
        // with terrible SINR (-4 dB) and RSRQ (-18 dB).
        val fakeStrong5gScore = NetworkQualityScorer.computeCellularRawScore(
            rsrpDbm = -80,
            rsrqDb = -18,
            sinrDb = -4,
            fallbackLevel = 4,
            is5g = true,
            isValidated = true,
            latencyMs = null
        )

        // Clean 4G LTE: moderate RSRP (-96 dBm, 2-3 bars), clean RSRQ (-8 dB), strong SINR (+15 dB)
        val cleanModerate4gScore = NetworkQualityScorer.computeCellularRawScore(
            rsrpDbm = -96,
            rsrqDb = -8,
            sinrDb = 15,
            fallbackLevel = 3,
            is5g = false,
            isValidated = true,
            latencyMs = null
        )

        assertTrue(
            "Expected congested 5G ($fakeStrong5gScore) to score < 35",
            fakeStrong5gScore < 35
        )
        assertTrue(
            "Expected clean 4G ($cleanModerate4gScore) to score > 75",
            cleanModerate4gScore > 75
        )
        assertTrue(cleanModerate4gScore > fakeStrong5gScore + 40)
    }

    @Test
    fun `dead backhaul or high latency penalizes cellular score`() {
        val baseGoodScore = NetworkQualityScorer.computeCellularRawScore(
            rsrpDbm = -88,
            rsrqDb = -9,
            sinrDb = 12,
            fallbackLevel = 4,
            is5g = true,
            isValidated = true,
            latencyMs = 42
        )

        val highLatencyScore = NetworkQualityScorer.computeCellularRawScore(
            rsrpDbm = -88,
            rsrqDb = -9,
            sinrDb = 12,
            fallbackLevel = 4,
            is5g = true,
            isValidated = true,
            latencyMs = 650
        )

        val unvalidatedZombieScore = NetworkQualityScorer.computeCellularRawScore(
            rsrpDbm = -88,
            rsrqDb = -9,
            sinrDb = 12,
            fallbackLevel = 4,
            is5g = true,
            isValidated = false,
            latencyMs = -1 // probe timeout
        )

        assertTrue("Fast ping should keep score high ($baseGoodScore)", baseGoodScore >= 75)
        assertTrue("High ping should reduce score ($highLatencyScore < $baseGoodScore)", highLatencyScore <= baseGoodScore - 15)
        assertTrue("Unvalidated + timeout should heavily penalize score ($unvalidatedZombieScore)", unvalidatedZombieScore <= baseGoodScore - 30)
    }

    @Test
    fun `EMA smoothing dampens transient single-sample drops`() {
        val prevEma = 80
        val suddenDropRaw = 20
        val nextEma = NetworkQualityScorer.computeEmaScore(previousEma = prevEma, currentRaw = suddenDropRaw)

        // With alpha = 0.35: 0.35 * 20 + 0.65 * 80 = 7 + 52 = 59
        assertEquals(59, nextEma)
    }

    @Test
    fun `wifi quality score prefers healthy Wi-Fi and flags sticky weak or dead Wi-Fi`() {
        val healthyWifi = NetworkQualityScorer.computeWifiRawScore(
            rssiDbm = -58,
            linkSpeedMbps = 433,
            isValidated = true,
            latencyMs = 22
        )

        val stickyWeakWifi = NetworkQualityScorer.computeWifiRawScore(
            rssiDbm = -84,
            linkSpeedMbps = 6,
            isValidated = true,
            latencyMs = 720
        )

        val deadBackhaulWifi = NetworkQualityScorer.computeWifiRawScore(
            rssiDbm = -62,
            linkSpeedMbps = 300,
            isValidated = false,
            latencyMs = -1 // timed out
        )

        assertTrue("Healthy Wi-Fi ($healthyWifi) should score >= 85", healthyWifi >= 85)
        assertTrue("Sticky weak Wi-Fi ($stickyWeakWifi) should score < 35", stickyWeakWifi < 35)
        assertTrue("Dead backhaul Wi-Fi ($deadBackhaulWifi) should score < 38", deadBackhaulWifi < 38)
    }

    @Test
    fun `5G to 4G decision downgrades on sustained poor 5G and respects voice call guard`() {
        val preset = SmartNetworkController.SensitivityPreset.BALANCED

        // Not enough consecutive poor samples yet (1 < 3) -> HOLD
        val holdAction = NetworkQualityScorer.evaluate5g4gAction(
            isCurrently5g = true,
            lockedTo4gByEngine = false,
            emaScore = 30,
            sinrDb = -3,
            rsrpDbm = -112,
            consecutivePoor5gSamples = 1,
            inVoiceCall = false,
            nowMs = 100_000L,
            next5gProbeEligibleMs = 0L,
            preset = preset
        )
        assertEquals(NetworkQualityScorer.Action5g4g.HOLD, holdAction)

        // Sustained poor 5G (3 samples), not in call -> DOWNGRADE_TO_4G
        val downgradeAction = NetworkQualityScorer.evaluate5g4gAction(
            isCurrently5g = true,
            lockedTo4gByEngine = false,
            emaScore = 30,
            sinrDb = -3,
            rsrpDbm = -112,
            consecutivePoor5gSamples = 3,
            inVoiceCall = false,
            nowMs = 100_000L,
            next5gProbeEligibleMs = 0L,
            preset = preset
        )
        assertEquals(NetworkQualityScorer.Action5g4g.DOWNGRADE_TO_4G, downgradeAction)

        // Same conditions but in an active voice call -> HOLD (never drop call!)
        val blockedByCallAction = NetworkQualityScorer.evaluate5g4gAction(
            isCurrently5g = true,
            lockedTo4gByEngine = false,
            emaScore = 25,
            sinrDb = -5,
            rsrpDbm = -118,
            consecutivePoor5gSamples = 5,
            inVoiceCall = true,
            nowMs = 100_000L,
            next5gProbeEligibleMs = 0L,
            preset = preset
        )
        assertEquals(NetworkQualityScorer.Action5g4g.HOLD, blockedByCallAction)
    }

    @Test
    fun `4G to 5G recovery waits for exponential backoff and healthy LTE anchor`() {
        val preset = SmartNetworkController.SensitivityPreset.BALANCED

        // Backoff not expired yet -> HOLD
        val beforeBackoff = NetworkQualityScorer.evaluate5g4gAction(
            isCurrently5g = false,
            lockedTo4gByEngine = true,
            emaScore = 78,
            sinrDb = 14,
            rsrpDbm = -90,
            consecutivePoor5gSamples = 0,
            inVoiceCall = false,
            nowMs = 120_000L,
            next5gProbeEligibleMs = 180_000L,
            preset = preset
        )
        assertEquals(NetworkQualityScorer.Action5g4g.HOLD, beforeBackoff)

        // Backoff expired and 4G signal is strong -> PROBE_5G_RECOVERY
        val readyToRecover = NetworkQualityScorer.evaluate5g4gAction(
            isCurrently5g = false,
            lockedTo4gByEngine = true,
            emaScore = 78,
            sinrDb = 14,
            rsrpDbm = -90,
            consecutivePoor5gSamples = 0,
            inVoiceCall = false,
            nowMs = 200_000L,
            next5gProbeEligibleMs = 180_000L,
            preset = preset
        )
        assertEquals(NetworkQualityScorer.Action5g4g.PROBE_5G_RECOVERY, readyToRecover)
    }

    @Test
    fun `Dual-SIM switch requires current SIM degradation, hysteresis margin, debounce, and cooldown`() {
        val preset = SmartNetworkController.SensitivityPreset.BALANCED

        // Case 1: Current SIM is already healthy (score 70 >= 48) -> do NOT switch even if SIM 2 is 92
        assertEquals(
            false,
            NetworkQualityScorer.shouldSwitchDataSim(
                currentSimScore = 70,
                candidateSimScore = 92,
                consecutiveCandidateBetterSamples = 5,
                inVoiceCall = false,
                nowMs = 200_000L,
                lastSimSwitchMs = 0L,
                preset = preset
            )
        )

        // Case 2: Current SIM is degraded (35), candidate is only marginally better (44, delta +9 < +18) -> false
        assertEquals(
            false,
            NetworkQualityScorer.shouldSwitchDataSim(
                currentSimScore = 35,
                candidateSimScore = 44,
                consecutiveCandidateBetterSamples = 5,
                inVoiceCall = false,
                nowMs = 200_000L,
                lastSimSwitchMs = 0L,
                preset = preset
            )
        )

        // Case 3: Current SIM is degraded (35), candidate is much better (76, delta +41 >= +18), sustained, cooldown passed -> true
        assertEquals(
            true,
            NetworkQualityScorer.shouldSwitchDataSim(
                currentSimScore = 35,
                candidateSimScore = 76,
                consecutiveCandidateBetterSamples = 3,
                inVoiceCall = false,
                nowMs = 200_000L,
                lastSimSwitchMs = 100_000L,
                preset = preset
            )
        )

        // Case 4: Cooldown not elapsed yet (only 20s since last switch < 60s) -> false
        assertEquals(
            false,
            NetworkQualityScorer.shouldSwitchDataSim(
                currentSimScore = 35,
                candidateSimScore = 76,
                consecutiveCandidateBetterSamples = 3,
                inVoiceCall = false,
                nowMs = 120_000L,
                lastSimSwitchMs = 100_000L,
                preset = preset
            )
        )
    }

    @Test
    fun `Wi-Fi and Mobile Data switch prefers Wi-Fi when usable and fails over to Mobile Data when Wi-Fi is weak or dead`() {
        val preset = SmartNetworkController.SensitivityPreset.BALANCED

        // Usable Wi-Fi (score 55 >= 40) -> KEEP_WIFI even if 5G is 90 (user prefers Wi-Fi)
        assertEquals(
            NetworkQualityScorer.ActionWifiData.HOLD,
            NetworkQualityScorer.evaluateWifiDataAction(
                isWifiConnected = true,
                switchedToMobileByEngine = false,
                wifiEmaScore = 55,
                bestSimEmaScore = 90,
                consecutivePoorWifiSamples = 0,
                inVoiceCall = false,
                nowMs = 100_000L,
                nextWifiProbeEligibleMs = 0L,
                preset = preset
            )
        )

        // Weak/dead Wi-Fi (score 28 < 38) sustained for 2 samples AND Mobile Data is strong (75) -> SWITCH_TO_MOBILE_DATA
        assertEquals(
            NetworkQualityScorer.ActionWifiData.SWITCH_TO_MOBILE_DATA,
            NetworkQualityScorer.evaluateWifiDataAction(
                isWifiConnected = true,
                switchedToMobileByEngine = false,
                wifiEmaScore = 28,
                bestSimEmaScore = 75,
                consecutivePoorWifiSamples = 2,
                inVoiceCall = false,
                nowMs = 100_000L,
                nextWifiProbeEligibleMs = 0L,
                preset = preset
            )
        )

        // While on Mobile Data due to engine failover, once backoff expires -> PROBE_WIFI_RECOVERY
        assertEquals(
            NetworkQualityScorer.ActionWifiData.PROBE_WIFI_RECOVERY,
            NetworkQualityScorer.evaluateWifiDataAction(
                isWifiConnected = false,
                switchedToMobileByEngine = true,
                wifiEmaScore = 0,
                bestSimEmaScore = 75,
                consecutivePoorWifiSamples = 0,
                inVoiceCall = false,
                nowMs = 250_000L,
                nextWifiProbeEligibleMs = 200_000L,
                preset = preset
            )
        )
    }
}
