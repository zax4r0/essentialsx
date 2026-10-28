package eu.zax4r0.essentialsx.features.wifi

import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController.SavedNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPriorityControllerTest {

    private val home = SavedNetwork(1, "Home")
    private val office = SavedNetwork(2, "Office")
    private val phone = SavedNetwork(3, "Phone")
    private val ranked = listOf(home, office, phone)

    // ---- the rule: highest-priority saved network in range wins ----

    @Test
    fun picksHighestPriorityInRange() {
        assertEquals(office, WifiPriorityController.pickTarget(ranked, setOf("Office", "Phone"), "Phone"))
    }

    @Test
    fun staysPutWhenAlreadyOnTheBest() {
        assertNull(WifiPriorityController.pickTarget(ranked, setOf("Home", "Office"), "Home"))
        assertNull(WifiPriorityController.pickTarget(ranked, setOf("Phone"), "Phone"))
    }

    @Test
    fun doesNothingWhenNothingSavedIsInRange() {
        assertNull(WifiPriorityController.pickTarget(ranked, emptySet(), "Phone"))
        assertNull(WifiPriorityController.pickTarget(ranked, setOf("Stranger"), null))
    }

    @Test
    fun leavesAnUnlistedNetworkForTheBestSavedOne() {
        assertEquals(phone, WifiPriorityController.pickTarget(ranked, setOf("Phone"), "Random"))
        assertEquals(phone, WifiPriorityController.pickTarget(ranked, setOf("Phone"), null))
    }

    // ---- order ----

    @Test
    fun storedOrderFirstNewNetworksAtTheBottom() {
        val saved = listOf("A", "B", "C", "D").mapIndexed { i, s -> SavedNetwork(i, s) }
        val result = WifiPriorityController.applyOrder(saved, listOf("C", "Gone", "A"))
        assertEquals(listOf("C", "A", "B", "D"), result.map { it.ssid })
    }

    @Test
    fun oneEntryPerSsid() {
        val saved = listOf(SavedNetwork(5, "Dup"), SavedNetwork(9, "Dup"))
        assertEquals(listOf(SavedNetwork(5, "Dup")), WifiPriorityController.applyOrder(saved, emptyList()))
    }

    @Test
    fun reorderingNetworkToTopImmediatelySelectsItAsSwitchTarget() {
        // Initially connected to "Home" (rank 1), with "Office" (rank 2) also in range
        val initial = listOf(home, office, phone)
        val inRange = setOf("Home", "Office")
        assertNull(WifiPriorityController.pickTarget(initial, inRange, "Home"))

        // User drags "Office" (index 1) to top (index 0)
        val reordered = WifiPriorityController.reorderNetworks(initial, from = 1, to = 0)
        assertEquals(listOf(office, home, phone), reordered)

        // Immediately picks "Office" as the switch target
        val target = WifiPriorityController.pickTarget(reordered, inRange, "Home")
        assertEquals(office, target)
    }

    @Test
    fun userReorderBypassesRetryCooldown() {
        val now = 1_000_000L
        // Background tick within 2 minutes of last attempt on same SSID is throttled
        assertTrue(
            WifiPriorityController.shouldThrottleAttempt(
                targetSsid = "Home",
                lastAttemptSsid = "Home",
                lastAttemptAt = now - 30_000L,
                now = now,
                forceImmediate = false
            )
        )
        // User-initiated reorder switch bypasses the 2-minute cooldown immediately
        assertFalse(
            WifiPriorityController.shouldThrottleAttempt(
                targetSsid = "Home",
                lastAttemptSsid = "Home",
                lastAttemptAt = now - 30_000L,
                now = now,
                forceImmediate = true
            )
        )
    }

    @Test
    fun filtersWifiNetworksBySearchQueryAndInRange() {
        val list = listOf(
            SavedNetwork(1, "Home 5GHz"),
            SavedNetwork(2, "WeWork Office"),
            SavedNetwork(3, "Cafe Guest"),
            SavedNetwork(4, "Home 2.4GHz")
        )
        val inRange = setOf("Home 5GHz", "Cafe Guest")

        // Search by query (case-insensitive)
        val homeMatches = WifiPriorityController.filterNetworks(list, query = "home", onlyInRange = false, inRange = inRange)
        assertEquals(listOf("Home 5GHz", "Home 2.4GHz"), homeMatches.map { it.ssid })

        // Filter by in-range only
        val inRangeMatches = WifiPriorityController.filterNetworks(list, query = "", onlyInRange = true, inRange = inRange)
        assertEquals(listOf("Home 5GHz", "Cafe Guest"), inRangeMatches.map { it.ssid })

        // Combined search query + in-range filter
        val combined = WifiPriorityController.filterNetworks(list, query = "home", onlyInRange = true, inRange = inRange)
        assertEquals(listOf("Home 5GHz"), combined.map { it.ssid })
    }

    @Test
    fun moveSsidToTopPromotesNetworkFromFilteredSearch() {
        val list = listOf(home, office, phone)
        val promoted = WifiPriorityController.moveSsidToTop(list, "Phone")
        assertEquals(listOf(phone, home, office), promoted)
    }

    // ---- parsers (rows captured from `cmd wifi` on Android 16) ----

    @Test
    fun parsesSavedNetworksOncePerIdKeepingSpaces() {
        val output = """
            Network Id      SSID                         Security type
            0            A                                wpa2-psk
            0            A                                wpa3-sae^
            13           Kelage 5Ghz                      wpa2-psk
            14           Malnad Boys  5Ghz                wpa2-psk
            14           Malnad Boys  5Ghz                wpa3-sae^
        """.trimIndent()
        assertEquals(
            listOf(SavedNetwork(0, "A"), SavedNetwork(13, "Kelage 5Ghz"), SavedNetwork(14, "Malnad Boys  5Ghz")),
            WifiPriorityController.parseSavedNetworks(output)
        )
        assertTrue(WifiPriorityController.parseSavedNetworks(null).isEmpty())
    }

    @Test
    fun parsesOnlyFreshScanResults() {
        val output = """
            BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
          44:1e:98:96:d0:ed       5320    -50(0:-52/1:-54)   >1000.0    AveryDennison_Wireless            [WPA2-PSK-CCMP-128][ESS]
          44:1e:98:96:d0:ec       5320    -49(0:-52/1:-53)      2.3    WeWorkDevice                      [WPA2-PSK-CCMP-128][ESS]
          0e:9a:44:bc:4a:4e       2412    -76(0:-83/1:-77)      1.1    Galaxy A23 5G 9569                [WPA2-PSK-CCMP-128][ESS]
          44:1e:98:56:c7:2c       5320    -86(0:-89/1:-89)     90.0    OldOne                            [WPA2-EAP/SHA1-CCMP-128][ESS]
          44:1e:98:56:c7:2d       5320    -86(0:-89/1:-89)      1.0                                      [WPA2-PSK-CCMP-128][ESS]
        """.trimIndent()
        assertEquals(setOf("WeWorkDevice", "Galaxy A23 5G 9569"), WifiPriorityController.parseVisibleSsids(output))
    }

    @Test
    fun parsesConnectedNetworkAndWifiState() {
        val on = """
            Wifi is enabled
            Wifi scanning is always available
            ==== Primary ClientModeManager instance ====
            Wifi is connected to "WeWorkWiFi"
            WifiInfo: SSID: "WeWorkWiFi", BSSID: 44:1e:98:56:d0:ec
        """.trimIndent()
        assertEquals("WeWorkWiFi", WifiPriorityController.parseConnectedSsid(on))
        assertTrue(WifiPriorityController.isWifiOn(on))

        val off = "Wifi is disabled\nWifi scanning is always available"
        assertNull(WifiPriorityController.parseConnectedSsid(off))
        assertFalse(WifiPriorityController.isWifiOn(off))
    }
}
