package dev.zax4r0.droidmode.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Launch
import androidx.compose.material.icons.twotone.AcUnit
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FileDownload
import androidx.compose.material.icons.twotone.FileUpload
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material.icons.twotone.Security
import androidx.compose.material.icons.twotone.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zax4r0.droidmode.features.freezer.AppFreezerController
import dev.zax4r0.droidmode.features.freezer.AppTrackerManager
import dev.zax4r0.droidmode.features.freezer.FreezableApp
import dev.zax4r0.droidmode.features.freezer.SidecarBuilder
import dev.zax4r0.droidmode.features.widgets.AppFreezerWidgetProvider
import dev.zax4r0.droidmode.features.widgets.DroidModeWidgetsUpdater
import dev.zax4r0.droidmode.ui.PorterScaffold
import dev.zax4r0.droidmode.ui.PorterSearchBar
import dev.zax4r0.droidmode.utils.BackupRestoreManager
import dev.zax4r0.droidmode.utils.ShizukuHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var isAppPickerOpen by remember { mutableStateOf(false) }
    var autoFreezeExit by remember { mutableStateOf(AppFreezerController.isAutoFreezeOnExit(context)) }
    var autoFreezeLock by remember { mutableStateOf(AppFreezerController.isAutoFreezeOnLock(context)) }
    var hasUsageAccess by remember { mutableStateOf(AppTrackerManager.hasUsageStatsPermission(context)) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        val res = BackupRestoreManager.exportSettings(context, out)
                        if (res.isSuccess) {
                            Toast.makeText(context, "Exported ${res.getOrNull()} settings successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Export failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        val res = BackupRestoreManager.importSettings(context, input)
                        if (res.isSuccess) {
                            Toast.makeText(context, "Restored ${res.getOrNull()} settings successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Import failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
    val filteredSelected = remember(selected, query) {
        if (query.isBlank()) selected else selected.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    PorterScaffold(
        title = "App Freezer",
        onBack = onBack,
        titleBadge = "${selected.count { it.isFrozen }}/${selected.size} frozen",
        actions = {
            IconButton(
                onClick = { exportLauncher.launch("droidmode_backup_${System.currentTimeMillis()}.json") },
                enabled = selected.isNotEmpty()
            ) {
                Icon(Icons.TwoTone.FileUpload, "Export Settings Backup")
            }
            IconButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }
            ) {
                Icon(Icons.TwoTone.FileDownload, "Import Settings Backup")
            }
            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.TwoTone.MoreVert, "More Options")
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = autoFreezeExit,
                                    onCheckedChange = null
                                )
                                Text("Auto-freeze on app exit")
                            }
                        },
                        onClick = {
                            val newVal = !autoFreezeExit
                            autoFreezeExit = newVal
                            AppFreezerController.setAutoFreezeOnExit(context, newVal)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = autoFreezeLock,
                                    onCheckedChange = null
                                )
                                Text("Auto-freeze on screen lock")
                            }
                        },
                        onClick = {
                            val newVal = !autoFreezeLock
                            autoFreezeLock = newVal
                            AppFreezerController.setAutoFreezeOnLock(context, newVal)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(if (hasUsageAccess) "Usage Access: Granted ✓" else "Grant Usage Access (Shizuku)")
                        },
                        leadingIcon = {
                            Icon(Icons.TwoTone.Security, null)
                        },
                        onClick = {
                            scope.launch {
                                val granted = AppTrackerManager.ensureUsageStatsPermission(context)
                                hasUsageAccess = granted
                                Toast.makeText(
                                    context,
                                    if (granted) "Usage Access granted!" else "Failed to grant Usage Access",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = hasPermission && selected.isNotEmpty() && operation == null,
                    onClick = { scope.launch { AppFreezerController.performBulkAction(context) } }
                ) {
                    Icon(
                        if (selected.isNotEmpty() && selected.all { it.isFrozen }) Icons.TwoTone.PlayArrow else Icons.TwoTone.AcUnit,
                        null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (selected.isNotEmpty() && selected.all { it.isFrozen }) "Unfreeze all" else "Freeze all",
                        maxLines = 1
                    )
                }

                FilledTonalButton(
                    onClick = { isAppPickerOpen = true }
                ) {
                    Icon(Icons.TwoTone.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Select Apps")
                }

                FilledTonalIconButton(
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
                    },
                    enabled = hasPermission && selected.isNotEmpty() && !isBuildingSidecar
                ) {
                    if (isBuildingSidecar) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.TwoTone.Apps, "Sync Drawer Icons")
                    }
                }

                FilledTonalIconButton(
                    onClick = {
                        DroidModeWidgetsUpdater.requestPinWidget(
                            context, AppFreezerWidgetProvider::class.java, "App Freezer"
                        )
                    },
                    enabled = selected.isNotEmpty()
                ) {
                    Icon(Icons.TwoTone.Widgets, "Pin Widget")
                }
            }

            // Real-time Search
            PorterSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search ${selected.size} apps in freezer…"
            )

            // Content: Essentials-Style App Grid
            if (loading && selected.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (selected.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.TwoTone.AcUnit,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No Apps in Freezer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Tap 'Select Apps' above to choose applications to freeze.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { isAppPickerOpen = true }) {
                        Icon(Icons.TwoTone.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Add Apps")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 78.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = bottomInset + 32.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredSelected, key = { it.packageName }) { app ->
                        FreezeGridItem(
                            app = app,
                            enabled = hasPermission && operation == null,
                            onToggleFreeze = {
                                scope.launch {
                                    AppFreezerController.setFrozen(context, app.packageName, !app.isFrozen)
                                }
                            },
                            onLaunch = {
                                scope.launch {
                                    if (app.isFrozen) {
                                        Toast.makeText(context, "Unfreezing ${app.label}…", Toast.LENGTH_SHORT).show()
                                        AppFreezerController.setFrozen(context, app.packageName, false)
                                        delay(150)
                                    }
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    if (launchIntent != null) {
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(launchIntent)
                                        AppTrackerManager.startTracking(context.applicationContext, app.packageName)
                                    } else {
                                        Toast.makeText(context, "Cannot launch ${app.label}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onRemove = {
                                AppFreezerController.setSelected(context, app.packageName, false)
                            }
                        )
                    }
                }
            }
        }
    }

    // App Picker Bottom Sheet
    if (isAppPickerOpen) {
        AppSelectionBottomSheet(
            apps = apps,
            onDismiss = { isAppPickerOpen = false },
            onToggleSelected = { pkg, isSelected ->
                AppFreezerController.setSelected(context, pkg, isSelected)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FreezeGridItem(
    app: FreezableApp,
    enabled: Boolean,
    onToggleFreeze: () -> Unit,
    onLaunch: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val icon = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    val grayscaleMatrix = remember { ColorMatrix().apply { setToSaturation(0.2f) } }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (app.isFrozen) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = if (app.isFrozen) 0.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (enabled) {
                        if (app.isFrozen) onLaunch() else onToggleFreeze()
                    }
                },
                onLongClick = {
                    showMenu = true
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = app.label,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                        colorFilter = if (app.isFrozen) ColorFilter.colorMatrix(grayscaleMatrix) else null,
                        alpha = if (app.isFrozen) 0.55f else 1f
                    )
                } else {
                    Icon(
                        Icons.TwoTone.AcUnit,
                        contentDescription = app.label,
                        modifier = Modifier.size(42.dp),
                        tint = if (app.isFrozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Snowflake Badge on Frozen Apps
                if (app.isFrozen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.TwoTone.AcUnit,
                            contentDescription = "Frozen",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (app.isFrozen) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            // Context Menu
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (app.isFrozen) "Unfreeze" else "Freeze") },
                    onClick = {
                        showMenu = false
                        onToggleFreeze()
                    },
                    leadingIcon = {
                        Icon(
                            if (app.isFrozen) Icons.TwoTone.PlayArrow else Icons.TwoTone.AcUnit,
                            null
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text("Launch App") },
                    onClick = {
                        showMenu = false
                        onLaunch()
                    },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.TwoTone.Launch, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove from List") },
                    onClick = {
                        showMenu = false
                        onRemove()
                    },
                    leadingIcon = {
                        Icon(Icons.TwoTone.Delete, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text("App Info") },
                    onClick = {
                        showMenu = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    leadingIcon = {
                        Icon(Icons.TwoTone.Info, null)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AppSelectionBottomSheet(
    apps: List<FreezableApp>,
    onDismiss: () -> Unit,
    onToggleSelected: (String, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickerQuery by remember { mutableStateOf("") }
    val filteredPickerApps = remember(apps, pickerQuery) {
        if (pickerQuery.isBlank()) apps else apps.filter {
            it.label.contains(pickerQuery, ignoreCase = true) || it.packageName.contains(pickerQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Select Apps for Freezer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${apps.count { it.isSelected }} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            PorterSearchBar(
                query = pickerQuery,
                onQueryChange = { pickerQuery = it },
                placeholder = "Search installed apps…"
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(filteredPickerApps, key = { it.packageName }) { app ->
                    val context = LocalContext.current
                    val icon = remember(app.packageName) {
                        runCatching {
                            context.packageManager.getApplicationIcon(app.packageName).toBitmap(72, 72).asImageBitmap()
                        }.getOrNull()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { onToggleSelected(app.packageName, !app.isSelected) })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Checkbox(
                            checked = app.isSelected,
                            onCheckedChange = { onToggleSelected(app.packageName, it) }
                        )
                        if (icon != null) {
                            Image(icon, null, Modifier.size(38.dp))
                        } else {
                            Icon(Icons.TwoTone.AcUnit, null, Modifier.size(38.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (app.isFrozen) {
                            Icon(
                                Icons.TwoTone.CheckCircle,
                                "Frozen",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}
