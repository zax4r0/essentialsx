package eu.zax4r0.essentialsx.ui.screens

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.animator.AnimatorController
import eu.zax4r0.essentialsx.features.animator.AnimatorTileService
import eu.zax4r0.essentialsx.ui.ChoiceDialog
import eu.zax4r0.essentialsx.ui.PorterScaffold
import eu.zax4r0.essentialsx.ui.SettingsCategory
import eu.zax4r0.essentialsx.ui.SettingsItem
import eu.zax4r0.essentialsx.ui.SettingsSwitch
import eu.zax4r0.essentialsx.utils.QuickSettingsTileManager
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.launch

@Composable
fun AnimatorScreen(bottomInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isPorterRunning by ShizukuHelper.isAvailable.collectAsState()
    val hasPorterPermission by ShizukuHelper.hasPermission.collectAsState()

    val windowScale by AnimatorController.windowScale.collectAsState()
    val transitionScale by AnimatorController.transitionScale.collectAsState()
    val animatorScale by AnimatorController.animatorScale.collectAsState()
    val isAnimatorLinked by AnimatorController.isLinked.collectAsState()

    var showAnimatorVariantDialog by remember { mutableStateOf(false) }

    fun addTile(serviceClass: Class<*>, title: String, @DrawableRes iconRes: Int) {
        scope.launch {
            QuickSettingsTileManager.addTileToLast(context, serviceClass, title, iconRes) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showAnimatorVariantDialog) {
        val choices = listOf(
            "Speed Boost (Toggle 0.5x / 1.0x animations)",
            "Animations Off (Toggle 0.0x / 1.0x animations)"
        )
        ChoiceDialog(
            title = "Choose Animator Tile Variant",
            choices = choices,
            selected = 0,
            onDismiss = { showAnimatorVariantDialog = false },
            onSelect = { _ ->
                showAnimatorVariantDialog = false
                addTile(AnimatorTileService::class.java, "Animator", R.drawable.ic_speed)
            }
        )
    }

    PorterScaffold(
        title = stringResource(R.string.animator_title),
        titleBadge = if (hasPorterPermission) "Porter Ready" else null,
        actions = {
            IconButton(
                onClick = { AnimatorController.setAllScales(context, 1.0f) },
                enabled = AnimatorController.canModify(context)
            ) {
                Icon(Icons.TwoTone.RestartAlt, contentDescription = "Reset to 1.0x")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!AnimatorController.canModify(context)) {
                item(key = "porter_warning") {
                    SettingsItem(
                        title = if (!isPorterRunning) "Porter is not running" else "Permission required",
                        icon = Icons.TwoTone.Warning,
                        summary = "Grant Porter / Shizuku or WRITE_SECURE_SETTINGS permission to adjust system animations",
                        onClick = { ShizukuHelper.requestPermission() }
                    )
                }
            }

            item(key = "category_presets") {
                SettingsCategory("Animation Speed Presets")
            }

            item(key = "presets_card") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "One-Tap Speed Presets",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = if (windowScale == 0f) "Animations Off" else "${windowScale}x Speed",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AnimatorController.PRESET_VALUES.forEach { preset ->
                                val isCurrent = (windowScale == preset)
                                val label = if (preset == 0f) "Off" else "${preset}x"
                                if (isCurrent) {
                                    Button(
                                        onClick = { AnimatorController.applyPreset(context, preset) },
                                        enabled = AnimatorController.canModify(context),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { AnimatorController.applyPreset(context, preset) },
                                        enabled = AnimatorController.canModify(context),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(label, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "category_fine_tune") {
                SettingsCategory("Fine-Tune Scales")
            }

            item(key = "link_scales") {
                SettingsSwitch(
                    title = "Link All Scales",
                    icon = Icons.TwoTone.Tune,
                    checked = isAnimatorLinked,
                    summary = "Adjust Window, Transition, and Animator duration scales together",
                    onCheckedChange = { AnimatorController.setLinked(context, it) }
                )
            }

            item(key = "sliders_card") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    ) {
                        AnimationScaleSlider(
                            title = stringResource(R.string.animator_window_scale),
                            value = windowScale,
                            enabled = AnimatorController.canModify(context),
                            onValueChange = { AnimatorController.setWindowScale(context, it) }
                        )

                        if (!isAnimatorLinked) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            AnimationScaleSlider(
                                title = stringResource(R.string.animator_transition_scale),
                                value = transitionScale,
                                enabled = AnimatorController.canModify(context),
                                onValueChange = { AnimatorController.setTransitionScale(context, it) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                            AnimationScaleSlider(
                                title = stringResource(R.string.animator_duration_scale),
                                value = animatorScale,
                                enabled = AnimatorController.canModify(context),
                                onValueChange = { AnimatorController.setAnimatorScale(context, it) }
                            )
                        }
                    }
                }
            }

            item(key = "reset_btn") {
                OutlinedButton(
                    onClick = { AnimatorController.setAllScales(context, 1.0f) },
                    enabled = AnimatorController.canModify(context),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.TwoTone.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset All Scales to Default (1.0x)")
                }
            }

            item(key = "category_qs") {
                SettingsCategory("Quick Settings Tile")
            }

            item(key = "qs_animator_tile") {
                SettingsItem(
                    title = "Animation Speed Tile",
                    icon = Icons.TwoTone.Speed,
                    summary = "Choose variant (Speed Boost 0.5x vs Off 0.0x) • Appends to QS panel",
                    trailing = {
                        FilledTonalButton(
                            onClick = { showAnimatorVariantDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Add")
                        }
                    },
                    onClick = { showAnimatorVariantDialog = true }
                )
            }

            item(key = "end_spacer") {
                Spacer(Modifier.height(32.dp + bottomInset))
            }
        }
    }
}
