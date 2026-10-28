package eu.zax4r0.essentialsx.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import eu.zax4r0.essentialsx.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val PREFS_NAME = "update_manager_prefs"
    private const val KEY_INCLUDE_PRERELEASES = "include_prereleases"
    private const val KEY_LAST_SEEN_VERSION = "last_seen_version"

    private const val CHANNEL_ID = "essentialsx_ota_update_channel"
    private const val NOTIFICATION_ID = 2004

    private const val GITHUB_LATEST_URL = "https://api.github.com/repos/zax4r0/essentialsx/releases/latest"
    private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/zax4r0/essentialsx/releases?per_page=10"

    // Process-level scope so background downloads survive tab switches, dialog dismissal, and app backgrounding
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _includePreReleases = MutableStateFlow(false)
    val includePreReleases: StateFlow<Boolean> = _includePreReleases

    private val _showWhatsNew = MutableStateFlow(false)
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew

    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    data class WhatsNewItem(
        val badge: String,
        val title: String,
        val description: String
    )

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(
            val versionName: String,
            val progress: Int,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadState
        data class ReadyToInstall(
            val versionName: String,
            val apkFile: File
        ) : DownloadState
        data class Failed(
            val versionName: String,
            val message: String
        ) : DownloadState
    }

    data class UpdateInfo(
        val versionName: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String?,
        val htmlUrl: String,
        val isPreRelease: Boolean,
        val isUpdateAvailable: Boolean
    )

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _includePreReleases.value = prefs.getBoolean(KEY_INCLUDE_PRERELEASES, false)
        val currentVersion = getCurrentVersion(context)
        val lastSeenVersion = prefs.getString(KEY_LAST_SEEN_VERSION, null)
        if (shouldShowWhatsNew(lastSeenVersion, currentVersion)) {
            _showWhatsNew.value = true
        }
    }

    fun dismissWhatsNew(context: Context) {
        val currentVersion = getCurrentVersion(context).trim().removePrefix("v")
        _showWhatsNew.value = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_SEEN_VERSION, currentVersion).apply()
    }

    fun openWhatsNew() {
        _showWhatsNew.value = true
    }

    internal fun shouldShowWhatsNew(lastSeenVersion: String?, currentVersion: String): Boolean {
        val cleanCurr = currentVersion.trim().removePrefix("v")
        if (cleanCurr.isEmpty()) return false
        val cleanLast = lastSeenVersion?.trim()?.removePrefix("v") ?: return true
        return cleanLast != cleanCurr
    }

    fun getWhatsNewHighlights(version: String): List<WhatsNewItem> = listOf(
        WhatsNewItem(
            badge = "UI & Tabs",
            title = "6-Tab Floating Bar & Bento Quick Control Home",
            description = "Dedicated tabs for Home, SIM, Smart Net, Wi-Fi, Animator & Settings (⚙️), plus Bento Action Cards on Home for 1-tap control."
        ),
        WhatsNewItem(
            badge = "Search & Filter",
            title = "SIM / eSIM & Wi-Fi Instant Search",
            description = "Search SIMs, travel eSIMs, carriers, or saved Wi-Fi SSIDs with live filter chips (Active, eSIM, Physical, In Range)."
        ),
        WhatsNewItem(
            badge = "Wi-Fi Priority",
            title = "Immediate Wi-Fi Switch on Reorder",
            description = "Dragging a Wi-Fi card or tapping ↑ immediately switches to the new #1 priority network in range with zero delay."
        ),
        WhatsNewItem(
            badge = "OTA Engine",
            title = "Background OTA Download & Notifications",
            description = "Updates download continuously in the background with live system notification progress—switch tabs or minimize freely."
        ),
        WhatsNewItem(
            badge = "Smart Net",
            title = "5G+ / 4G+ Carrier Aggregation & Dead-Pack Guard",
            description = "Detects multi-band Carrier Aggregation (MHz bandwidth) and verifies live HTTP 204 internet before & after SIM switches."
        )
    )

    fun setIncludePreReleases(context: Context, include: Boolean) {
        _includePreReleases.value = include
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INCLUDE_PRERELEASES, include).apply()
    }

    internal fun canStartDownload(state: DownloadState): Boolean =
        state !is DownloadState.Downloading

    internal fun formatDownloadStatus(state: DownloadState.Downloading): String {
        if (state.totalBytes > 0L) {
            val downMb = state.downloadedBytes.toDouble() / (1024.0 * 1024.0)
            val totalMb = state.totalBytes.toDouble() / (1024.0 * 1024.0)
            return String.format(
                Locale.US,
                "Downloading v%s • %d%% (%.1f / %.1f MB)",
                state.versionName,
                state.progress,
                downMb,
                totalMb
            )
        }
        return "Downloading v${state.versionName} • ${state.progress}%"
    }

    fun getCurrentVersion(context: Context): String {
        return runCatching {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    suspend fun checkForUpdate(
        context: Context,
        includeBetas: Boolean = _includePreReleases.value
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (includeBetas) GITHUB_RELEASES_LIST_URL else GITHUB_LATEST_URL
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "EssentialsX-App")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub API returned response code: ${connection.responseCode}")
                return@withContext null
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = if (includeBetas) {
                val arr = JSONArray(jsonText)
                var chosen: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    if (!item.optBoolean("draft", false)) {
                        chosen = item
                        break
                    }
                }
                chosen ?: return@withContext null
            } else {
                JSONObject(jsonText)
            }

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val releaseTitle = json.optString("name", "v$tagName")
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/zax4r0/essentialsx/releases")
            val isPreRelease = json.optBoolean("prerelease", false)

            var downloadUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }

            val currentVersion = getCurrentVersion(context)
            val isAvailable = isNewerVersion(currentVersion, tagName)

            val info = UpdateInfo(
                versionName = tagName,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                htmlUrl = htmlUrl,
                isPreRelease = isPreRelease,
                isUpdateAvailable = isAvailable
            )
            if (isAvailable) {
                _availableUpdate.value = info
            }
            info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        }
    }

    /**
     * Starts an uninterrupted background download on the process-level [downloadScope].
     * Switching tabs, dismissing the update dialog, or minimizing the app will NOT stop the download.
     */
    fun startBackgroundDownload(
        context: Context,
        update: UpdateInfo,
        onError: ((String) -> Unit)? = null
    ) {
        val downloadUrl = update.downloadUrl ?: return
        if (!canStartDownload(_downloadState.value)) return

        val appContext = context.applicationContext
        _downloadState.value = DownloadState.Downloading(
            versionName = update.versionName,
            progress = 0,
            downloadedBytes = 0L,
            totalBytes = 0L
        )
        ensureNotificationChannel(appContext)
        notifyProgress(appContext, update.versionName, 0, 0L, 0L)

        downloadScope.launch {
            try {
                val url = URL(downloadUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }

                // Handle HTTP redirects (GitHub releases redirect to AWS S3)
                var redirectedUrl = url
                var redirectedConnection = connection
                var status = redirectedConnection.responseCode
                var redirects = 0
                while ((status == HttpURLConnection.HTTP_MOVED_TEMP ||
                            status == HttpURLConnection.HTTP_MOVED_PERM ||
                            status == HttpURLConnection.HTTP_SEE_OTHER) && redirects < 5
                ) {
                    val newUrl = redirectedConnection.getHeaderField("Location")
                    redirectedUrl = URL(newUrl)
                    redirectedConnection = (redirectedUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                    }
                    status = redirectedConnection.responseCode
                    redirects++
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    val msg = "Download failed: Server returned HTTP $status"
                    _downloadState.value = DownloadState.Failed(update.versionName, msg)
                    notifyFailed(appContext, update.versionName, msg)
                    withContext(Dispatchers.Main) { onError?.invoke(msg) }
                    return@launch
                }

                val fileLength = redirectedConnection.contentLength.toLong().coerceAtLeast(0L)
                val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.cacheDir
                val destinationFile = File(downloadDir, "EssentialsX_update.apk")
                if (destinationFile.exists()) destinationFile.delete()

                var lastNotifiedProgress = -1
                redirectedConnection.inputStream.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val data = ByteArray(16 * 1024)
                        var total = 0L
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            output.write(data, 0, count)
                            val progress = if (fileLength > 0L) {
                                ((total * 100L) / fileLength).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            _downloadState.value = DownloadState.Downloading(
                                versionName = update.versionName,
                                progress = progress,
                                downloadedBytes = total,
                                totalBytes = fileLength
                            )
                            if (progress != lastNotifiedProgress && (progress % 5 == 0 || progress == 100)) {
                                lastNotifiedProgress = progress
                                notifyProgress(appContext, update.versionName, progress, total, fileLength)
                            }
                        }
                        output.flush()
                    }
                }

                _downloadState.value = DownloadState.ReadyToInstall(update.versionName, destinationFile)
                notifyReadyToInstall(appContext, update.versionName, destinationFile)

                withContext(Dispatchers.Main) {
                    installApk(appContext, destinationFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background download error", e)
                val msg = "Download error: ${e.localizedMessage ?: "Unknown error"}"
                _downloadState.value = DownloadState.Failed(update.versionName, msg)
                notifyFailed(appContext, update.versionName, msg)
                withContext(Dispatchers.Main) { onError?.invoke(msg) }
            }
        }
    }

    fun installDownloadedUpdate(context: Context) {
        val ready = _downloadState.value as? DownloadState.ReadyToInstall ?: return
        if (ready.apkFile.exists()) {
            installApk(context, ready.apkFile)
        }
    }

    fun installApk(context: Context, file: File) {
        runCatching {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        }.onFailure { e ->
            Log.e(TAG, "Install launch failed", e)
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTA App Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background download progress for EssentialsX updates"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun notifyProgress(
        context: Context,
        versionName: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val statusText = formatDownloadStatus(
                DownloadState.Downloading(versionName, progress, downloadedBytes, totalBytes)
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_smart_network)
                .setContentTitle("EssentialsX Update")
                .setContentText(statusText)
                .setProgress(100, progress, totalBytes <= 0L)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyReadyToInstall(context: Context, versionName: String, file: File) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingInstall = PendingIntent.getActivity(context, 0, installIntent, pendingFlags)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_smart_network)
                .setContentTitle("Update Downloaded: v$versionName")
                .setContentText("Tap to install EssentialsX v$versionName")
                .setContentIntent(pendingInstall)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyFailed(context: Context, versionName: String, error: String) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_smart_network)
                .setContentTitle("Update v$versionName Failed")
                .setContentText(error)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * SemVer-compliant version comparison supporting pre-release suffixes (e.g. "1.1.0-beta.2" vs "1.1.0"):
     * 1. Compares core [major, minor, patch] numbers first.
     * 2. If core numbers are equal:
     *    - A stable release (no '-' suffix) is newer than a pre-release (e.g. "1.1.0" > "1.1.0-beta.2").
     *    - If both are pre-releases, compares their trailing pre-release numbers.
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        val currClean = current.trim().removePrefix("v")
        val lateClean = latest.trim().removePrefix("v")
        if (currClean == lateClean) return false

        val currBaseAndPre = currClean.split("-", limit = 2)
        val lateBaseAndPre = lateClean.split("-", limit = 2)

        val currBaseParts = currBaseAndPre[0].split(".").map { it.toIntOrNull() ?: 0 }
        val lateBaseParts = lateBaseAndPre[0].split(".").map { it.toIntOrNull() ?: 0 }

        val maxBaseLen = maxOf(currBaseParts.size, lateBaseParts.size)
        for (i in 0 until maxBaseLen) {
            val c = currBaseParts.getOrElse(i) { 0 }
            val l = lateBaseParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        // Base [major, minor, patch] are identical — check pre-release suffix
        val currPre = currBaseAndPre.getOrNull(1)
        val latePre = lateBaseAndPre.getOrNull(1)

        if (currPre != null && latePre == null) {
            // Upgrading from 1.1.0-beta.x to stable 1.1.0
            return true
        }
        if (currPre == null && latePre != null) {
            // Currently on stable 1.1.0, candidate is 1.1.0-beta.x -> not newer
            return false
        }
        if (currPre != null && latePre != null) {
            val currPreNums = Regex("""\d+""").findAll(currPre).mapNotNull { it.value.toIntOrNull() }.toList()
            val latePreNums = Regex("""\d+""").findAll(latePre).mapNotNull { it.value.toIntOrNull() }.toList()
            val maxPreLen = maxOf(currPreNums.size, latePreNums.size)
            for (i in 0 until maxPreLen) {
                val c = currPreNums.getOrElse(i) { 0 }
                val l = latePreNums.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return latePre > currPre
        }

        return false
    }
}
