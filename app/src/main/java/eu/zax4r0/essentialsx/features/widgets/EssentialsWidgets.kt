package eu.zax4r0.essentialsx.features.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import eu.zax4r0.essentialsx.R
import eu.zax4r0.essentialsx.features.caffeine.CaffeineController
import eu.zax4r0.essentialsx.features.network.NetworkModeController
import eu.zax4r0.essentialsx.features.network.NetworkModeDrawerActivity
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkController
import eu.zax4r0.essentialsx.features.smartnet.SmartNetworkDrawerActivity
import eu.zax4r0.essentialsx.features.wifi.WifiPriorityController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class WidgetCardModel(
    val title: String,
    val subtitle: String,
    val badge: String,
    val isActive: Boolean
)

object EssentialsWidgetsUpdater {

    const val ACTION_CAFFEINE_TOGGLE = "eu.zax4r0.essentialsx.widget.ACTION_CAFFEINE_TOGGLE"
    const val ACTION_CAFFEINE_CYCLE = "eu.zax4r0.essentialsx.widget.ACTION_CAFFEINE_CYCLE"
    const val ACTION_SMARTNET_TOGGLE = "eu.zax4r0.essentialsx.widget.ACTION_SMARTNET_TOGGLE"
    const val ACTION_SMARTNET_TRAY = "eu.zax4r0.essentialsx.widget.ACTION_SMARTNET_TRAY"
    const val ACTION_NETWORK_DRAWER = "eu.zax4r0.essentialsx.widget.ACTION_NETWORK_DRAWER"
    const val ACTION_SIM_SWITCH = "eu.zax4r0.essentialsx.widget.ACTION_SIM_SWITCH"
    const val ACTION_WIFI_PRIORITY_TOGGLE = "eu.zax4r0.essentialsx.widget.ACTION_WIFI_PRIORITY_TOGGLE"
    const val ACTION_WIFI_PRIORITY_SWITCH_NOW = "eu.zax4r0.essentialsx.widget.ACTION_WIFI_PRIORITY_SWITCH_NOW"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun buildCaffeineWidgetModel(
        isActive: Boolean,
        remainingSeconds: Int,
        timeoutMinutes: Int
    ): WidgetCardModel {
        val presetShort = CaffeineController.formatShortPreset(timeoutMinutes)
        return if (isActive) {
            val remainingLabel = if (timeoutMinutes == -1 || remainingSeconds < 0) {
                "Infinite (∞) awake"
            } else {
                "${CaffeineController.formatDuration(remainingSeconds)} left"
            }
            WidgetCardModel(
                title = "Caffeine • Awake",
                subtitle = remainingLabel,
                badge = presetShort,
                isActive = true
            )
        } else {
            WidgetCardModel(
                title = "Caffeine",
                subtitle = "Sleep normal • $presetShort",
                badge = presetShort,
                isActive = false
            )
        }
    }

    fun buildWifiPriorityWidgetModel(
        isEnabled: Boolean,
        connectedSsid: String?,
        inRangeCount: Int
    ): WidgetCardModel {
        return if (isEnabled) {
            val ssidPart = connectedSsid ?: "Scanning..."
            val rangePart = if (inRangeCount > 0) " • $inRangeCount in range" else ""
            WidgetCardModel(
                title = "Wi-Fi Priority • ON",
                subtitle = "$ssidPart$rangePart",
                badge = "Switch",
                isActive = true
            )
        } else {
            WidgetCardModel(
                title = "Wi-Fi Priority",
                subtitle = connectedSsid?.let { "$it • Auto off" } ?: "Auto-switch off",
                badge = "Switch",
                isActive = false
            )
        }
    }

    fun buildNetworkSimWidgetModel(
        sim: NetworkModeController.SimInfo?,
        activeSimCount: Int
    ): WidgetCardModel {
        if (sim == null) {
            return WidgetCardModel(
                title = "Network & SIM",
                subtitle = "Tap to open 5G/4G Drawer",
                badge = "5G/4G",
                isActive = false
            )
        }
        val slotNum = if (sim.slotIndex >= 0) " (Slot ${sim.slotIndex + 1})" else ""
        val badge = if (activeSimCount > 1) "SIM ⇄" else sim.currentMode.shortName
        return WidgetCardModel(
            title = "${sim.displayName}$slotNum",
            subtitle = sim.currentMode.displayName,
            badge = badge,
            isActive = true
        )
    }

    fun buildSmartNetWidgetModel(
        isEnabled: Boolean,
        status: String
    ): WidgetCardModel {
        return WidgetCardModel(
            title = if (isEnabled) "Smart Net • ON" else "Smart Network",
            subtitle = if (isEnabled) status else "Tap to start • Tray ⚙",
            badge = "Tray",
            isActive = isEnabled
        )
    }

    fun handleAction(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        when (intent.action) {
            ACTION_CAFFEINE_TOGGLE -> {
                CaffeineController.init(appCtx)
                CaffeineController.toggle(appCtx)
                updateAll(appCtx)
            }
            ACTION_CAFFEINE_CYCLE -> {
                CaffeineController.init(appCtx)
                CaffeineController.cycleTimeout(appCtx)
                updateAll(appCtx)
            }
            ACTION_SMARTNET_TOGGLE -> {
                SmartNetworkController.init(appCtx)
                SmartNetworkController.toggle(appCtx)
                updateAll(appCtx)
            }
            ACTION_SMARTNET_TRAY -> {
                val trayIntent = Intent(appCtx, SmartNetworkDrawerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                runCatching { appCtx.startActivity(trayIntent) }
            }
            ACTION_NETWORK_DRAWER -> {
                val drawerIntent = Intent(appCtx, NetworkModeDrawerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                runCatching { appCtx.startActivity(drawerIntent) }
            }
            ACTION_SIM_SWITCH -> {
                scope.launch {
                    if (NetworkModeController.sims.value.isEmpty()) {
                        NetworkModeController.refresh(appCtx)
                    }
                    val activeCount = NetworkModeController.sims.value.count { it.isActive }
                    if (activeCount > 1) {
                        NetworkModeController.toggleDefaultDataSim(appCtx)
                    } else {
                        NetworkModeController.toggleDefaultDataSimMode(appCtx)
                    }
                    updateAll(appCtx)
                }
            }
            ACTION_WIFI_PRIORITY_TOGGLE -> {
                WifiPriorityController.init(appCtx)
                val next = !WifiPriorityController.isEnabled.value
                WifiPriorityController.setEnabled(appCtx, next)
                if (next) {
                    scope.launch {
                        WifiPriorityController.switchToHighestPriorityNow()
                        updateAll(appCtx)
                    }
                }
                updateAll(appCtx)
            }
            ACTION_WIFI_PRIORITY_SWITCH_NOW -> {
                WifiPriorityController.init(appCtx)
                scope.launch {
                    WifiPriorityController.switchToHighestPriorityNow()
                    updateAll(appCtx)
                }
            }
        }
    }

    fun updateAll(context: Context) {
        runCatching {
            val appCtx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appCtx)

            updateCaffeineWidgets(appCtx, mgr)
            updateSmartNetWidgets(appCtx, mgr)
            updateNetworkSimWidgets(appCtx, mgr)
            updateWifiPriorityWidgets(appCtx, mgr)
            updateBarWidgets(appCtx, mgr)
        }
    }

    fun updateCaffeineWidgets(context: Context, mgr: AppWidgetManager = AppWidgetManager.getInstance(context)) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, CaffeineWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val model = buildCaffeineWidgetModel(
            isActive = CaffeineController.isActive.value,
            remainingSeconds = CaffeineController.remainingSecondsFlow.value,
            timeoutMinutes = CaffeineController.selectedTimeout.value
        )
        for (id in ids) {
            val views = buildPillRemoteViews(
                context = context,
                iconRes = R.drawable.ic_coffee,
                model = model,
                rootPendingIntent = broadcastPendingIntent(context, CaffeineWidgetProvider::class.java, ACTION_CAFFEINE_TOGGLE, 101),
                badgePendingIntent = broadcastPendingIntent(context, CaffeineWidgetProvider::class.java, ACTION_CAFFEINE_CYCLE, 102)
            )
            mgr.updateAppWidget(id, views)
        }
    }

    fun updateSmartNetWidgets(context: Context, mgr: AppWidgetManager = AppWidgetManager.getInstance(context)) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, SmartNetworkWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val model = buildSmartNetWidgetModel(
            isEnabled = SmartNetworkController.isEnabled.value,
            status = SmartNetworkController.engineStatus.value
        )
        for (id in ids) {
            val views = buildPillRemoteViews(
                context = context,
                iconRes = R.drawable.ic_smart_network,
                model = model,
                rootPendingIntent = broadcastPendingIntent(context, SmartNetworkWidgetProvider::class.java, ACTION_SMARTNET_TOGGLE, 201),
                badgePendingIntent = activityPendingIntent(context, SmartNetworkDrawerActivity::class.java, 202)
            )
            mgr.updateAppWidget(id, views)
        }
    }

    fun updateNetworkSimWidgets(context: Context, mgr: AppWidgetManager = AppWidgetManager.getInstance(context)) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, NetworkSimWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val activeSims = NetworkModeController.sims.value.filter { it.isActive }
        val defaultSim = NetworkModeController.getDefaultTargetSim()
        val model = buildNetworkSimWidgetModel(defaultSim, activeSims.size)
        for (id in ids) {
            val views = buildPillRemoteViews(
                context = context,
                iconRes = R.drawable.ic_signal_cellular,
                model = model,
                rootPendingIntent = activityPendingIntent(context, NetworkModeDrawerActivity::class.java, 301),
                badgePendingIntent = broadcastPendingIntent(context, NetworkSimWidgetProvider::class.java, ACTION_SIM_SWITCH, 302)
            )
            mgr.updateAppWidget(id, views)
        }
    }

    fun updateWifiPriorityWidgets(context: Context, mgr: AppWidgetManager = AppWidgetManager.getInstance(context)) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, WifiPriorityWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val model = buildWifiPriorityWidgetModel(
            isEnabled = WifiPriorityController.isEnabled.value,
            connectedSsid = WifiPriorityController.connectedSsid.value,
            inRangeCount = WifiPriorityController.inRange.value.size
        )
        for (id in ids) {
            val views = buildPillRemoteViews(
                context = context,
                iconRes = R.drawable.ic_wifi,
                model = model,
                rootPendingIntent = broadcastPendingIntent(context, WifiPriorityWidgetProvider::class.java, ACTION_WIFI_PRIORITY_TOGGLE, 401),
                badgePendingIntent = broadcastPendingIntent(context, WifiPriorityWidgetProvider::class.java, ACTION_WIFI_PRIORITY_SWITCH_NOW, 402)
            )
            mgr.updateAppWidget(id, views)
        }
    }

    fun updateBarWidgets(context: Context, mgr: AppWidgetManager = AppWidgetManager.getInstance(context)) {
        val ids = mgr.getAppWidgetIds(ComponentName(context, EssentialsBarWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val caffActive = CaffeineController.isActive.value
        val caffSub = if (caffActive) {
            val rem = CaffeineController.remainingSecondsFlow.value
            if (CaffeineController.selectedTimeout.value == -1 || rem < 0) "∞ Awake"
            else CaffeineController.formatDuration(rem)
        } else {
            "Off (${CaffeineController.formatShortPreset(CaffeineController.selectedTimeout.value)})"
        }

        val smartActive = SmartNetworkController.isEnabled.value
        val smartSub = if (smartActive) "Active" else "Off"

        val defaultSim = NetworkModeController.getDefaultTargetSim()
        val netLabel = defaultSim?.displayName ?: "Network"
        val netSub = defaultSim?.currentMode?.shortName ?: "5G/4G"

        val wifiActive = WifiPriorityController.isEnabled.value
        val connectedWifi = WifiPriorityController.connectedSsid.value
        val wifiSub = when {
            wifiActive && !connectedWifi.isNullOrBlank() -> connectedWifi
            wifiActive -> "Active"
            else -> "Off"
        }

        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_control_bar)

            // 1. Caffeine
            views.setInt(
                R.id.bar_btn_caffeine,
                "setBackgroundResource",
                if (caffActive) R.drawable.bg_widget_chip_active else R.drawable.bg_widget_chip
            )
            views.setTextViewText(R.id.bar_sub_caffeine, caffSub)
            views.setOnClickPendingIntent(
                R.id.bar_btn_caffeine,
                broadcastPendingIntent(context, EssentialsBarWidgetProvider::class.java, ACTION_CAFFEINE_TOGGLE, 501)
            )

            // 2. Smart Network
            views.setInt(
                R.id.bar_btn_smartnet,
                "setBackgroundResource",
                if (smartActive) R.drawable.bg_widget_chip_active else R.drawable.bg_widget_chip
            )
            views.setTextViewText(R.id.bar_sub_smartnet, smartSub)
            views.setOnClickPendingIntent(
                R.id.bar_btn_smartnet,
                broadcastPendingIntent(context, EssentialsBarWidgetProvider::class.java, ACTION_SMARTNET_TOGGLE, 502)
            )

            // 3. Network & SIM (opens Network Mode Drawer)
            views.setInt(
                R.id.bar_btn_network,
                "setBackgroundResource",
                if (defaultSim != null) R.drawable.bg_widget_chip_active else R.drawable.bg_widget_chip
            )
            views.setTextViewText(R.id.bar_label_network, netLabel)
            views.setTextViewText(R.id.bar_sub_network, netSub)
            views.setOnClickPendingIntent(
                R.id.bar_btn_network,
                activityPendingIntent(context, NetworkModeDrawerActivity::class.java, 503)
            )

            // 4. Wi-Fi Priority
            views.setInt(
                R.id.bar_btn_wifi,
                "setBackgroundResource",
                if (wifiActive) R.drawable.bg_widget_chip_active else R.drawable.bg_widget_chip
            )
            views.setTextViewText(R.id.bar_sub_wifi, wifiSub)
            views.setOnClickPendingIntent(
                R.id.bar_btn_wifi,
                broadcastPendingIntent(context, EssentialsBarWidgetProvider::class.java, ACTION_WIFI_PRIORITY_TOGGLE, 504)
            )

            mgr.updateAppWidget(id, views)
        }
    }

    private fun buildPillRemoteViews(
        context: Context,
        iconRes: Int,
        model: WidgetCardModel,
        rootPendingIntent: PendingIntent,
        badgePendingIntent: PendingIntent
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_pill_card)
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (model.isActive) R.drawable.bg_widget_active else R.drawable.bg_widget_surface
        )
        views.setImageViewResource(R.id.widget_icon, iconRes)
        views.setTextViewText(R.id.widget_title, model.title)
        views.setTextViewText(R.id.widget_subtitle, model.subtitle)
        if (model.badge.isNotBlank()) {
            views.setViewVisibility(R.id.widget_badge, View.VISIBLE)
            views.setTextViewText(R.id.widget_badge, model.badge)
        } else {
            views.setViewVisibility(R.id.widget_badge, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widget_root, rootPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_badge, badgePendingIntent)
        return views
    }

    private fun broadcastPendingIntent(
        context: Context,
        receiverClass: Class<out AppWidgetProvider>,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, receiverClass).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun activityPendingIntent(
        context: Context,
        activityClass: Class<*>,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, activityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, requestCode, intent, piFlags)
    }

    fun requestPinWidget(
        context: Context,
        providerClass: Class<out AppWidgetProvider>,
        label: String
    ) {
        val mgr = AppWidgetManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.isRequestPinAppWidgetSupported) {
            val provider = ComponentName(context, providerClass)
            val pinned = mgr.requestPinAppWidget(provider, null, null)
            if (!pinned) {
                Toast.makeText(
                    context,
                    "Long-press your Home Screen → Widgets → EssentialsX to add $label",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(
                context,
                "Long-press your Home Screen → Widgets → EssentialsX to add $label",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

abstract class BaseEssentialsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        EssentialsWidgetsUpdater.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action?.startsWith("eu.zax4r0.essentialsx.widget.") == true) {
            EssentialsWidgetsUpdater.handleAction(context, intent)
        }
    }
}

class CaffeineWidgetProvider : BaseEssentialsWidgetProvider()
class SmartNetworkWidgetProvider : BaseEssentialsWidgetProvider()
class NetworkSimWidgetProvider : BaseEssentialsWidgetProvider()
class WifiPriorityWidgetProvider : BaseEssentialsWidgetProvider()
class EssentialsBarWidgetProvider : BaseEssentialsWidgetProvider()
