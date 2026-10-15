package eu.zax4r0.essentialsx.features.caffeine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import eu.zax4r0.essentialsx.MainActivity
import eu.zax4r0.essentialsx.R

class CaffeineWakeLockService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var timeoutMinutes: Int = -1
    private var abortOnScreenOff: Boolean = true
    private var remainingSeconds: Int = -1

    private val handler = Handler(Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (timeoutMinutes == -1) {
                CaffeineController.updateActiveState(true, -1)
                updateNotification()
                return
            }
            if (remainingSeconds <= 0) {
                stopSelf()
                return
            }
            remainingSeconds--
            CaffeineController.updateActiveState(true, remainingSeconds)
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && abortOnScreenOff) {
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        timeoutMinutes = intent?.getIntExtra(EXTRA_TIMEOUT_MINUTES, 5) ?: 5
        abortOnScreenOff = intent?.getBooleanExtra(EXTRA_ABORT_SCREEN_OFF, true) ?: true

        remainingSeconds = if (timeoutMinutes == -1) -1 else timeoutMinutes * 60

        // Acquire wake lock
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "EssentialsX::CaffeineWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        CaffeineController.updateActiveState(true, remainingSeconds)

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownRunnable)
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        CaffeineController.updateActiveState(false, 0)
        CaffeineController.refreshTile(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.caffeine_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPending = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CaffeineWakeLockService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = if (remainingSeconds < 0) {
            "Screen stay awake: Infinite"
        } else {
            "Remaining: ${CaffeineController.formatDuration(remainingSeconds)}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_coffee)
            .setContentTitle(getString(R.string.caffeine_notif_title))
            .setContentText(content)
            .setContentIntent(launchPending)
            .addAction(0, getString(R.string.caffeine_notif_stop), stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "caffeine_wakelock_channel"
        const val NOTIFICATION_ID = 2001

        const val EXTRA_TIMEOUT_MINUTES = "extra_timeout_minutes"
        const val EXTRA_ABORT_SCREEN_OFF = "extra_abort_screen_off"
        const val ACTION_STOP = "action_stop_caffeine"
    }
}
