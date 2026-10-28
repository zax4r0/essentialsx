package eu.zax4r0.essentialsx.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.animator.AnimatorTileService
import eu.zax4r0.essentialsx.features.caffeine.CaffeineTileService
import eu.zax4r0.essentialsx.features.network.DataSimTileService
import eu.zax4r0.essentialsx.features.network.NetworkModeTileService
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkTileService
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
fun SettingsScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isPorterRunning by ShizukuHelper.isAvailable.collectAsState()
    val hasPorterPermission by ShizukuHelper.hasPermission.collectAsState()
    val hasWriteSecure = remember(hasPorterPermission) {
        SettingsHelper.hasWriteSecureSettings(context)
    }

    var showCaffeineVariantDialog by remember { mutableStateOf(false) }
    var showNetworkVariantDialog by remember { mutableStateOf(false) }
    var showSmartNetTileDialog by remember { mutableStateOf(false) }
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
            onDismissRequest = { showUpdateDialog = false },
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
                OutlinedButton(onClick = { showUpdateDialog = false }) {
                    Text(if (isDownloading) "Hide (Keep Downloading)" else "Later")
                }
            }
        )
    }

    PorterScaffold(
        title = "Settings & About",
        titleBadge = "v${UpdateManager.getCurrentVersion(context)}"
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ================= OTA UPDATES =================
            item(key = "cat_updates") {
                SettingsCategory("Software Updates (OTA)")
            }

            item(key = "check_updates") {
                val updateSummary = when (val state = downloadState) {
                    is UpdateManager.DownloadState.Downloading -> UpdateManager.formatDownloadStatus(state)
                    is UpdateManager.DownloadState.Installing -> "Installing v${state.versionName}..."
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
                                is UpdateManager.DownloadState.Installing -> {
                                    CircularProgressIndicator(
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
                                is UpdateManager.DownloadState.Downloading -> showUpdateDialog = true
                                is UpdateManager.DownloadState.ReadyToInstall -> UpdateManager.installDownloadedUpdate(context)
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

            item(key = "beta_optin") {
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

            item(key = "whats_new") {
                SettingsItem(
                    title = "What's New in v${UpdateManager.getCurrentVersion(context)}",
                    icon = Icons.TwoTone.AutoAwesome,
                    summary = "View changelog & new features in this release",
                    onClick = { UpdateManager.openWhatsNew() }
                )
            }

            // ================= QUICK TILES =================
            item(key = "cat_tiles") {
                SettingsCategory("Quick Settings Tiles")
            }

            item(key = "tile_caffeine") {
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

            item(key = "tile_smart_net") {
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

            item(key = "tile_network") {
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

            item(key = "tile_animator") {
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

            // ================= PERMISSIONS =================
            item(key = "cat_permissions") {
                SettingsCategory("System Permissions & Privileges")
            }

            item(key = "perm_shizuku") {
                SettingsItem(
                    title = "Porter / Shizuku Service",
                    icon = if (hasPorterPermission) Icons.TwoTone.VerifiedUser else Icons.TwoTone.Warning,
                    summary = when {
                        hasPorterPermission -> "Granted & Running • Full telephony & Wi-Fi control active"
                        isPorterRunning -> "Running • Tap to grant permission"
                        else -> "Not running • Start Porter / Shizuku for privileged controls"
                    },
                    onClick = {
                        if (!hasPorterPermission) ShizukuHelper.requestPermission()
                        else ShizukuHelper.updateState()
                    }
                )
            }

            item(key = "perm_write_secure") {
                SettingsItem(
                    title = "Write Secure Settings",
                    icon = if (hasWriteSecure || hasPorterPermission) Icons.TwoTone.CheckCircle else Icons.TwoTone.Info,
                    summary = if (hasWriteSecure || hasPorterPermission) {
                        "Active • System animation scales unlocked"
                    } else {
                        "Not granted • Grant Porter permission to auto-unlock"
                    },
                    onClick = { ShizukuHelper.updateState() }
                )
            }

            // ================= ABOUT & DEVELOPER =================
            item(key = "cat_about") {
                SettingsCategory("About & Open Source")
            }

            item(key = "about_app") {
                SettingsItem(
                    title = "EssentialsX v${UpdateManager.getCurrentVersion(context)}",
                    icon = Icons.TwoTone.Info,
                    summary = "Advanced Android utility suite • Tap for details",
                    onClick = { showAboutDialog = true }
                )
            }

            item(key = "about_dev") {
                SettingsItem(
                    title = "Developer: zax4r0",
                    icon = Icons.TwoTone.Person,
                    summary = "github.com/zax4r0 • zax4r0.dev",
                    onClick = { openUrl("https://github.com/zax4r0") }
                )
            }

            item(key = "about_repo") {
                SettingsItem(
                    title = "GitHub Repository",
                    icon = Icons.TwoTone.Code,
                    summary = "github.com/zax4r0/essentialsx",
                    onClick = { openUrl("https://github.com/zax4r0/essentialsx") }
                )
            }

            item(key = "about_license") {
                SettingsItem(
                    title = "License",
                    icon = Icons.TwoTone.Article,
                    summary = "MIT Open Source License",
                    onClick = { openUrl("https://opensource.org/licenses/MIT") }
                )
            }

            item(key = "end_spacer") {
                Spacer(Modifier.height(32.dp + bottomInset))
            }
        }
    }
}
