package eu.zax4r0.essentialsx.features.caffeine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CaffeineController {

    private const val PREFS_NAME = "caffeine_prefs"
    private const val KEY_TIMEOUT = "caffeine_timeout"
    private const val KEY_ABORT_SCREEN_OFF = "caffeine_abort_screen_off"

    val PRESETS = listOf(5, 10, 30, 60, -1) // -1 = Infinite

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSecondsFlow: StateFlow<Int> = _remainingSeconds

    private val _selectedTimeout = MutableStateFlow(5)
    val selectedTimeout: StateFlow<Int> = _selectedTimeout

    private val _abortOnScreenOff = MutableStateFlow(true)
    val abortOnScreenOff: StateFlow<Boolean> = _abortOnScreenOff

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _selectedTimeout.value = prefs.getInt(KEY_TIMEOUT, 5)
        _abortOnScreenOff.value = prefs.getBoolean(KEY_ABORT_SCREEN_OFF, true)
    }

    fun updateActiveState(active: Boolean, remainingSecs: Int = 0) {
        _isActive.value = active
        _remainingSeconds.value = remainingSecs
    }

    fun setAbortOnScreenOff(context: Context, abort: Boolean) {
        _abortOnScreenOff.value = abort
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ABORT_SCREEN_OFF, abort).apply()
    }

    fun setTimeout(context: Context, minutes: Int) {
        _selectedTimeout.value = minutes
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TIMEOUT, minutes).apply()
    }

    fun toggle(context: Context) {
        if (_isActive.value) {
            stop(context)
        } else {
            start(context, _selectedTimeout.value)
        }
    }

    fun cycleTimeout(context: Context) {
        if (!_isActive.value) {
            start(context, PRESETS.first())
            return
        }
        val current = _selectedTimeout.value
        val currentIndex = PRESETS.indexOf(current)
        if (currentIndex == -1 || currentIndex == PRESETS.lastIndex) {
            // Stop after cycling past the last preset
            stop(context)
        } else {
            val next = PRESETS[currentIndex + 1]
            setTimeout(context, next)
            start(context, next)
        }
    }

    fun start(context: Context, minutes: Int) {
        setTimeout(context, minutes)
        val intent = Intent(context, CaffeineWakeLockService::class.java).apply {
            putExtra(CaffeineWakeLockService.EXTRA_TIMEOUT_MINUTES, minutes)
            putExtra(CaffeineWakeLockService.EXTRA_ABORT_SCREEN_OFF, _abortOnScreenOff.value)
        }
        ContextCompat.startForegroundService(context, intent)
        refreshTile(context)
    }

    fun stop(context: Context) {
        val intent = Intent(context, CaffeineWakeLockService::class.java).apply {
            action = CaffeineWakeLockService.ACTION_STOP
        }
        context.startService(intent)
        _isActive.value = false
        _remainingSeconds.value = 0
        refreshTile(context)
    }

    fun refreshTile(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, CaffeineTileService::class.java)
            )
        }
    }

    fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds < 0) return "∞"
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    fun formatPreset(minutes: Int): String = when (minutes) {
        -1 -> "Infinite (∞)"
        60 -> "1 Hour"
        else -> "$minutes Minutes"
    }
}
