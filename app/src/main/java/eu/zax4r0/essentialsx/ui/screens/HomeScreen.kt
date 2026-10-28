package eu.zax4r0.essentialsx.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import eu.zax4r0.essentialsx.features.animator.AnimatorTileService
import eu.zax4r0.essentialsx.features.caffeine.CaffeineController
import eu.zax4r0.essentialsx.features.caffeine.CaffeineTileService
import eu.zax4r0.essentialsx.features.network.DataSimTileService
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.network.NetworkModeTileService
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkTileService
import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController
import eu.zax4r0.essentialsx.ui.AboutDialog
import eu.zax4r0.essentialsx.ui.ChoiceDialog
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.SettingsCategory
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.ui.SettingsSwitch
import eu.zax4r0.essentialsx.utils.QuickSettingsTileManager
import eu.zax4r0.essentialsx.utils.SettingsHelper
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
    val hasWriteSecure = remember { mutableStateOf(SettingsHelper.hasWriteSecureSettings(context)) }

    // Caffeine states
    val isCaffeineActive by CaffeineController.isActive.collectAsState()
    val caffeineRemaining by CaffeineController.remainingSecondsFlow.collectAsState()
    val caffeineTimeout by CaffeineController.selectedTimeout.collectAsState()
    val caffeineAbortScreenOff by CaffeineController.abortOnScreenOff.collectAsState()
    var showTimeoutDialog by remember { mutableStateOf(false) }

    // Overview states from other controllers
    val sims by NetworkModeController.sims.collectAsState()
    val isSmartNetEnabled by SmartNetworkController.isEnabled.collectAsState()
    val connectedWifi by WifiPriorityController.connectedSsid.collectAsState()
    val savedWifis by WifiPriorityController.networks.collectAsState()
    var showSmartNetTileDialog by remember { mutableStateOf(false) }

    // Animator states
    val windowScale by AnimatorController.windowScale.collectAsState()
    val transitionScale by AnimatorController.transitionScale.collectAsState()
    val animatorScale by AnimatorController.animatorScale.collectAsState()
    val isAnimatorLinked by AnimatorController.isLinked.collectAsState()

    var showCaffeineVariantDialog by remember { mutableStateOf(false) }
    var showNetworkVariantDialog by remember { mutableStateOf(false) }
    var showAnimatorVariantDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val availableUpdate by UpdateManager.availableUpdate.collectAsState()
    val downloadState by UpdateManager.downloadState.collectAsState()
    val includePreReleases by UpdateManager.includePreReleases.collectAsState()

    fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "Cannot open browser: $url", Toast.LENGTH_SHORT).show()
        }
    }

    fun addTile(serviceClass: Class<*>, title: String, @DrawableRes iconRes: Int) {
        scope.launch {
            QuickSettingsTileManager.addTileToLast(context, serviceClass, title, iconRes) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(hasPorterPermission) {
        ShizukuHelper.updateState()
        hasWriteSecure.value = SettingsHelper.hasWriteSecureSettings(context)
        NetworkModeController.refresh(context)
        WifiPriorityController.refresh()
    }

    if (showTimeoutDialog) {
        val choices = CaffeineController.PRESETS.map { CaffeineController.formatPreset(it) }
        val selectedIndex = CaffeineController.PRESETS.indexOf(caffeineTimeout).coerceAtLeast(0)
        ChoiceDialog(
            title = "Select Caffeine Timeout",
            choices = choices,
            selected = selectedIndex,
            onDismiss = { showTimeoutDialog = false },
            onSelect = { idx ->
                val chosen = CaffeineController.PRESETS[idx]
                CaffeineController.setTimeout(context, chosen)
                if (isCaffeineActive) {
                    CaffeineController.start(context, chosen)
                }
            }
        )
    }

    if (showCaffeineVariantDialog) {
        val choices = listOf(
            "Caffeine Toggle (Infinite keep-awake mode)",
            "Caffeine Cycle (Cycle 1m, 5m, 10m, 30m, 1h presets)"
        )
        ChoiceDialog(
            title = "Choose Caffeine Tile Variant",
            choices = choices,
            selected = 0,
            onDismiss = { showCaffeineVariantDialog = false },
            onSelect = { _ ->
                showCaffeineVariantDialog = false
                addTile(CaffeineTileService::class.java, "Caffeine", R.drawable.ic_coffee)
            }
        )
    }

    if (showNetworkVariantDialog) {
        val choices = listOf(
            "Network Mode Drawer (5G/4G/Auto drawer selector)",
            "Data SIM Switcher (1-tap switch default mobile data SIM)"
        )
        ChoiceDialog(
            title = "Choose Network Tile Variant",
            choices = choices,
            selected = 0,
            onDismiss = { showNetworkVariantDialog = false },
            onSelect = { idx ->
                showNetworkVariantDialog = false
                val targetService = if (idx == 0) {
                    NetworkModeTileService::class.java
                } else {
                    DataSimTileService::class.java
                }
                val label = if (idx == 0) "Network Mode" else "Data SIM"
                addTile(targetService, label, R.drawable.ic_signal_cellular)
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

    if (showAnimatorVariantDialog) {
        val choices = listOf(
            "Speed Boost (Toggle 0.5x / 1.0x animations)",
            "Animations Off (Toggle 0.0x / 1.0x animations)"
        )
        ChoiceDialog(
            title = "Choose Animator Tile Variant",
            choices = choices,
            selected = 0,
            onDismiss = { showAnimatorVariantDialog = false },
            onSelect = { _ ->
                showAnimatorVariantDialog = false
                addTile(AnimatorTileService::class.java, "Animator", R.drawable.ic_speed)
            }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
            onOpenUrl = { url ->
                showAboutDialog = false
                openUrl(url)
            }
        )
    }

    if (showUpdateDialog && availableUpdate != null) {
        val update = availableUpdate!!
        val isDownloading = downloadState is UpdateManager.DownloadState.Downloading
        val isReadyToInstall = (downloadState as? UpdateManager.DownloadState.ReadyToInstall)?.versionName == update.versionName
        AlertDialog(
            onDismissRequest = {
                // Allow dismissing freely even while downloading — download continues in background!
                showUpdateDialog = false
            },
            icon = {
                Icon(
                    Icons.TwoTone.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    "Update Available: v${update.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (update.releaseTitle.isNotBlank()) {
                        Text(
                            update.releaseTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (update.releaseNotes.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = update.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    when (val state = downloadState) {
                        is UpdateManager.DownloadState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { state.progress.toFloat() / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    UpdateManager.formatDownloadStatus(state),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is UpdateManager.DownloadState.Failed -> {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            },
            confirmButton = {
                when {
                    isReadyToInstall -> {
                        Button(onClick = {
                            showUpdateDialog = false
                            UpdateManager.installDownloadedUpdate(context)
                        }) {
                            Text("Install Now")
                        }
                    }
                    update.downloadUrl != null -> {
                        Button(
                            onClick = {
                                UpdateManager.startBackgroundDownload(
                                    context = context,
                                    update = update,
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            enabled = !isDownloading
                        ) {
                            Text(if (isDownloading) "Downloading in BG..." else "Download & Install")
                        }
                    }
                    else -> {
                        Button(onClick = { openUrl(update.htmlUrl) }) {
                            Text("View on GitHub")
                        }
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showUpdateDialog = false }
                ) {
                    Text(if (isDownloading) "Hide (Keep Downloading)" else "Later")
                }
            }
        )
    }

    PorterScaffold(
        title = stringResource(R.string.app_name),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null,
        actions = {
            IconButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.TwoTone.Info, contentDescription = "About EssentialsX")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
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

            // ================= QUICK DASHBOARD HUB =================
            item(key = "dashboard_hub") {
                val activeSimCount = sims.count { it.isActive }
                val defaultSim = sims.firstOrNull { it.isActive && it.isDefaultData }
                val simSubtitle = when {
                    defaultSim != null -> "${defaultSim.displayName} • ${defaultSim.currentMode.shortName}"
                    activeSimCount > 0 -> "$activeSimCount Active • ${sims.size} Total"
                    else -> "${sims.size} Profiles"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashboardTileCard(
                            title = "SIM & eSIM",
                            subtitle = simSubtitle,
                            icon = Icons.TwoTone.SimCard,
                            isActive = activeSimCount > 0,
                            onClick = { onNavigateToTab(1) },
                            modifier = Modifier.weight(1f)
                        )
                        DashboardTileCard(
                            title = "Smart Net",
                            subtitle = if (isSmartNetEnabled) "Engine Active" else "Tap to configure",
                            icon = Icons.TwoTone.AutoAwesome,
                            isActive = isSmartNetEnabled,
                            onClick = { onNavigateToTab(2) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashboardTileCard(
                            title = "Wi-Fi Priority",
                            subtitle = connectedWifi ?: "${savedWifis.size} Saved Networks",
                            icon = Icons.TwoTone.Wifi,
                            isActive = connectedWifi != null,
                            onClick = { onNavigateToTab(3) },
                            modifier = Modifier.weight(1f)
                        )
                        DashboardTileCard(
                            title = "Caffeine",
                            subtitle = if (isCaffeineActive) {
                                "Awake (${CaffeineController.formatDuration(caffeineRemaining)})"
                            } else {
                                "Normal Sleep"
                            },
                            icon = Icons.TwoTone.LocalCafe,
                            isActive = isCaffeineActive,
                            onClick = { CaffeineController.toggle(context) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ================= CAFFEINE =================
            item {
                SettingsCategory(stringResource(R.string.caffeine_title))
            }

            item {
                SettingsSwitch(
                    title = "Caffeine Mode",
                    icon = Icons.TwoTone.LocalCafe,
                    checked = isCaffeineActive,
                    summary = if (isCaffeineActive) {
                        "Screen will stay awake • Remaining: ${CaffeineController.formatDuration(caffeineRemaining)}"
                    } else {
                        "Screen will sleep normally"
                    },
                    onCheckedChange = { CaffeineController.toggle(context) }
                )
            }

            item {
                SettingsItem(
                    title = stringResource(R.string.caffeine_timeout_title),
                    icon = Icons.TwoTone.Timer,
                    summary = "Preset: ${CaffeineController.formatPreset(caffeineTimeout)}",
                    onClick = { showTimeoutDialog = true }
                )
            }

            item {
                SettingsSwitch(
                    title = stringResource(R.string.caffeine_abort_screen_off),
                    icon = Icons.TwoTone.PowerSettingsNew,
                    checked = caffeineAbortScreenOff,
                    summary = stringResource(R.string.caffeine_abort_screen_off_summary),
                    onCheckedChange = { CaffeineController.setAbortOnScreenOff(context, it) }
                )
            }

            // ================= CUSTOM ANIMATOR =================
            item {
                SettingsCategory(stringResource(R.string.animator_title))
            }

            item {
                SettingsSwitch(
                    title = "Link All Scales",
                    icon = Icons.TwoTone.Tune,
                    checked = isAnimatorLinked,
                    summary = "Adjust all 3 animation scales simultaneously",
                    onCheckedChange = { AnimatorController.setLinked(context, it) }
                )
            }

            item {
                Text(
                    "Quick Presets",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AnimatorController.PRESET_VALUES.forEach { preset ->
                        val isCurrent = (windowScale == preset)
                        val label = if (preset == 0f) "Off" else "${preset}x"
                        Button(
                            onClick = { AnimatorController.applyPreset(context, preset) },
                            enabled = AnimatorController.canModify(context),
                            colors = if (isCurrent) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            item {
                AnimationScaleSlider(
                    title = stringResource(R.string.animator_window_scale),
                    value = windowScale,
                    enabled = AnimatorController.canModify(context),
                    onValueChange = { AnimatorController.setWindowScale(context, it) }
                )
            }

            if (!isAnimatorLinked) {
                item {
                    AnimationScaleSlider(
                        title = stringResource(R.string.animator_transition_scale),
                        value = transitionScale,
                        enabled = AnimatorController.canModify(context),
                        onValueChange = { AnimatorController.setTransitionScale(context, it) }
                    )
                }

                item {
                    AnimationScaleSlider(
                        title = stringResource(R.string.animator_duration_scale),
                        value = animatorScale,
                        enabled = AnimatorController.canModify(context),
                        onValueChange = { AnimatorController.setAnimatorScale(context, it) }
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { AnimatorController.setAllScales(context, 1.0f) },
                    enabled = AnimatorController.canModify(context),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.TwoTone.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset to Default (1.0x)")
                }
            }

            // ================= QUICK TILES =================
            item {
                SettingsCategory("Quick Settings Tiles")
            }

            item {
                SettingsItem(
                    title = "Caffeine Tile",
                    icon = Icons.TwoTone.LocalCafe,
                    summary = "Choose variant (Toggle vs Cycle) • Appends to end of QS panel",
                    trailing = {
                        FilledTonalButton(
                            onClick = { showCaffeineVariantDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Add")
                        }
                    },
                    onClick = { showCaffeineVariantDialog = true }
                )
            }

            item {
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

            item {
                SettingsItem(
                    title = "Network & SIM Tile",
                    icon = Icons.TwoTone.CellTower,
                    summary = "Choose variant (Network Drawer vs Data SIM) • Appends to end of QS panel",
                    trailing = {
                        FilledTonalButton(
                            onClick = { showNetworkVariantDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Add")
                        }
                    },
                    onClick = { showNetworkVariantDialog = true }
                )
            }

            item {
                SettingsItem(
                    title = "Animation Speed Tile",
                    icon = Icons.TwoTone.Speed,
                    summary = "Choose variant (Speed Boost vs Off) • Appends to end of QS panel",
                    trailing = {
                        FilledTonalButton(
                            onClick = { showAnimatorVariantDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Add")
                        }
                    },
                    onClick = { showAnimatorVariantDialog = true }
                )
            }

            // ================= ABOUT & DEVELOPER =================
            item {
                SettingsCategory("Updates & About")
            }

            item {
                val updateSummary = when (val state = downloadState) {
                    is UpdateManager.DownloadState.Downloading -> UpdateManager.formatDownloadStatus(state)
                    is UpdateManager.DownloadState.ReadyToInstall -> "Downloaded v${state.versionName} • Ready to install"
                    is UpdateManager.DownloadState.Failed -> state.message
                    UpdateManager.DownloadState.Idle -> {
                        if (isCheckingUpdate) {
                            "Checking GitHub Releases..."
                        } else if (availableUpdate?.isUpdateAvailable == true) {
                            "Update available: v${availableUpdate!!.versionName} • Tap to view or download"
                        } else {
                            "OTA updates via GitHub • Current: v${UpdateManager.getCurrentVersion(context)}"
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsItem(
                        title = "Check for Updates",
                        icon = Icons.TwoTone.SystemUpdate,
                        summary = updateSummary,
                        trailing = {
                            when (val state = downloadState) {
                                is UpdateManager.DownloadState.Downloading -> {
                                    CircularProgressIndicator(
                                        progress = { state.progress.toFloat() / 100f },
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                }
                                is UpdateManager.DownloadState.ReadyToInstall -> {
                                    Button(
                                        onClick = { UpdateManager.installDownloadedUpdate(context) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text("Install")
                                    }
                                }
                                else -> {
                                    if (isCheckingUpdate) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        FilledTonalButton(
                                            onClick = {
                                                if (availableUpdate?.isUpdateAvailable == true) {
                                                    showUpdateDialog = true
                                                } else {
                                                    scope.launch {
                                                        isCheckingUpdate = true
                                                        val info = UpdateManager.checkForUpdate(context)
                                                        isCheckingUpdate = false
                                                        if (info != null && info.isUpdateAvailable) {
                                                            showUpdateDialog = true
                                                        } else {
                                                            Toast.makeText(
                                                                context,
                                                                "You are using the latest version (v${UpdateManager.getCurrentVersion(context)})",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text(if (availableUpdate?.isUpdateAvailable == true) "Update" else "Check")
                                        }
                                    }
                                }
                            }
                        },
                        onClick = {
                            when (downloadState) {
                                is UpdateManager.DownloadState.Downloading -> {
                                    showUpdateDialog = true
                                }
                                is UpdateManager.DownloadState.ReadyToInstall -> {
                                    UpdateManager.installDownloadedUpdate(context)
                                }
                                else -> {
                                    if (availableUpdate?.isUpdateAvailable == true) {
                                        showUpdateDialog = true
                                    } else if (!isCheckingUpdate) {
                                        scope.launch {
                                            isCheckingUpdate = true
                                            val info = UpdateManager.checkForUpdate(context)
                                            isCheckingUpdate = false
                                            if (info != null && info.isUpdateAvailable) {
                                                showUpdateDialog = true
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "You are using the latest version (v${UpdateManager.getCurrentVersion(context)})",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    )

                    val dl = downloadState
                    if (dl is UpdateManager.DownloadState.Downloading) {
                        LinearProgressIndicator(
                            progress = { dl.progress.toFloat() / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            item {
                SettingsSwitch(
                    title = "Receive Pre-Release (Beta) Updates",
                    icon = Icons.TwoTone.Science,
                    checked = includePreReleases,
                    summary = if (includePreReleases) {
                        "Opted in • OTA checks both beta pre-releases and stable releases"
                    } else {
                        "Stable only • Toggle on to receive early beta builds via OTA"
                    },
                    onCheckedChange = { UpdateManager.setIncludePreReleases(context, it) }
                )
            }

            item {
                SettingsItem(
                    title = "What's New in v${UpdateManager.getCurrentVersion(context)}",
                    icon = Icons.TwoTone.AutoAwesome,
                    summary = "View changelog & new features in this release",
                    onClick = { UpdateManager.openWhatsNew() }
                )
            }

            item {
                SettingsItem(
                    title = "EssentialsX v${UpdateManager.getCurrentVersion(context)}",
                    icon = Icons.TwoTone.Info,
                    summary = "By zax4r0 • Tap for GitHub & license details",
                    onClick = { showAboutDialog = true }
                )
            }

            item {
                Spacer(Modifier.height(32.dp + bottomInset))
            }
        }
    }
}

@Composable
private fun DashboardTileCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val iconTint = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
