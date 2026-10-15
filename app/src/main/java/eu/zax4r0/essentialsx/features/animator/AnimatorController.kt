package eu.zax4r0.essentialsx.features.animator

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.quicksettings.TileService
import eu.zax4r0.essentialsx.utils.SettingsHelper
import eu.zax4r0.essentialsx.utils.ShizukuHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AnimatorController {

    private const val KEY_LINKED = "animator_linked_scales"

    val PRESET_VALUES = listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f)

    private val _windowScale = MutableStateFlow(1.0f)
    val windowScale: StateFlow<Float> = _windowScale

    private val _transitionScale = MutableStateFlow(1.0f)
    val transitionScale: StateFlow<Float> = _transitionScale

    private val _animatorScale = MutableStateFlow(1.0f)
    val animatorScale: StateFlow<Float> = _animatorScale

    private val _isLinked = MutableStateFlow(true)
    val isLinked: StateFlow<Boolean> = _isLinked

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("animator_prefs", Context.MODE_PRIVATE)
        _isLinked.value = prefs.getBoolean(KEY_LINKED, true)
        refreshScales(context)
    }

    fun setLinked(context: Context, linked: Boolean) {
        _isLinked.value = linked
        context.getSharedPreferences("animator_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LINKED, linked).apply()
    }

    fun refreshScales(context: Context) {
        val cr = context.contentResolver
        _windowScale.value = SettingsHelper.getGlobalFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f)
        _transitionScale.value = SettingsHelper.getGlobalFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f)
        _animatorScale.value = SettingsHelper.getGlobalFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
        refreshTile(context)
    }

    fun applyPreset(context: Context, scale: Float) {
        setAllScales(context, scale)
    }

    fun setAllScales(context: Context, scale: Float) {
        setWindowScale(context, scale)
        setTransitionScale(context, scale)
        setAnimatorScale(context, scale)
    }

    fun setWindowScale(context: Context, scale: Float) {
        _windowScale.value = scale
        writeScale(context, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
        if (_isLinked.value) {
            _transitionScale.value = scale
            writeScale(context, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
            _animatorScale.value = scale
            writeScale(context, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        }
        refreshTile(context)
    }

    fun setTransitionScale(context: Context, scale: Float) {
        _transitionScale.value = scale
        writeScale(context, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
        if (_isLinked.value) {
            _windowScale.value = scale
            writeScale(context, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            _animatorScale.value = scale
            writeScale(context, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        }
        refreshTile(context)
    }

    fun setAnimatorScale(context: Context, scale: Float) {
        _animatorScale.value = scale
        writeScale(context, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        if (_isLinked.value) {
            _windowScale.value = scale
            writeScale(context, Settings.Global.WINDOW_ANIMATION_SCALE, scale)
            _transitionScale.value = scale
            writeScale(context, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
        }
        refreshTile(context)
    }

    fun toggleQuickTile(context: Context) {
        // Toggle between Fast (0.5x) and Default (1.0x)
        val current = _windowScale.value
        val next = if (current <= 0.5f) 1.0f else 0.5f
        setAllScales(context, next)
    }

    private fun writeScale(context: Context, settingKey: String, value: Float) {
        val cr = context.contentResolver
        if (SettingsHelper.hasWriteSecureSettings(context)) {
            SettingsHelper.putGlobalFloat(cr, settingKey, value)
        } else if (ShizukuHelper.hasPorterPermission()) {
            ShizukuHelper.runCommand("settings put global $settingKey $value")
        }
    }

    fun canModify(context: Context): Boolean =
        SettingsHelper.hasWriteSecureSettings(context) || ShizukuHelper.hasPorterPermission()

    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, AnimatorTileService::class.java)
            )
        }
    }
}
