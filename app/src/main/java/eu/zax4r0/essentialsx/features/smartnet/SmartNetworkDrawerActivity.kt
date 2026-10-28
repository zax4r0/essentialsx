package eu.zax4r0.essentialsx.features.smartnet

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.CellTower
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.SimCard
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.zax4r0.essentialsx.features.network.DataSimTileService
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.network.NetworkModeDrawerActivity
import eu.zax4r0.essentialsx.features.network.NetworkModeTileService
import eu.zax4r0.essentialsx.ui.EssentialsTheme
import kotlinx.coroutines.launch

class SmartNetworkDrawerActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If user long-pressed the manual NetworkModeTileService or DataSimTileService,
        // route to NetworkModeDrawerActivity if they prefer, or show SmartNetworkDrawerContent
        val clickedComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)
        }

        if (clickedComponent?.className == NetworkModeTileService::class.java.name ||
            clickedComponent?.className == DataSimTileService::class.java.name
        ) {
            startActivity(Intent(this, NetworkModeDrawerActivity::class.java))
            finish()
            return
        }

        window.setBackgroundDrawableResource(android.R.color.transparent)
        enableEdgeToEdge()

        setContent {
            EssentialsTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    NetworkModeController.refresh(context)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    ModalBottomSheet(
                        onDismissRequest = { finish() },
                        sheetState = sheetState,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    ) {
                        SmartNetworkDrawerContent(
                            onDismiss = {
                                scope.launch {
                                    sheetState.hide()
                                    finish()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmartNetworkDrawerContent(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isEnabled by SmartNetworkController.isEnabled.collectAsState()
    val autoWifiData by SmartNetworkController.autoWifiDataEnabled.collectAsState()
    val auto5g4g by SmartNetworkController.auto5g4gEnabled.collectAsState()
    val autoSimSwitch by SmartNetworkController.autoSimSwitchEnabled.collectAsState()
    val sensitivity by SmartNetworkController.sensitivityPreset.collectAsState()
    val status by SmartNetworkController.engineStatus.collectAsState()
    val wifiSnap by SmartNetworkController.wifiSnapshot.collectAsState()
    val simSnaps by SmartNetworkController.simSnapshots.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = "Smart Network Tray",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onDismiss) {
                Icon(Icons.TwoTone.Close, contentDescription = "Close")
            }
        }

        // Master Engine Switch Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            border = BorderStroke(
                1.dp,
                if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Smart Network Engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isEnabled) "Running in low-power background mode" else "Off • Tap to enable auto-switching",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { SmartNetworkController.setEnabled(context, it) }
                )
            }
        }

        // Live Quality Pills (Wi-Fi + SIMs)
        if (wifiSnap != null || simSnaps.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                wifiSnap?.let { wifi ->
                    QualityPillCard(
                        modifier = Modifier.weight(1f),
                        title = "Wi-Fi",
                        subtitle = "${wifi.rssiDbm} dBm • ${wifi.linkSpeedMbps}M",
                        score = wifi.emaScore,
                        grade = wifi.grade
                    )
                }
                simSnaps.values.forEach { sim ->
                    QualityPillCard(
                        modifier = Modifier.weight(1f),
                        title = "${sim.displayName} (${sim.ratLabel})",
                        subtitle = "SINR ${sim.sinrDb ?: "?"}dB • RSRQ ${sim.rsrqDb ?: "?"}dB",
                        score = sim.emaScore,
                        grade = sim.grade
                    )
                }
            }
        }

        HorizontalDivider()

        // Individual Feature Toggles
        TraySwitchRow(
            icon = Icons.TwoTone.Wifi,
            title = "Smart Switch Wi-Fi & Mobile Data",
            subtitle = "Prefer Wi-Fi when healthy; auto-switch to Mobile Data when Wi-Fi is weak or dead",
            checked = autoWifiData,
            onCheckedChange = { SmartNetworkController.setAutoWifiDataEnabled(context, it) }
        )

        TraySwitchRow(
            icon = Icons.TwoTone.CellTower,
            title = "Smart 5G / 4G Quality Switch",
            subtitle = "Drop congested 5G to 4G Only; auto-recover to 5G when signal clears",
            checked = auto5g4g,
            onCheckedChange = { SmartNetworkController.setAuto5g4gEnabled(context, it) }
        )

        TraySwitchRow(
            icon = Icons.TwoTone.SimCard,
            title = "Smart Dual-SIM Data Switch",
            subtitle = "Switch default data to the SIM with higher SINR/RSRQ quality",
            checked = autoSimSwitch,
            onCheckedChange = { SmartNetworkController.setAutoSimSwitchEnabled(context, it) }
        )

        // Sensitivity Preset Row
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Engine Sensitivity",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    SmartNetworkController.SensitivityPreset.CONSERVATIVE to "Saver",
                    SmartNetworkController.SensitivityPreset.BALANCED to "Balanced",
                    SmartNetworkController.SensitivityPreset.AGGRESSIVE to "Fast"
                )
                presets.forEach { (preset, label) ->
                    val selected = (sensitivity == preset)
                    Button(
                        onClick = { SmartNetworkController.setSensitivityPreset(context, preset) },
                        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityPillCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    score: Int,
    grade: String
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "$score/100",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "$grade • $subtitle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TraySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
