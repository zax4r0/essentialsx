package eu.zax4r0.essentialsx.ui.screens

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.draw.alpha
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
import eu.zax4r0.essentialsx.ui.AboutDialog
import eu.zax4r0.essentialsx.ui.ChoiceDialog
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.SettingsCategory
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.ui.SettingsSwitch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import eu.zax4r0.essentialsx.utils.QuickSettingsTileManager
import eu.zax4r0.essentialsx.utils.SettingsHelper
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import eu.zax4r0.essentialsx.utils.UpdateManager
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(bottomInset: Dp = 0.dp) {
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

    // Network states
    val sims by NetworkModeController.sims.collectAsState()
    val isNetworkLoading by NetworkModeController.isLoading.collectAsState()
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
    var availableUpdate by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var updateDownloadProgress by remember { mutableStateOf<Int?>(null) }

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
            onSelect = { idx ->
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
            onSelect = { idx ->
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

    if (availableUpdate != null) {
        val update = availableUpdate!!
        AlertDialog(
            onDismissRequest = {
                if (updateDownloadProgress == null) availableUpdate = null
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

                    if (updateDownloadProgress != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { updateDownloadProgress!!.toFloat() / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Downloading: ${updateDownloadProgress}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (update.downloadUrl != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                updateDownloadProgress = 0
                                UpdateManager.downloadAndInstall(
                                    context = context,
                                    downloadUrl = update.downloadUrl,
                                    onProgress = { updateDownloadProgress = it },
                                    onError = { err ->
                                        updateDownloadProgress = null
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = updateDownloadProgress == null
                    ) {
                        Text(if (updateDownloadProgress != null) "Downloading..." else "Download & Install")
                    }
                } else {
                    Button(onClick = { openUrl(update.htmlUrl) }) {
                        Text("View on GitHub")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { availableUpdate = null },
                    enabled = updateDownloadProgress == null
                ) {
                    Text("Later")
                }
            }
        )
    }

    PorterScaffold(
        title = stringResource(R.string.app_name),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

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

            // ================= NETWORK SWITCH =================
            item {
                SettingsCategory(stringResource(R.string.network_title))
            }

            if (!hasPorterPermission) {
                item {
                    SettingsItem(
                        title = if (!isPorterRunning) "Porter is not running" else "Permission required",
                        icon = Icons.TwoTone.Warning,
                        summary = if (!isPorterRunning) "Start Porter to switch network modes" else "Tap to grant Porter / Shizuku permission",
                        onClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }

            if (sims.isEmpty()) {
                item {
                    SettingsItem(
                        title = "No SIM or eSIM detected",
                        icon = Icons.TwoTone.SignalCellularOff,
                        summary = if (hasPorterPermission) "Refreshing..." else "Grant Porter permission to query SIMs",
                        onClick = {
                            scope.launch { NetworkModeController.refresh(context) }
                        }
                    )
                }
            } else {
                items(sims.size) { index ->
                    val sim = sims[index]
                    SimNetworkCard(
                        sim = sim,
                        enabled = hasPorterPermission && !isNetworkLoading,
                        isLoading = isNetworkLoading,
                        onSelectMode = { mode ->
                            scope.launch {
                                NetworkModeController.setModeForSim(context, sim, mode)
                            }
                        }
                    )
                }
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
                SettingsCategory("About & Developer")
            }

            item {
                SettingsItem(
                    title = "Check for Updates",
                    icon = Icons.TwoTone.SystemUpdate,
                    summary = if (isCheckingUpdate) "Checking GitHub Releases..." else "OTA updates via GitHub • Current: v${UpdateManager.getCurrentVersion(context)}",
                    trailing = {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        isCheckingUpdate = true
                                        val info = UpdateManager.checkForUpdate(context)
                                        isCheckingUpdate = false
                                        if (info != null && info.isUpdateAvailable) {
                                            availableUpdate = info
                                        } else {
                                            Toast.makeText(context, "You are using the latest version (v${UpdateManager.getCurrentVersion(context)})", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Check")
                            }
                        }
                    },
                    onClick = {
                        if (!isCheckingUpdate) {
                            scope.launch {
                                isCheckingUpdate = true
                                val info = UpdateManager.checkForUpdate(context)
                                isCheckingUpdate = false
                                if (info != null && info.isUpdateAvailable) {
                                    availableUpdate = info
                                } else {
                                    Toast.makeText(context, "You are using the latest version (v${UpdateManager.getCurrentVersion(context)})", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }

            item {
                SettingsItem(
                    title = "EssentialsX v1.0.0",
                    icon = Icons.TwoTone.Info,
                    summary = "Advanced utility suite • Click for details",
                    onClick = { showAboutDialog = true }
                )
            }

            item {
                SettingsItem(
                    title = "Developer: zax4r0",
                    icon = Icons.TwoTone.Person,
                    summary = "github.com/zax4r0 • zax4r0.dev",
                    onClick = { openUrl("https://github.com/zax4r0") }
                )
            }

            item {
                SettingsItem(
                    title = "GitHub Repository",
                    icon = Icons.TwoTone.Code,
                    summary = "github.com/zax4r0/essentialsx",
                    onClick = { openUrl("https://github.com/zax4r0/essentialsx") }
                )
            }

            item {
                SettingsItem(
                    title = "License",
                    icon = Icons.TwoTone.Article,
                    summary = "MIT Open Source License",
                    onClick = { openUrl("https://opensource.org/licenses/MIT") }
                )
            }

            item {
                Spacer(Modifier.height(32.dp + bottomInset))
            }
        }
    }
}

@Composable
fun SimNetworkCard(
    sim: NetworkModeController.SimInfo,
    enabled: Boolean,
    isLoading: Boolean,
    onSelectMode: (NetworkModeController.NetworkMode) -> Unit
) {
    val cardAlpha = if (sim.isActive) 1f else 0.45f
    val cardBorderColor = if (sim.isActive && sim.isDefaultData) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (sim.isActive) 0.5f else 0.2f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(cardAlpha),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (sim.isActive) 0.45f else 0.2f),
        border = BorderStroke(1.dp, cardBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Icon + Name + Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (sim.isActive) Icons.TwoTone.SignalCellularAlt else Icons.TwoTone.SignalCellularOff,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (sim.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = sim.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Badges
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sim.isEmbedded) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                "eSIM",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                "SIM",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (sim.isActive) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        ) {
                            Text(
                                "Slot ${sim.slotIndex + 1}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (sim.isDefaultData) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text(
                                    "Data",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.outline
                        ) {
                            Text(
                                "Inactive",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle status
            val statusText = if (sim.isActive) {
                val carrier = if (sim.carrierName.isNotBlank() && !sim.carrierName.equals(sim.displayName, ignoreCase = true)) {
                    " • ${sim.carrierName}"
                } else ""
                "Current: ${sim.currentMode.displayName}$carrier"
            } else {
                if (sim.isEmbedded) "Inactive eSIM profile (not loaded in slot)" else "Inactive physical SIM"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Buttons: 5G Only, 4G Only, 5G Auto
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
                    val isSelected = sim.isActive && (sim.currentMode == mode)
                    val buttonEnabled = sim.isActive && enabled && !isLoading

                    if (isSelected) {
                        Button(
                            onClick = { onSelectMode(mode) },
                            enabled = buttonEnabled,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelectMode(mode) },
                            enabled = buttonEnabled,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
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
