package dev.zax4r0.droidmode.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SimCard
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.zax4r0.droidmode.ui.FloatingTab
import dev.zax4r0.droidmode.ui.FloatingTabBar
import dev.zax4r0.droidmode.ui.FloatingTabBarClearance
import dev.zax4r0.droidmode.ui.WhatsNewDialog
import dev.zax4r0.droidmode.utils.UpdateManager

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val showWhatsNew by UpdateManager.showWhatsNew.collectAsState()

    val tabs = remember {
        listOf(
            FloatingTab("Home", Icons.TwoTone.Home),
            FloatingTab("SIM", Icons.TwoTone.SimCard),
            FloatingTab("Smart", Icons.TwoTone.AutoAwesome),
            FloatingTab("Wi-Fi", Icons.TwoTone.Wifi),
            FloatingTab("Settings", Icons.TwoTone.Settings)
        )
    }

    if (showWhatsNew) {
        val currentVersion = remember { UpdateManager.getCurrentVersion(context) }
        val highlights = remember(currentVersion) { UpdateManager.getWhatsNewHighlights(currentVersion) }
        WhatsNewDialog(
            versionName = currentVersion,
            items = highlights,
            onDismiss = { UpdateManager.dismissWhatsNew(context) }
        )
    }

    Box(Modifier.fillMaxSize()) {
        when (selectedTab) {
            0 -> HomeScreen(
                bottomInset = FloatingTabBarClearance,
                onNavigateToTab = { selectedTab = it }
            )
            1 -> SimManagerScreen(bottomInset = FloatingTabBarClearance)
            2 -> SmartNetworkScreen(bottomInset = FloatingTabBarClearance)
            3 -> WifiPriorityScreen(bottomInset = FloatingTabBarClearance)
            else -> SettingsScreen(bottomInset = FloatingTabBarClearance)
        }

        FloatingTabBar(
            tabs = tabs,
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )
    }
}
