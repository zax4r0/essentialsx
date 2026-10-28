package eu.zax4r0.essentialsx.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.SettingsCategory
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.ui.SettingsSwitch
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WifiPriorityScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shizuku / Porter states
    val isPorterRunning by ShizukuHelper.isAvailable.collectAsState()
    val hasPorterPermission by ShizukuHelper.hasPermission.collectAsState()

    // Wi-Fi states
    val networks by WifiPriorityController.networks.collectAsState()
    val inRange by WifiPriorityController.inRange.collectAsState()
    val connectedSsid by WifiPriorityController.connectedSsid.collectAsState()
    val isEnabled by WifiPriorityController.isEnabled.collectAsState()

    // Drag-to-reorder state
    val listState = rememberLazyListState()
    var draggingSsid by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var lastSwapOffset by remember { mutableStateOf<Int?>(null) }
    val latestNetworks by rememberUpdatedState(networks)

    fun endDrag() {
        draggingSsid = null
        dragOffset = 0f
        lastSwapOffset = null
    }

    // Swap the dragged row with the row its centre is over.
    fun checkSwap() {
        val key = draggingSsid ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        // The layout still shows the row where it was before the last swap: wait for it to catch up.
        if (dragged.offset == lastSwapOffset) return
        val centre = dragged.offset + dragOffset + dragged.size / 2f
        val target = visible.firstOrNull { item ->
            item.key != key &&
                latestNetworks.any { it.ssid == item.key } &&
                centre >= item.offset && centre < item.offset + item.size
        } ?: return
        val from = latestNetworks.indexOfFirst { it.ssid == key }
        val to = latestNetworks.indexOfFirst { it.ssid == target.key }
        if (from < 0 || to < 0) return
        WifiPriorityController.move(context, from, to)
        dragOffset += dragged.offset - target.offset
        lastSwapOffset = dragged.offset
    }

    LaunchedEffect(hasPorterPermission) {
        ShizukuHelper.updateState()
        while (true) {
            WifiPriorityController.refresh()
            delay(5_000)
        }
    }

    // Scroll the list while a row is held near the top or bottom edge.
    LaunchedEffect(draggingSsid) {
        while (draggingSsid != null) {
            val info = listState.layoutInfo
            val dragged = info.visibleItemsInfo.firstOrNull { it.key == draggingSsid }
            if (dragged != null) {
                val centre = dragged.offset + dragOffset + dragged.size / 2f
                val step = when {
                    centre < info.viewportStartOffset + 96f -> -18f
                    centre > info.viewportEndOffset - 96f -> 18f
                    else -> 0f
                }
                if (step != 0f) {
                    dragOffset += listState.scrollBy(step)
                    checkSwap()
                }
            }
            delay(16)
        }
    }

    PorterScaffold(
        title = stringResource(R.string.wifi_priority_title),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null,
        actions = {
            IconButton(
                onClick = { scope.launch { WifiPriorityController.refresh(scan = true) } },
                enabled = hasPorterPermission
            ) {
                Icon(Icons.TwoTone.Refresh, contentDescription = "Refresh")
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            // ================= AUTO-SWITCH =================
            item(key = "category_switch") {
                SettingsCategory(stringResource(R.string.wifi_priority_title))
            }

            if (!hasPorterPermission) {
                item(key = "porter_warning") {
                    SettingsItem(
                        title = if (!isPorterRunning) "Porter is not running" else "Permission required",
                        icon = Icons.TwoTone.Warning,
                        summary = if (!isPorterRunning) "Start Porter to switch Wi-Fi networks" else "Tap to grant Porter / Shizuku permission",
                        onClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }

            item(key = "switch") {
                SettingsSwitch(
                    title = "Auto-switch by priority",
                    icon = Icons.TwoTone.Wifi,
                    checked = isEnabled,
                    summary = if (isEnabled) {
                        "Connects to the highest saved network in range"
                    } else {
                        "Off"
                    },
                    enabled = hasPorterPermission || isEnabled,
                    onCheckedChange = { WifiPriorityController.setEnabled(context, it) }
                )
            }

            // ================= SAVED NETWORKS =================
            item(key = "category_networks") {
                SettingsCategory("Saved networks • top = highest priority")
            }

            if (networks.isEmpty()) {
                item(key = "empty") {
                    SettingsItem(
                        title = "No saved networks",
                        icon = Icons.TwoTone.WifiOff,
                        summary = if (hasPorterPermission) "Refreshing..." else "Grant Porter permission to read saved networks",
                        onClick = { scope.launch { WifiPriorityController.refresh() } }
                    )
                }
            } else {
                itemsIndexed(networks, key = { _, network -> network.ssid }) { index, network ->
                    val isDragging = draggingSsid == network.ssid
                    WifiNetworkCard(
                        rank = index + 1,
                        ssid = network.ssid,
                        isConnected = network.ssid == connectedSsid,
                        isInRange = network.ssid in inRange,
                        modifier = if (isDragging) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = dragOffset }
                        } else {
                            Modifier.animateItem()
                        },
                        dragHandle = Modifier.pointerInput(network.ssid) {
                            detectDragGestures(
                                onDragStart = {
                                    draggingSsid = network.ssid
                                    dragOffset = 0f
                                    lastSwapOffset = null
                                },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragOffset += drag.y
                                    checkSwap()
                                }
                            )
                        }
                    )
                }
            }

            item(key = "end") {
                Spacer(Modifier.height(32.dp + bottomInset))
            }
        }
    }
}

@Composable
private fun WifiNetworkCard(
    rank: Int,
    ssid: String,
    isConnected: Boolean,
    isInRange: Boolean,
    modifier: Modifier,
    dragHandle: Modifier
) {
    val borderColor = if (isConnected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val status = when {
        isConnected -> "Connected"
        isInRange -> "In range"
        else -> "Not in range"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isConnected || isInRange) Icons.TwoTone.Wifi else Icons.TwoTone.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(Modifier.weight(1f)) {
                Text(
                    text = ssid,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Priority $rank • $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = dragHandle.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.TwoTone.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
