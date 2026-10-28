package eu.zax4r0.essentialsx.features.network

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.telephony.SubscriptionManager
import android.util.Log
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

object NetworkModeController {

    private const val TAG = "NetworkModeController"

    enum class NetworkMode(val displayName: String, val shortName: String) {
        MODE_5G_PREF("5G Preferred (Auto)", "5G Auto"),
        MODE_NR_ONLY("5G Only (NR)", "5G Only"),
        MODE_LTE_ONLY("4G Only (LTE)", "4G Only"),
        MODE_3G_ONLY("3G Only", "3G Only"),
        MODE_2G_ONLY("2G Only", "2G Only")
    }

    enum class SimFilter(val label: String) {
        ALL("All"),
        ACTIVE("Active"),
        ESIM("eSIM"),
        PHYSICAL("Physical")
    }

    data class SimInfo(
        val subId: Int,
        val slotIndex: Int,          // 0, 1, or -1 (inactive)
        val displayName: String,     // e.g. "Airtel", "Jio", "Skylo", "Niyo global", etc.
        val carrierName: String,     // e.g. "airtel", "No service"
        val isEmbedded: Boolean,     // true for eSIM, false for physical SIM
        val isActive: Boolean,       // slotIndex >= 0
        val isDefaultData: Boolean,  // matches multi_sim_data_call
        val currentMode: NetworkMode = NetworkMode.MODE_5G_PREF
    )

    private val _sims = MutableStateFlow<List<SimInfo>>(emptyList())
    val sims: StateFlow<List<SimInfo>> = _sims

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val idRegex = Regex("""\bid=(\d+)""")
    private val slotRegex = Regex("""\bsimSlotIndex=(-?\d+)""")
    private val embeddedRegex = Regex("""\bisEmbedded=(\d+)""")
    private val nameRegex = Regex("""\bdisplayName=(.*?)\s+carrierName=""", RegexOption.DOT_MATCHES_ALL)
    private val carrierRegex = Regex("""\bcarrierName=(.*?)\s+isOpportunistic=""", RegexOption.DOT_MATCHES_ALL)

    suspend fun refresh(context: Context) {
        if (!ShizukuHelper.hasPorterPermission()) {
            loadFromSubscriptionManager(context)
            return
        }
        _isLoading.value = true
        withContext(Dispatchers.IO) {
            val defaultSubOutput = ShizukuHelper.runCommandWithOutput("settings get global multi_sim_data_call")
            val defaultDataSubId = defaultSubOutput?.trim()?.toIntOrNull() ?: -1

            val isubOutput = ShizukuHelper.runCommandWithOutput("dumpsys isub")
            val parsedSims = parseIsubOutput(isubOutput, defaultDataSubId)

            val fullSims = if (parsedSims.isNotEmpty()) {
                parsedSims
            } else {
                parseFromSubscriptionManager(context, defaultDataSubId)
            }

            // Query current network modes for active slots
            val updatedSims = fullSims.map { sim ->
                if (sim.isActive && sim.slotIndex >= 0) {
                    val mode = queryCurrentMode(sim.slotIndex)
                    sim.copy(currentMode = mode)
                } else {
                    sim
                }
            }

            // Sort: active SIMs first (by slotIndex), then inactive eSIMs, then inactive physical SIMs
            _sims.value = updatedSims.sortedWith(
                compareBy<SimInfo> { !it.isActive }
                    .thenBy { it.slotIndex }
                    .thenBy { !it.isEmbedded }
                    .thenBy { it.subId }
            )

            _isLoading.value = false
            refreshTile(context)
        }
    }

    private fun parseIsubOutput(output: String?, defaultDataSubId: Int): List<SimInfo> {
        if (output.isNullOrBlank()) return emptyList()
        val results = LinkedHashMap<Int, SimInfo>()

        output.lineSequence().forEach { line ->
            if (!line.contains("SubscriptionInfoInternal:")) return@forEach
            val id = idRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

            // If we already have this SIM and it is active, keep the active record
            if (results[id]?.isActive == true) return@forEach

            val slot = slotRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val isEmbedded = (embeddedRegex.find(line)?.groupValues?.get(1) == "1")
            val rawName = nameRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()
            val rawCarrier = carrierRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()

            val name = rawName.ifBlank { rawCarrier.ifBlank { "SIM $id" } }
            val isActive = slot >= 0
            val isDefaultData = (id == defaultDataSubId)

            results[id] = SimInfo(
                subId = id,
                slotIndex = slot,
                displayName = name,
                carrierName = rawCarrier,
                isEmbedded = isEmbedded,
                isActive = isActive,
                isDefaultData = isDefaultData
            )
        }
        return results.values.toList()
    }

    private fun loadFromSubscriptionManager(context: Context) {
        val defaultDataSubId = -1
        _sims.value = parseFromSubscriptionManager(context, defaultDataSubId)
    }

    private fun parseFromSubscriptionManager(context: Context, defaultDataSubId: Int): List<SimInfo> {
        return runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val activeList = sm.activeSubscriptionInfoList.orEmpty()
            activeList.map { sub ->
                SimInfo(
                    subId = sub.subscriptionId,
                    slotIndex = sub.simSlotIndex,
                    displayName = sub.displayName?.toString().orEmpty().ifBlank { "SIM ${sub.subscriptionId}" },
                    carrierName = sub.carrierName?.toString().orEmpty(),
                    isEmbedded = sub.isEmbedded,
                    isActive = true,
                    isDefaultData = (sub.subscriptionId == defaultDataSubId)
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun setModeForSim(context: Context, sim: SimInfo, mode: NetworkMode) {
        if (!sim.isActive || sim.slotIndex < 0) return
        if (!ShizukuHelper.hasPorterPermission()) return

        _isLoading.value = true
        withContext(Dispatchers.IO) {
            val bitmask = getBitmaskForMode(mode)
            val bitmaskString = longToBinaryString20(bitmask)

            val success = ShizukuHelper.runCommand("cmd phone set-allowed-network-types-for-users -s ${sim.slotIndex} $bitmaskString")
            Log.d(TAG, "cmd phone set-allowed-network-types-for-users -s ${sim.slotIndex} $bitmaskString result=$success")

            // Small delay for modem to register then query verified mode
            kotlinx.coroutines.delay(150)
            val verifiedMode = queryCurrentMode(sim.slotIndex)

            _sims.value = _sims.value.map {
                if (it.subId == sim.subId) it.copy(currentMode = verifiedMode) else it
            }
            _isLoading.value = false
            refreshTile(context)
        }
    }

    suspend fun toggleDefaultDataSimMode(context: Context) {
        val activeSims = _sims.value.filter { it.isActive }
        val targetSim = activeSims.firstOrNull { it.isDefaultData } ?: activeSims.firstOrNull() ?: return
        val next = when (targetSim.currentMode) {
            NetworkMode.MODE_NR_ONLY -> NetworkMode.MODE_LTE_ONLY
            NetworkMode.MODE_LTE_ONLY -> NetworkMode.MODE_5G_PREF
            else -> NetworkMode.MODE_NR_ONLY
        }
        setModeForSim(context, targetSim, next)
    }

    suspend fun setDefaultDataSubId(context: Context, subId: Int) {
        withContext(Dispatchers.IO) {
            try {
                val binder = rikka.shizuku.SystemServiceHelper.getSystemService("isub")
                if (binder != null) {
                    val wrappedBinder = rikka.shizuku.ShizukuBinderWrapper(binder)
                    val stubClass = Class.forName("com.android.internal.telephony.ISub\$Stub")
                    val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                    val iSubInstance = asInterfaceMethod.invoke(null, wrappedBinder)
                    val setDefaultDataSubIdMethod = iSubInstance.javaClass.getMethod(
                        "setDefaultDataSubId",
                        Int::class.javaPrimitiveType
                    )
                    setDefaultDataSubIdMethod.invoke(iSubInstance, subId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reflection setDefaultDataSubId failed, falling back to shell", e)
            }
            ShizukuHelper.runCommand("settings put global multi_sim_data_call $subId")
            ShizukuHelper.runCommand("cmd phone set-default-data-sub-id $subId")
            ShizukuHelper.runCommand("svc data enable")
        }
        refresh(context)
    }

    suspend fun toggleDefaultDataSim(context: Context) {
        val activeSims = _sims.value.filter { it.isActive }
        if (activeSims.size < 2) return
        val currentDefault = activeSims.firstOrNull { it.isDefaultData } ?: activeSims.first()
        val nextSim = activeSims.firstOrNull { it.subId != currentDefault.subId } ?: return
        setDefaultDataSubId(context, nextSim.subId)
    }

    fun getDefaultTargetSim(): SimInfo? {
        val activeSims = _sims.value.filter { it.isActive }
        return activeSims.firstOrNull { it.isDefaultData } ?: activeSims.firstOrNull()
    }

    fun filterSims(
        sims: List<SimInfo>,
        query: String,
        filter: SimFilter = SimFilter.ALL
    ): List<SimInfo> {
        val q = query.trim()
        return sims.filter { sim ->
            val categoryMatch = when (filter) {
                SimFilter.ALL -> true
                SimFilter.ACTIVE -> sim.isActive
                SimFilter.ESIM -> sim.isEmbedded
                SimFilter.PHYSICAL -> !sim.isEmbedded
            }
            if (!categoryMatch) return@filter false
            if (q.isEmpty()) return@filter true

            val slotLabel = if (sim.isActive) "slot ${sim.slotIndex + 1}" else "inactive"
            val typeLabel = if (sim.isEmbedded) "esim" else "physical sim"
            sim.displayName.contains(q, ignoreCase = true) ||
                sim.carrierName.contains(q, ignoreCase = true) ||
                slotLabel.contains(q, ignoreCase = true) ||
                typeLabel.contains(q, ignoreCase = true) ||
                sim.currentMode.displayName.contains(q, ignoreCase = true)
        }
    }

    private fun queryCurrentMode(slotId: Int): NetworkMode {
        val output = ShizukuHelper.runCommandWithOutput("cmd phone get-allowed-network-types-for-users -s $slotId")
        if (output.isNullOrBlank()) {
            return NetworkMode.MODE_5G_PREF
        }
        val hasNR = output.contains("NR", ignoreCase = true)
        val hasLTE = output.contains("LTE", ignoreCase = true)
        val has3G = output.contains("UMTS", ignoreCase = true) || output.contains("HSPA", ignoreCase = true) || output.contains("HSDPA", ignoreCase = true)
        val has2G = output.contains("GSM", ignoreCase = true) || output.contains("GPRS", ignoreCase = true) || output.contains("EDGE", ignoreCase = true)

        return when {
            hasNR && !hasLTE && !has3G && !has2G -> NetworkMode.MODE_NR_ONLY
            !hasNR && hasLTE && !has3G && !has2G -> NetworkMode.MODE_LTE_ONLY
            !hasNR && !hasLTE && has3G && !has2G -> NetworkMode.MODE_3G_ONLY
            !hasNR && !hasLTE && !has3G && has2G -> NetworkMode.MODE_2G_ONLY
            else -> NetworkMode.MODE_5G_PREF
        }
    }

    private fun getBitmaskForMode(mode: NetworkMode): Long {
        val bitmask2G = getBitmaskForTech(16) or
                getBitmaskForTech(1) or
                getBitmaskForTech(2) or
                getBitmaskForTech(4) or
                getBitmaskForTech(7) or
                getBitmaskForTech(11)

        val bitmask3G = bitmask2G or
                getBitmaskForTech(3) or
                getBitmaskForTech(5) or
                getBitmaskForTech(6) or
                getBitmaskForTech(12) or
                getBitmaskForTech(8) or
                getBitmaskForTech(9) or
                getBitmaskForTech(10) or
                getBitmaskForTech(14) or
                getBitmaskForTech(15)

        val bitmask4G = getBitmaskForTech(13) or getBitmaskForTech(19)
        val bitmask5G = getBitmaskForTech(20)

        return when (mode) {
            NetworkMode.MODE_5G_PREF -> bitmask2G or bitmask3G or bitmask4G or bitmask5G
            NetworkMode.MODE_NR_ONLY -> bitmask5G
            NetworkMode.MODE_LTE_ONLY -> bitmask4G
            NetworkMode.MODE_3G_ONLY -> bitmask3G
            NetworkMode.MODE_2G_ONLY -> bitmask2G
        }
    }

    private fun getBitmaskForTech(tech: Int): Long =
        if (tech > 0) (1L shl (tech - 1)) else 0L

    private fun longToBinaryString20(mask: Long): String {
        val sb = StringBuilder()
        for (i in 20 downTo 1) {
            val bit = if ((mask and (1L shl (i - 1))) != 0L) '1' else '0'
            sb.append(bit)
        }
        return sb.toString()
    }

    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, NetworkModeTileService::class.java)
            )
        }
    }
}
