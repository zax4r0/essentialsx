package eu.zax4r0.essentialsx.features.caffeine

import org.junit.Assert.assertEquals
import org.junit.Test

class CaffeineControllerTest {

    @Test
    fun formatsShortPresetsInMinutesCorrectly() {
        val formatted = CaffeineController.PRESETS.map { CaffeineController.formatShortPreset(it) }
        assertEquals(listOf("5m", "10m", "30m", "1h", "∞"), formatted)
    }
}
