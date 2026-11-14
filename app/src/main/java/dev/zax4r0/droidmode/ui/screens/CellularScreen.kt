package dev.zax4r0.droidmode.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.SimCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zax4r0.droidmode.features.network.NetworkModeController
import dev.zax4r0.droidmode.ui.PorterScaffold
import dev.zax4r0.droidmode.utils.ShizukuHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellularScreen(
    bottomInset: Dp = 0.dp,
    initialSubTab: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var subTab by rememberSaveable { mutableIntStateOf(initialSubTab) }

    val hasPorterPermission by ShizukuHelper.hasPermission.collectAsState()
    val isNetworkLoading by NetworkModeController.isLoading.collectAsState()

    PorterScaffold(
        title = "Cellular & Dual SIM",
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            PrimaryTabRow(
                selectedTabIndex = subTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = subTab == 0,
                    onClick = { subTab = 0 },
                    text = { Text("SIMs & 5G Radios", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.TwoTone.SimCard, contentDescription = null) }
                )
                Tab(
                    selected = subTab == 1,
                    onClick = { subTab = 1 },
                    text = { Text("Smart Net Engine", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.TwoTone.AutoAwesome, contentDescription = null) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = subTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "cellularTabAnim"
                ) { target ->
                    when (target) {
                        0 -> SimManagerScreen(bottomInset = bottomInset, includeScaffold = false)
                        else -> SmartNetworkScreen(bottomInset = bottomInset, includeScaffold = false)
                    }
                }
            }
        }
    }
}
