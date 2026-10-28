package eu.zax4r0.essentialsx.features.network

import eu.zax4r0.essentialsx.features.network.NetworkModeController.NetworkMode
import eu.zax4r0.essentialsx.features.network.NetworkModeController.SimFilter
import eu.zax4r0.essentialsx.features.network.NetworkModeController.SimInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkModeControllerTest {

    private val airtelPhysical = SimInfo(
        subId = 1,
        slotIndex = 0,
        displayName = "Airtel Primary",
        carrierName = "IND airtel",
        isEmbedded = false,
        isActive = true,
        isDefaultData = true,
        currentMode = NetworkMode.MODE_5G_PREF
    )

    private val jioEsimActive = SimInfo(
        subId = 2,
        slotIndex = 1,
        displayName = "Jio True5G",
        carrierName = "JIO 4G",
        isEmbedded = true,
        isActive = true,
        isDefaultData = false,
        currentMode = NetworkMode.MODE_NR_ONLY
    )

    private val niyoTravelEsim = SimInfo(
        subId = 3,
        slotIndex = -1,
        displayName = "Niyo Global Travel",
        carrierName = "Roaming Partner",
        isEmbedded = true,
        isActive = false,
        isDefaultData = false,
        currentMode = NetworkMode.MODE_5G_PREF
    )

    private val skyloEsim = SimInfo(
        subId = 4,
        slotIndex = -1,
        displayName = "Skylo Satellite",
        carrierName = "Skylo NTN",
        isEmbedded = true,
        isActive = false,
        isDefaultData = false,
        currentMode = NetworkMode.MODE_LTE_ONLY
    )

    private val allSims = listOf(airtelPhysical, jioEsimActive, niyoTravelEsim, skyloEsim)

    @Test
    fun filtersSimsByCategoryChip() {
        assertEquals(
            listOf(airtelPhysical, jioEsimActive),
            NetworkModeController.filterSims(allSims, query = "", filter = SimFilter.ACTIVE)
        )
        assertEquals(
            listOf(jioEsimActive, niyoTravelEsim, skyloEsim),
            NetworkModeController.filterSims(allSims, query = "", filter = SimFilter.ESIM)
        )
        assertEquals(
            listOf(airtelPhysical),
            NetworkModeController.filterSims(allSims, query = "", filter = SimFilter.PHYSICAL)
        )
    }

    @Test
    fun filtersSimsBySearchQueryMatchingNameOrCarrierOrSlot() {
        // Match display name
        assertEquals(
            listOf(niyoTravelEsim),
            NetworkModeController.filterSims(allSims, query = "niyo", filter = SimFilter.ALL)
        )
        // Match carrier name
        assertEquals(
            listOf(skyloEsim),
            NetworkModeController.filterSims(allSims, query = "ntn", filter = SimFilter.ALL)
        )
        // Match slot number ("slot 2" -> slotIndex 1)
        assertEquals(
            listOf(jioEsimActive),
            NetworkModeController.filterSims(allSims, query = "slot 2", filter = SimFilter.ALL)
        )
        // Combined filter chip (eSIM) + search query ("jio")
        assertEquals(
            listOf(jioEsimActive),
            NetworkModeController.filterSims(allSims, query = "jio", filter = SimFilter.ESIM)
        )
    }
}
