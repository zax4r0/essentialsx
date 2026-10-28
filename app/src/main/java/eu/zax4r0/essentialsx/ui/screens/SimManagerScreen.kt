package eu.zax4r0.essentialsx.ui.screens

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import eu.zax4r0.essentialsx.features.network.DataSimTileService
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.network.NetworkModeController.SimFilter
import eu.zax4r0.essentialsx.features.network.NetworkModeTileService
import eu.zax4r0.essentialsx.ui.ChoiceDialog
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.PorterSearchBar
import eu.zax4r0.essentialsx.ui.SettingsCategory
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.utils.QuickSettingsTileManager
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.launch

@Composable
fun SimManagerScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isPorterRunning by ShizukuHelper.isAvailable.collectAsState()
    val hasPorterPermission by ShizukuHelper.hasPermission.collectAsState()

    val sims by NetworkModeController.sims.collectAsState()
    val isNetworkLoading by NetworkModeController.isLoading.collectAsState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(SimFilter.ALL) }
    var showNetworkVariantDialog by remember { mutableStateOf(false) }

    val filteredSims = remember(sims, searchQuery, selectedFilter) {
        NetworkModeController.filterSims(sims, searchQuery, selectedFilter)
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
        NetworkModeController.refresh(context)
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

    PorterScaffold(
        title = stringResource(R.string.network_title),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null,
        actions = {
            IconButton(
                onClick = { scope.launch { NetworkModeController.refresh(context) } },
                enabled = !isNetworkLoading
            ) {
                Icon(Icons.TwoTone.Refresh, contentDescription = "Refresh SIMs")
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
                        summary = if (!isPorterRunning) "Start Porter to switch SIM network modes" else "Tap to grant Porter / Shizuku permission",
                        onClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }

            // Search bar & Filter Chips
            item(key = "search_and_filters") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PorterSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "Search SIM, eSIM, carrier or slot..."
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SimFilter.entries.forEach { filter ->
                            val count = remember(sims, filter) {
                                NetworkModeController.filterSims(sims, "", filter).size
                            }
                            val selected = selectedFilter == filter
                            FilterChip(
                                selected = selected,
                                onClick = { selectedFilter = filter },
                                label = {
                                    Text(
                                        text = "${filter.label} ($count)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            imageVector = Icons.TwoTone.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            item(key = "category_sims") {
                val subtitle = if (searchQuery.isNotBlank() || selectedFilter != SimFilter.ALL) {
                    "Showing ${filteredSims.size} of ${sims.size} profiles"
                } else {
                    "SIM & eSIM Profiles (${sims.size})"
                }
                SettingsCategory(subtitle)
            }

            if (sims.isEmpty()) {
                item(key = "empty_sims") {
                    SettingsItem(
                        title = "No SIM or eSIM detected",
                        icon = Icons.TwoTone.SignalCellularOff,
                        summary = if (hasPorterPermission) "Refreshing..." else "Grant Porter permission to query SIM & eSIM profiles",
                        onClick = {
                            scope.launch { NetworkModeController.refresh(context) }
                        }
                    )
                }
            } else if (filteredSims.isEmpty()) {
                item(key = "no_matches") {
                    SettingsItem(
                        title = "No matching SIM or eSIM profiles",
                        icon = Icons.TwoTone.SearchOff,
                        summary = "Tap to clear search & filters",
                        onClick = {
                            searchQuery = ""
                            selectedFilter = SimFilter.ALL
                        }
                    )
                }
            } else {
                items(filteredSims, key = { it.subId }) { sim ->
                    SimNetworkCard(
                        sim = sim,
                        enabled = hasPorterPermission && !isNetworkLoading,
                        isLoading = isNetworkLoading,
                        onSelectMode = { mode ->
                            scope.launch {
                                NetworkModeController.setModeForSim(context, sim, mode)
                            }
                        },
                        onSetDefaultData = {
                            if (sim.isActive && !sim.isDefaultData && hasPorterPermission) {
                                scope.launch {
                                    NetworkModeController.setDefaultDataSubId(context, sim.subId)
                                }
                            }
                        }
                    )
                }
            }

            item(key = "category_qs_tile") {
                SettingsCategory("Quick Settings Tile")
            }

            item(key = "qs_network_tile") {
                SettingsItem(
                    title = "Network & SIM Tile",
                    icon = Icons.TwoTone.CellTower,
                    summary = "Choose variant (Network Mode Drawer vs Data SIM Switcher)",
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

            item(key = "end_spacer") {
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
    onSelectMode: (NetworkModeController.NetworkMode) -> Unit,
    onSetDefaultData: (() -> Unit)? = null
) {
    val cardAlpha = if (sim.isActive) 1f else 0.55f
    val cardBorderColor = if (sim.isActive && sim.isDefaultData) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (sim.isActive) 0.5f else 0.25f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .alpha(cardAlpha),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (sim.isActive) 0.45f else 0.22f),
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
                    imageVector = if (sim.isActive) Icons.TwoTone.SignalCellularAlt else Icons.TwoTone.SimCard,
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
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (onSetDefaultData != null && enabled) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.primary,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier.clickable(onClick = onSetDefaultData)
                            ) {
                                Text(
                                    "Use Data",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
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
                val carrier = if (sim.carrierName.isNotBlank() && !sim.carrierName.equals(sim.displayName, ignoreCase = true)) {
                    " (${sim.carrierName})"
                } else ""
                if (sim.isEmbedded) "Inactive eSIM profile$carrier" else "Inactive physical SIM$carrier"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (sim.isActive) {
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
}
