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
    fun formatsAnimatorWidgetLabelsAndNextScaleCycle() {
        assertEquals(0.5f, EssentialsWidgetsUpdater.nextAnimatorScale(1.0f), 0.001f)
        assertEquals(0.0f, EssentialsWidgetsUpdater.nextAnimatorScale(0.5f), 0.001f)
        assertEquals(1.0f, EssentialsWidgetsUpdater.nextAnimatorScale(0.0f), 0.001f)

        val boosted = EssentialsWidgetsUpdater.buildAnimatorWidgetModel(0.5f)
        assertEquals("Animator • 0.5x", boosted.title)
        assertEquals("Speed Boost", boosted.subtitle)
        assertTrue(boosted.isActive)
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
