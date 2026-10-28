package eu.zax4r0.essentialsx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.animator.AnimatorController
import eu.zax4r0.essentialsx.features.caffeine.CaffeineController
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import eu.zax4r0.essentialsx.utils.UpdateManager
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    bottomInset: Dp = 0.dp,
    onNavigateToTab: (Int) -> Unit = {}
) {
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
    val smartStatus by SmartNetworkController.engineStatus.collectAsState()

    // Wi-Fi states
    val isWifiAutoEnabled by WifiPriorityController.isEnabled.collectAsState()
    val connectedWifi by WifiPriorityController.connectedSsid.collectAsState()
    val savedWifis by WifiPriorityController.networks.collectAsState()
    val inRangeWifis by WifiPriorityController.inRange.collectAsState()

    // SIM states
    val sims by NetworkModeController.sims.collectAsState()
    val isNetworkLoading by NetworkModeController.isLoading.collectAsState()

    // Caffeine states
    val isCaffeineActive by CaffeineController.isActive.collectAsState()
    val caffeineRemaining by CaffeineController.remainingSecondsFlow.collectAsState()
    val caffeineTimeout by CaffeineController.selectedTimeout.collectAsState()
    val caffeineAbortScreenOff by CaffeineController.abortOnScreenOff.collectAsState()

    // Animator states
    val windowScale by AnimatorController.windowScale.collectAsState()
    val transitionScale by AnimatorController.transitionScale.collectAsState()
    val animatorScale by AnimatorController.animatorScale.collectAsState()
    val isAnimatorLinked by AnimatorController.isLinked.collectAsState()
    var showAnimatorSliders by remember { mutableStateOf(false) }

    // OTA Update states
    val availableUpdate by UpdateManager.availableUpdate.collectAsState()
    val downloadState by UpdateManager.downloadState.collectAsState()

    LaunchedEffect(hasPorterPermission) {
        ShizukuHelper.updateState()
        NetworkModeController.refresh(context)
        WifiPriorityController.refresh()
    }

    PorterScaffold(
        title = stringResource(R.string.app_name),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null,
        actions = {
            IconButton(onClick = { onNavigateToTab(4) }) {
                Icon(Icons.TwoTone.Settings, contentDescription = "Settings & About")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp + bottomInset)
        ) {
            if (!hasPorterPermission) {
                item(key = "porter_warning") {
                    SettingsItem(
                        title = if (!isPorterRunning) "Porter is not running" else "Permission required",
                        icon = Icons.TwoTone.Warning,
                        summary = if (!isPorterRunning) "Start Porter / Shizuku to unlock system controls" else "Tap to grant Porter / Shizuku permission",
                        onClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }

            // ================= BACKGROUND OTA BANNER (IF ACTIVE) =================
            if (downloadState !is UpdateManager.DownloadState.Idle || availableUpdate?.isUpdateAvailable == true) {
                item(key = "ota_banner") {
                    OtaQuickBannerCard(
                        availableUpdate = availableUpdate,
                        downloadState = downloadState,
                        onOpenSettings = { onNavigateToTab(4) },
                        onInstall = { UpdateManager.installDownloadedUpdate(context) },
                        onStartDownload = { upd -> UpdateManager.startBackgroundDownload(context, upd) }
                    )
                }
            }

            // ================= 1. SMART NETWORK ENGINE BENTO CARD =================
            item(key = "bento_smart_net") {
                BentoCardContainer(
                    isActive = isSmartNetEnabled,
                    onClickHeader = { onNavigateToTab(2) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BentoIconBox(
                            icon = Icons.TwoTone.AutoAwesome,
                            isActive = isSmartNetEnabled
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Smart Network Engine",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.TwoTone.ChevronRight,
                                    contentDescription = "Open Smart Net tab",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (isSmartNetEnabled) smartStatus else "Auto 5G/4G, Dual-SIM & Wi-Fi ↔ Data",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSmartNetEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = isSmartNetEnabled,
                            enabled = hasPorterPermission || isSmartNetEnabled,
                            onCheckedChange = { SmartNetworkController.setEnabled(context, it) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = autoWifiData,
                            onClick = { SmartNetworkController.setAutoWifiDataEnabled(context, !autoWifiData) },
                            label = { Text("Wi-Fi ↔ Data", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = if (autoWifiData) {
                                { Icon(Icons.TwoTone.Check, null, Modifier.size(14.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = auto5g4g,
                            onClick = { SmartNetworkController.setAuto5g4gEnabled(context, !auto5g4g) },
                            label = { Text("5G ↔ 4G", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = if (auto5g4g) {
                                { Icon(Icons.TwoTone.Check, null, Modifier.size(14.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = autoSimSwitch,
                            onClick = { SmartNetworkController.setAutoSimSwitchEnabled(context, !autoSimSwitch) },
                            label = { Text("Dual-SIM Data", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = if (autoSimSwitch) {
                                { Icon(Icons.TwoTone.Check, null, Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }
            }

            // ================= 2. WI-FI PRIORITY BENTO CARD =================
            item(key = "bento_wifi") {
                val inRangeSaved = remember(savedWifis, inRangeWifis) {
                    savedWifis.filter { it.ssid in inRangeWifis }
                }
                BentoCardContainer(
                    isActive = isWifiAutoEnabled || connectedWifi != null,
                    onClickHeader = { onNavigateToTab(3) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BentoIconBox(
                            icon = Icons.TwoTone.Wifi,
                            isActive = isWifiAutoEnabled || connectedWifi != null
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Wi-Fi Priority",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.TwoTone.ChevronRight,
                                    contentDescription = "Open Wi-Fi tab",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val wifiSub = when {
                                connectedWifi != null -> "Connected: $connectedWifi • ${inRangeSaved.size} in range"
                                inRangeSaved.isNotEmpty() -> "${inRangeSaved.size} saved in range • ${savedWifis.size} total"
                                else -> "${savedWifis.size} saved networks"
                            }
                            Text(
                                text = wifiSub,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connectedWifi != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = isWifiAutoEnabled,
                            enabled = hasPorterPermission || isWifiAutoEnabled,
                            onCheckedChange = { WifiPriorityController.setEnabled(context, it) }
                        )
                    }

                    if (inRangeSaved.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            inRangeSaved.take(3).forEachIndexed { idx, net ->
                                val isCurrent = net.ssid == connectedWifi
                                FilterChip(
                                    selected = isCurrent,
                                    onClick = {
                                        scope.launch {
                                            WifiPriorityController.moveToTopAndConnect(context, net.ssid)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = "#${idx + 1} ${net.ssid}",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isCurrent) Icons.TwoTone.Check else Icons.TwoTone.Wifi,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                            AssistChip(
                                onClick = { onNavigateToTab(3) },
                                label = { Text("Reorder All →", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // ================= 3. ACTIVE SIM & 5G MODE BENTO CARD =================
            item(key = "bento_sim") {
                val activeSims = remember(sims) { sims.filter { it.isActive } }
                val targetSim = remember(activeSims) {
                    activeSims.firstOrNull { it.isDefaultData } ?: activeSims.firstOrNull()
                }

                BentoCardContainer(
                    isActive = targetSim != null,
                    onClickHeader = { onNavigateToTab(1) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BentoIconBox(
                            icon = Icons.TwoTone.SimCard,
                            isActive = targetSim != null
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "SIM & 5G Network Lock",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    imageVector = Icons.TwoTone.ChevronRight,
                                    contentDescription = "Open SIM tab",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val simSub = if (targetSim != null) {
                                "Data: ${targetSim.displayName} (Slot ${targetSim.slotIndex + 1}) • ${targetSim.currentMode.shortName}"
                            } else {
                                "${sims.size} SIM / eSIM profiles"
                            }
                            Text(
                                text = simSub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilledTonalButton(
                            onClick = { onNavigateToTab(1) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("All (${sims.size})", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Dual-SIM 1-tap Data Switcher if 2 active SIMs exist
                    if (activeSims.size >= 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            activeSims.forEach { sim ->
                                FilterChip(
                                    selected = sim.isDefaultData,
                                    onClick = {
                                        if (!sim.isDefaultData && hasPorterPermission) {
                                            scope.launch {
                                                NetworkModeController.setDefaultDataSubId(context, sim.subId)
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = {
                                        Text(
                                            text = "Data: ${sim.displayName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = if (sim.isDefaultData) {
                                        { Icon(Icons.TwoTone.Check, null, Modifier.size(14.dp)) }
                                    } else null
                                )
                            }
                        }
                    }

                    // 5G Only / 4G Only / 5G Auto buttons for active Data SIM
                    if (targetSim != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val modes = listOf(
                                NetworkModeController.NetworkMode.MODE_NR_ONLY to "5G Only",
                                NetworkModeController.NetworkMode.MODE_LTE_ONLY to "4G Only",
                                NetworkModeController.NetworkMode.MODE_5G_PREF to "5G Auto"
                            )
                            modes.forEach { (mode, label) ->
                                val isSelected = targetSim.currentMode == mode
                                val btnEnabled = hasPorterPermission && !isNetworkLoading
                                if (isSelected) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                NetworkModeController.setModeForSim(context, targetSim, mode)
                                            }
                                        },
                                        enabled = btnEnabled,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                NetworkModeController.setModeForSim(context, targetSim, mode)
                                            }
                                        },
                                        enabled = btnEnabled,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text(label, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ================= 4. CAFFEINE KEEP-AWAKE BENTO CARD =================
            item(key = "bento_caffeine") {
                BentoCardContainer(
                    isActive = isCaffeineActive,
                    onClickHeader = { CaffeineController.toggle(context) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BentoIconBox(
                            icon = Icons.TwoTone.LocalCafe,
                            isActive = isCaffeineActive
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Caffeine Keep-Awake",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isCaffeineActive) {
                                    "Screen stays awake • ${CaffeineController.formatDuration(caffeineRemaining)}"
                                } else {
                                    "Normal sleep • Preset: ${CaffeineController.formatPreset(caffeineTimeout)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCaffeineActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = isCaffeineActive,
                            onCheckedChange = { CaffeineController.toggle(context) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaffeineController.PRESETS.forEach { preset ->
                            val selected = caffeineTimeout == preset
                            val shortLabel = CaffeineController.formatShortPreset(preset)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    CaffeineController.setTimeout(context, preset)
                                    if (isCaffeineActive) {
                                        CaffeineController.start(context, preset)
                                    }
                                },
                                label = { Text(shortLabel, style = MaterialTheme.typography.labelSmall) }
                            )
                        }

                        FilterChip(
                            selected = caffeineAbortScreenOff,
                            onClick = { CaffeineController.setAbortOnScreenOff(context, !caffeineAbortScreenOff) },
                            label = { Text("Lock Off", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = if (caffeineAbortScreenOff) {
                                { Icon(Icons.TwoTone.PowerSettingsNew, null, Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }
            }

            // ================= 5. ANIMATION SPEED BENTO CARD =================
            item(key = "bento_animator") {
                BentoCardContainer(
                    isActive = windowScale != 1.0f,
                    onClickHeader = { showAnimatorSliders = !showAnimatorSliders }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BentoIconBox(
                            icon = Icons.TwoTone.Speed,
                            isActive = windowScale != 1.0f
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Animation Speed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (windowScale == 0f) "Animations Off (0.0x)" else "Current scale: ${windowScale}x",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (windowScale != 1.0f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { showAnimatorSliders = !showAnimatorSliders },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (showAnimatorSliders) "Hide" else "Sliders",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickScales = listOf(0.0f, 0.5f, 0.75f, 1.0f, 1.5f)
                        quickScales.forEach { preset ->
                            val isCurrent = (windowScale == preset)
                            val label = if (preset == 0f) "Off" else "${preset}x"
                            if (isCurrent) {
                                Button(
                                    onClick = { AnimatorController.applyPreset(context, preset) },
                                    enabled = AnimatorController.canModify(context),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { AnimatorController.applyPreset(context, preset) },
                                    enabled = AnimatorController.canModify(context),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    if (showAnimatorSliders) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Link All 3 Scales",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Switch(
                                checked = isAnimatorLinked,
                                onCheckedChange = { AnimatorController.setLinked(context, it) }
                            )
                        }
                        AnimationScaleSlider(
                            title = stringResource(R.string.animator_window_scale),
                            value = windowScale,
                            enabled = AnimatorController.canModify(context),
                            onValueChange = { AnimatorController.setWindowScale(context, it) }
                        )
                        if (!isAnimatorLinked) {
                            AnimationScaleSlider(
                                title = stringResource(R.string.animator_transition_scale),
                                value = transitionScale,
                                enabled = AnimatorController.canModify(context),
                                onValueChange = { AnimatorController.setTransitionScale(context, it) }
                            )
                            AnimationScaleSlider(
                                title = stringResource(R.string.animator_duration_scale),
                                value = animatorScale,
                                enabled = AnimatorController.canModify(context),
                                onValueChange = { AnimatorController.setAnimatorScale(context, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BentoCardContainer(
    isActive: Boolean,
    onClickHeader: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClickHeader),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun BentoIconBox(
    icon: ImageVector,
    isActive: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OtaQuickBannerCard(
    availableUpdate: UpdateManager.UpdateInfo?,
    downloadState: UpdateManager.DownloadState,
    onOpenSettings: () -> Unit,
    onInstall: () -> Unit,
    onStartDownload: (UpdateManager.UpdateInfo) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onOpenSettings),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    val title = when (downloadState) {
                        is UpdateManager.DownloadState.Downloading -> "Downloading Update..."
                        is UpdateManager.DownloadState.Installing -> "Installing Update v${downloadState.versionName}..."
                        is UpdateManager.DownloadState.ReadyToInstall -> "Update Ready: v${downloadState.versionName}"
                        is UpdateManager.DownloadState.Failed -> "Update Download Failed"
                        UpdateManager.DownloadState.Idle -> "Update Available: v${availableUpdate?.versionName.orEmpty()}"
                    }
                    val sub = when (downloadState) {
                        is UpdateManager.DownloadState.Downloading -> UpdateManager.formatDownloadStatus(downloadState)
                        is UpdateManager.DownloadState.Installing -> "Applying update via Porter / Shizuku..."
                        is UpdateManager.DownloadState.ReadyToInstall -> "Tap Install to apply update now"
                        is UpdateManager.DownloadState.Failed -> downloadState.message
                        UpdateManager.DownloadState.Idle -> availableUpdate?.releaseTitle.orEmpty()
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (sub.isNotBlank()) {
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when (downloadState) {
                    is UpdateManager.DownloadState.ReadyToInstall -> {
                        Button(
                            onClick = onInstall,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Install", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    is UpdateManager.DownloadState.Installing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                    is UpdateManager.DownloadState.Downloading -> {
                        CircularProgressIndicator(
                            progress = { downloadState.progress.toFloat() / 100f },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                    else -> {
                        if (availableUpdate != null && availableUpdate.downloadUrl != null) {
                            Button(
                                onClick = { onStartDownload(availableUpdate) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Download", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            if (downloadState is UpdateManager.DownloadState.Downloading) {
                LinearProgressIndicator(
                    progress = { downloadState.progress.toFloat() / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (downloadState is UpdateManager.DownloadState.Installing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun AnimationScaleSlider(
    title: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format("%.2fx", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.0f..2.0f,
            steps = 7, // 0, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
