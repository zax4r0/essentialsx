package eu.zax4r0.essentialsx.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val GITHUB_API_URL = "https://api.github.com/repos/zax4r0/essentialsx/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String?,
        val htmlUrl: String,
        val isUpdateAvailable: Boolean
    )

    fun getCurrentVersion(context: Context): String {
        return runCatching {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
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
            val json = JSONObject(jsonText)

            val tagName = json.optString("tag_name", "").removePrefix("v")
            val releaseTitle = json.optString("name", "v$tagName")
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/zax4r0/essentialsx/releases")

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

            UpdateInfo(
                versionName = tagName,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                htmlUrl = htmlUrl,
                isUpdateAvailable = isAvailable
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
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
                    status == HttpURLConnection.HTTP_SEE_OTHER) && redirects < 5) {
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
                withContext(Dispatchers.Main) {
                    onError("Download failed: Server returned HTTP $status")
                }
                return@withContext
            }

            val fileLength = redirectedConnection.contentLength
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            val destinationFile = File(downloadDir, "EssentialsX_update.apk")
            if (destinationFile.exists()) destinationFile.delete()

            redirectedConnection.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int
                    while (input.read(data).also { count = it } != -1) {
                        total += count
                        if (fileLength > 0) {
                            val progress = ((total * 100) / fileLength).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                        output.write(data, 0, count)
                    }
                    output.flush()
                }
            }

            // Launch package installer
            withContext(Dispatchers.Main) {
                onProgress(100)
                installApk(context, destinationFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            withContext(Dispatchers.Main) {
                onError("Download error: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    private fun installApk(context: Context, file: File) {
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

    fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val curr = currentParts.getOrElse(i) { 0 }
            val late = latestParts.getOrElse(i) { 0 }
            if (late > curr) return true
            if (late < curr) return false
        }
        return false
    }
}
