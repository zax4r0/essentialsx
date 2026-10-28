package eu.zax4r0.essentialsx.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.zax4r0.essentialsx.ui.FloatingTab
import eu.zax4r0.essentialsx.ui.FloatingTabBar
import eu.zax4r0.essentialsx.ui.FloatingTabBarClearance

@Composable
fun MainScreen() {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            FloatingTab("Home", Icons.TwoTone.Home),
            FloatingTab("Wi-Fi", Icons.TwoTone.Wifi)
        )
    }

    Box(Modifier.fillMaxSize()) {
        when (selectedTab) {
            0 -> HomeScreen(bottomInset = FloatingTabBarClearance)
            else -> WifiPriorityScreen(bottomInset = FloatingTabBarClearance)
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
