package eu.zax4r0.essentialsx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

data class FloatingTab(val label: String, val icon: ImageVector)

// Space a screen should leave at the bottom so its last row isn't hidden behind the bar.
val FloatingTabBarClearance = 88.dp

@Composable
fun FloatingTabBar(
    tabs: List<FloatingTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val container by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    label = "tabContainer"
                )
                val content = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(container)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 15.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = if (selected) null else tab.label,
                        modifier = Modifier.size(24.dp),
                        tint = content
                    )
                    AnimatedVisibility(
                        visible = selected,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut()
                    ) {
                        Text(
                            tab.label,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = content,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
