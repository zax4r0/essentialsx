package dev.zax4r0.droidmode.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AcUnit
import androidx.compose.material.icons.twotone.CellTower
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.Settings
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
            FloatingTab("Freezer", Icons.TwoTone.AcUnit),
            FloatingTab("Cellular", Icons.TwoTone.CellTower),
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

    // Graceful back handling: pressing Back on any sub-tab returns to Home first
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mainTabTransition"
        ) { tab ->
            when (tab) {
                0 -> HomeScreen(
                    bottomInset = FloatingTabBarClearance,
                    onNavigateToTab = { selectedTab = it },
                    onOpenFreezer = { selectedTab = 1 }
                )
                1 -> AppFreezerScreen(
                    bottomInset = FloatingTabBarClearance,
                    onBack = { selectedTab = 0 }
                )
                2 -> CellularScreen(
                    bottomInset = FloatingTabBarClearance
                )
                3 -> WifiPriorityScreen(
                    bottomInset = FloatingTabBarClearance
                )
                else -> SettingsScreen(
                    bottomInset = FloatingTabBarClearance
                )
            }
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
