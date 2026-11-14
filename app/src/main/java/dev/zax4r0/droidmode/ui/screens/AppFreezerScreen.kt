package dev.zax4r0.droidmode.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AcUnit
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.FileDownload
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zax4r0.droidmode.features.freezer.AppFreezerController
import dev.zax4r0.droidmode.features.freezer.FreezableApp
import dev.zax4r0.droidmode.features.freezer.SidecarBuilder
import dev.zax4r0.droidmode.features.widgets.AppFreezerWidgetProvider
import dev.zax4r0.droidmode.features.widgets.DroidModeWidgetsUpdater
import dev.zax4r0.droidmode.ui.PorterScaffold
import dev.zax4r0.droidmode.ui.PorterSearchBar
import dev.zax4r0.droidmode.utils.ShizukuHelper
import kotlinx.coroutines.launch

@Composable
fun AppFreezerScreen(bottomInset: Dp = 0.dp, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val apps by AppFreezerController.apps.collectAsState()
    val loading by AppFreezerController.isLoading.collectAsState()
    val operation by AppFreezerController.operation.collectAsState()
    val hasPermission by ShizukuHelper.hasPermission.collectAsState()
    var query by remember { mutableStateOf("") }
    var isBuildingSidecar by remember { mutableStateOf(false) }
    var sidecarInstalled by remember { mutableStateOf(SidecarBuilder.isSidecarInstalled(context)) }

    if (onBack != null) BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        AppFreezerController.refresh(context)
        sidecarInstalled = SidecarBuilder.isSidecarInstalled(context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    AppFreezerController.refresh(context)
                    sidecarInstalled = SidecarBuilder.isSidecarInstalled(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selected = apps.filter { it.isSelected }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    PorterScaffold(
        title = "App Freezer",
        onBack = onBack,
        titleBadge = "${selected.count { it.isFrozen }}/${selected.size} frozen"
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("True App Freezer (pm disable-user)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Completely disables selected apps with Shizuku. Apps cannot run, start services, or drain battery until unfrozen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = hasPermission && selected.isNotEmpty() && operation == null,
                            onClick = { scope.launch { AppFreezerController.performBulkAction(context) } }
                        ) {
                            Icon(Icons.TwoTone.AcUnit, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (selected.isNotEmpty() && selected.all { it.isFrozen }) "Unfreeze all" else "Freeze selected")
                        }
                        OutlinedButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                DroidModeWidgetsUpdater.requestPinWidget(
                                    context, AppFreezerWidgetProvider::class.java, "App Freezer"
                                )
                            }
                        ) { Text("Widget") }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasPermission,
                        onClick = {
                            scope.launch {
                                val count = AppFreezerController.importFromEssentials(context)
                                Toast.makeText(context, "Imported $count apps from Essentials", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.TwoTone.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Import List from Essentials")
                    }
                    if (!hasPermission) {
                        Text(
                            "Start Shizuku and grant DroidMode permission first.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("App Drawer Greyscale Icons", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (sidecarInstalled) "Sidecar Active" else "Not installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sidecarInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(
                        "Generates a Sidecar companion app with greyscale launcher icons. Tapping a greyscale icon immediately unfreezes and launches the app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasPermission && selected.isNotEmpty() && !isBuildingSidecar,
                        onClick = {
                            scope.launch {
                                isBuildingSidecar = true
                                val result = SidecarBuilder.buildAndInstallSidecar(context, selected)
                                isBuildingSidecar = false
                                sidecarInstalled = SidecarBuilder.isSidecarInstalled(context)
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Sidecar synced with ${result.getOrNull()} drawer icons!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Sidecar error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        if (isBuildingSidecar) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Building & Installing Sidecar…")
                        } else {
                            Icon(Icons.TwoTone.Apps, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (sidecarInstalled) "Sync Drawer Icons" else "Build & Install Drawer Icons")
                        }
                    }
                }
            }

            PorterSearchBar(query, { query = it }, "Search installed apps")

            if (loading && apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottomInset + 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        FreezerAppRow(
                            app = app,
                            enabled = hasPermission && operation == null,
                            onSelected = { AppFreezerController.setSelected(context, app.packageName, it) },
                            onToggleFrozen = {
                                scope.launch { AppFreezerController.setFrozen(context, app.packageName, !app.isFrozen) }
                            }
                        )
                    }
                    if (filtered.isEmpty() && !loading) {
                        item { Text("No matching launchable apps", Modifier.padding(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreezerAppRow(
    app: FreezableApp,
    enabled: Boolean,
    onSelected: (Boolean) -> Unit,
    onToggleFrozen: () -> Unit
) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().clickable { onSelected(!app.isSelected) }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(checked = app.isSelected, onCheckedChange = onSelected)
        if (icon != null) {
            Image(icon, null, Modifier.size(42.dp).alpha(if (app.isFrozen) .38f else 1f))
        } else {
            Icon(Icons.TwoTone.AcUnit, null, Modifier.size(42.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                if (app.isFrozen) "Frozen • ${app.packageName}" else app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = if (app.isFrozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (app.isSelected) {
            IconButton(onClick = onToggleFrozen, enabled = enabled) {
                Icon(
                    if (app.isFrozen) Icons.TwoTone.PlayArrow else Icons.TwoTone.AcUnit,
                    if (app.isFrozen) "Unfreeze" else "Freeze",
                    tint = if (app.isFrozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (app.isFrozen) {
            Icon(Icons.TwoTone.CheckCircle, "Frozen", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
