package eu.zax4r0.essentialsx.features.widgets

import eu.zax4r0.essentialsx.features.network.NetworkModeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EssentialsWidgetsTest {

    @Test
    fun formatsCaffeineWidgetLabelsProperly() {
        val activeState = EssentialsWidgetsUpdater.buildCaffeineWidgetModel(
            isActive = true,
            remainingSeconds = 295,
            timeoutMinutes = 5
        )
        assertEquals("Caffeine • Awake", activeState.title)
        assertEquals("4m 55s left", activeState.subtitle)
        assertEquals("5m", activeState.badge)
        assertTrue(activeState.isActive)

        val idleState = EssentialsWidgetsUpdater.buildCaffeineWidgetModel(
            isActive = false,
            remainingSeconds = 0,
            timeoutMinutes = 10
        )
        assertEquals("Caffeine", idleState.title)
        assertEquals("Sleep normal • 10m", idleState.subtitle)
        assertEquals("10m", idleState.badge)
    }

    @Test
    fun formatsWifiPriorityWidgetLabelsProperly() {
        val activeState = EssentialsWidgetsUpdater.buildWifiPriorityWidgetModel(
            isEnabled = true,
            connectedSsid = "WeWorkWiFi",
            inRangeCount = 2
        )
        assertEquals("Wi-Fi Priority • ON", activeState.title)
        assertEquals("WeWorkWiFi • 2 in range", activeState.subtitle)
        assertEquals("Switch", activeState.badge)
        assertTrue(activeState.isActive)

        val idleState = EssentialsWidgetsUpdater.buildWifiPriorityWidgetModel(
            isEnabled = false,
            connectedSsid = null,
            inRangeCount = 0
        )
        assertEquals("Wi-Fi Priority", idleState.title)
        assertEquals("Auto-switch off", idleState.subtitle)
        assertEquals("Switch", idleState.badge)
    }

    @Test
    fun formatsNetworkSimWidgetModel() {
        val sim = NetworkModeController.SimInfo(
            subId = 2,
            slotIndex = 1,
            displayName = "Jio",
            carrierName = "JIO",
            isEmbedded = true,
            isActive = true,
            isDefaultData = true,
            currentMode = NetworkModeController.NetworkMode.MODE_NR_ONLY
        )
        val model = EssentialsWidgetsUpdater.buildNetworkSimWidgetModel(sim, activeSimCount = 2)
        assertEquals("Jio (Slot 2)", model.title)
        assertEquals("5G Only (NR)", model.subtitle)
        assertEquals("SIM ⇄", model.badge)
        assertTrue(model.isActive)
    }
}
