package eu.zax4r0.essentialsx.ui.screens

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.smartnet.NetworkQualityScorer
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkTileService
import eu.zax4r0.essentialsx.ui.ChoiceDialog
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.SettingsCategory
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.ui.SettingsSwitch
import eu.zax4r0.essentialsx.utils.QuickSettingsTileManager
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.launch

@Composable
fun SmartNetworkScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shizuku / Porter states
    val isPorterRunning by ShizukuHelper.isAvailable.collectAsState()
    val hasPorterPermission by ShizukuHelper.hasPermission.collectAsState()

    // Smart Network Engine states
    val isSmartNetEnabled by SmartNetworkController.isEnabled.collectAsState()
    val autoWifiData by SmartNetworkController.autoWifiDataEnabled.collectAsState()
    val auto5g4g by SmartNetworkController.auto5g4gEnabled.collectAsState()
    val autoSimSwitch by SmartNetworkController.autoSimSwitchEnabled.collectAsState()
    val smartSensitivity by SmartNetworkController.sensitivityPreset.collectAsState()
    val smartStatus by SmartNetworkController.engineStatus.collectAsState()
    val smartLastAction by SmartNetworkController.lastActionLog.collectAsState()
    val wifiSnapshot by SmartNetworkController.wifiSnapshot.collectAsState()
    val simSnapshots by SmartNetworkController.simSnapshots.collectAsState()

    var showSensitivityDialog by remember { mutableStateOf(false) }
    var showSmartNetTileDialog by remember { mutableStateOf(false) }

    fun addTile(serviceClass: Class<*>, title: String, @DrawableRes iconRes: Int) {
        scope.launch {
            QuickSettingsTileManager.addTileToLast(context, serviceClass, title, iconRes) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(hasPorterPermission) {
        ShizukuHelper.updateState()
        NetworkModeController.refresh(context)
    }

    if (showSensitivityDialog) {
        val presets = SmartNetworkController.SensitivityPreset.entries
        val choices = presets.map { it.displayName }
        val selectedIdx = presets.indexOf(smartSensitivity).coerceAtLeast(0)
        ChoiceDialog(
            title = stringResource(R.string.smart_net_sensitivity_title),
            choices = choices,
            selected = selectedIdx,
            onDismiss = { showSensitivityDialog = false },
            onSelect = { idx ->
                SmartNetworkController.setSensitivityPreset(context, presets[idx])
            }
        )
    }

    if (showSmartNetTileDialog) {
        val modes = SmartNetworkController.TileClickMode.entries
        val choices = modes.map { it.displayName }
        val selectedIdx = modes.indexOf(SmartNetworkController.tileClickMode.value).coerceAtLeast(0)
        ChoiceDialog(
            title = "Choose Smart Network Tile Behavior",
            choices = choices,
            selected = selectedIdx,
            onDismiss = { showSmartNetTileDialog = false },
            onSelect = { idx ->
                showSmartNetTileDialog = false
                SmartNetworkController.setTileClickMode(context, modes[idx])
                addTile(SmartNetworkTileService::class.java, "Smart Network", R.drawable.ic_smart_network)
            }
        )
    }

    PorterScaffold(
        title = stringResource(R.string.smart_net_title),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null,
        actions = {
            IconButton(
                onClick = { scope.launch { NetworkModeController.refresh(context) } },
                enabled = hasPorterPermission
            ) {
                Icon(Icons.TwoTone.Refresh, contentDescription = "Refresh")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            item(key = "category_engine") {
                SettingsCategory(stringResource(R.string.smart_net_title))
            }

            if (!hasPorterPermission) {
                item(key = "porter_warning") {
                    SettingsItem(
                        title = if (!isPorterRunning) "Porter is not running" else "Permission required",
                        icon = Icons.TwoTone.Warning,
                        summary = if (!isPorterRunning) "Start Porter to auto-switch network modes & data SIM" else "Tap to grant Porter / Shizuku permission",
                        onClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }

            item(key = "master_switch") {
                SettingsSwitch(
                    title = stringResource(R.string.smart_net_master_title),
                    icon = Icons.TwoTone.AutoAwesome,
                    checked = isSmartNetEnabled,
                    summary = if (isSmartNetEnabled) {
                        smartStatus
                    } else {
                        stringResource(R.string.smart_net_master_summary_off)
                    },
                    enabled = hasPorterPermission || isSmartNetEnabled,
                    onCheckedChange = { SmartNetworkController.setEnabled(context, it) }
                )
            }

            item(key = "telemetry_card") {
                SmartNetworkTelemetryCard(
                    isEnabled = isSmartNetEnabled,
                    status = smartStatus,
                    lastAction = smartLastAction,
                    wifiSnapshot = wifiSnapshot,
                    simSnapshots = simSnapshots
                )
            }

            item(key = "category_rules") {
                SettingsCategory("Auto-Switching Rules")
            }

            item(key = "rule_wifi_data") {
                SettingsSwitch(
                    title = stringResource(R.string.smart_net_wifi_data_title),
                    icon = Icons.TwoTone.Wifi,
                    checked = autoWifiData,
                    summary = stringResource(R.string.smart_net_wifi_data_summary),
                    onCheckedChange = { SmartNetworkController.setAutoWifiDataEnabled(context, it) }
                )
            }

            item(key = "rule_5g_4g") {
                SettingsSwitch(
                    title = stringResource(R.string.smart_net_5g_4g_title),
                    icon = Icons.TwoTone.NetworkCell,
                    checked = auto5g4g,
                    summary = stringResource(R.string.smart_net_5g_4g_summary),
                    onCheckedChange = { SmartNetworkController.setAuto5g4gEnabled(context, it) }
                )
            }

            item(key = "rule_dual_sim") {
                SettingsSwitch(
                    title = stringResource(R.string.smart_net_dual_sim_title),
                    icon = Icons.TwoTone.SimCard,
                    checked = autoSimSwitch,
                    summary = stringResource(R.string.smart_net_dual_sim_summary),
                    onCheckedChange = { SmartNetworkController.setAutoSimSwitchEnabled(context, it) }
                )
            }

            item(key = "sensitivity") {
                SettingsItem(
                    title = stringResource(R.string.smart_net_sensitivity_title),
                    icon = Icons.TwoTone.Speed,
                    summary = "Preset: ${smartSensitivity.displayName}",
                    onClick = { showSensitivityDialog = true }
                )
            }

            item(key = "category_qs") {
                SettingsCategory("Quick Settings Tile & Tray")
            }

            item(key = "qs_tile") {
                SettingsItem(
                    title = "Smart Network Tile",
                    icon = Icons.TwoTone.AutoAwesome,
                    summary = "Tap to toggle or open tray • Hold tile to open Smart Wi-Fi/Data/5G Tray",
                    trailing = {
                        FilledTonalButton(
                            onClick = { showSmartNetTileDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Add")
                        }
                    },
                    onClick = { showSmartNetTileDialog = true }
                )
            }

            item(key = "end") {
                Spacer(Modifier.height(32.dp + bottomInset))
            }
        }
    }
}

@Composable
fun SmartNetworkTelemetryCard(
    isEnabled: Boolean,
    status: String,
    lastAction: String,
    wifiSnapshot: NetworkQualityScorer.WifiQualitySnapshot?,
    simSnapshots: Map<Int, NetworkQualityScorer.SimQualitySnapshot>
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Network Quality Telemetry",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = if (isEnabled) "Active" else "Standby",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (wifiSnapshot != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Wi-Fi (${wifiSnapshot.rssiDbm} dBm • ${wifiSnapshot.linkSpeedMbps} Mbps)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${wifiSnapshot.emaScore}/100 (${wifiSnapshot.grade})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (simSnapshots.isEmpty() && wifiSnapshot == null) {
                Text(
                    text = if (isEnabled) status else "Turn on Smart Network Auto-Switcher to monitor SINR, RSRQ, RSRP & latency",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            simSnapshots.values.forEach { snap ->
                val metrics = buildList {
                    snap.sinrDb?.let { add("SINR ${it}dB") }
                    snap.rsrqDb?.let { add("RSRQ ${it}dB") }
                    snap.rsrpDbm?.let { add("RSRP ${it}dBm") }
                    snap.latencyMs?.takeIf { it > 0 }?.let { add("${it}ms") }
                }.joinToString(" • ").ifBlank { "Level ${snap.signalLevel}/4" }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${snap.displayName} • ${snap.ratLabel}" + if (snap.isDefaultData) " [Data]" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${snap.emaScore}/100 (${snap.grade})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (snap.isDefaultData) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = metrics,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "Last action: $lastAction",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
