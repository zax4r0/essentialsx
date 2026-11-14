package dev.zax4r0.droidmode.features.freezer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import dev.zax4r0.droidmode.utils.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object SidecarBuilder {
    private const val TAG = "SidecarBuilder"
    const val SIDECAR_PACKAGE = "dev.zax4r0.droidmode.sidecar"

    fun isSidecarInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SIDECAR_PACKAGE, 0) != null
    }.getOrDefault(false)

    fun sanitizePackageName(packageName: String): String =
        packageName.replace('.', '_')

    fun getAliasName(packageName: String): String =
        ".Alias_${sanitizePackageName(packageName)}"

    fun getAliasClassName(packageName: String): String =
        "$SIDECAR_PACKAGE.Alias_${sanitizePackageName(packageName)}"

    fun getFullComponent(packageName: String): String =
        "$SIDECAR_PACKAGE/${getAliasName(packageName)}"

    suspend fun buildAndInstallSidecar(context: Context, apps: List<FreezableApp>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val appContext = context.applicationContext
                val pm = appContext.packageManager

                if (!ShizukuHelper.hasPorterPermission()) {
                    return@withContext Result.failure(IllegalStateException("Shizuku permission required"))
                }

                // 1. Copy sidecar_template.apk from assets to cache
                val templateFile = File(appContext.cacheDir, "sidecar_template.apk")
                appContext.assets.open("sidecar_template.apk").use { input ->
                    FileOutputStream(templateFile).use { output -> input.copyTo(output) }
                }

                // 2. Load APK module
                val module = ApkModule.loadApkFile(templateFile)
                @Suppress("DEPRECATION")
                val manifest = module.androidManifestBlock
                val appElement = manifest.applicationElement

                // Remove existing activity-alias elements so we rebuild cleanly
                appElement.removeElementsIf { it.name == "activity-alias" }

                val tableBlock = module.tableBlock
                val pkgBlock = tableBlock.getOrCreatePackage(0x7f, SIDECAR_PACKAGE)

                var count = 0
                for (app in apps) {
                    val sanitized = sanitizePackageName(app.packageName)
                    val drawablePath = "res/drawable-nodpi/ic_$sanitized.png"

                    // Load icon and convert to greyscale
                    val iconDrawable = runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                    val greyBitmap = if (iconDrawable != null) {
                        toGrayscale(drawableToBitmap(iconDrawable))
                    } else {
                        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    }

                    val bos = ByteArrayOutputStream()
                    greyBitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    val pngBytes = bos.toByteArray()

                    // Add image to APK
                    module.add(ByteInputSource(pngBytes, drawablePath))

                    // Add drawable resource entry
                    val entry = pkgBlock.getOrCreate("", "drawable", "ic_$sanitized")
                    entry.setValueAsString(drawablePath)
                    val resId = entry.getResourceId()

                    // Add activity-alias
                    val alias = appElement.newElement("activity-alias")
                    alias.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(getAliasName(app.packageName))
                    alias.getOrCreateAndroidAttribute("targetActivity", 0x01010202).setValueAsString(".TrampolineActivity")
                    alias.getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString(app.label)
                    alias.getOrCreateAndroidAttribute("icon", 0x01010002).setValueAsResourceId(resId)
                    alias.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
                    // Initial enabled state: enabled if currently frozen, disabled if unfrozen
                    alias.getOrCreateAndroidAttribute("enabled", 0x0101000e).setValueAsBoolean(app.isFrozen)

                    val filter = alias.newElement("intent-filter")
                    val action = filter.newElement("action")
                    action.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.action.MAIN")
                    val cat = filter.newElement("category")
                    cat.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.category.LAUNCHER")

                    // meta-data for target_package
                    val meta = alias.newElement("meta-data")
                    meta.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("target_package")
                    meta.getOrCreateAndroidAttribute("value", 0x01010024).setValueAsString(app.packageName)

                    count++
                }

                // 3. Write unaligned APK
                val unalignedFile = File(appContext.cacheDir, "sidecar_unaligned.apk")
                if (unalignedFile.exists()) unalignedFile.delete()
                module.writeApk(unalignedFile)

                // 4. Sign APK with bundled keystore
                val signedFile = File(appContext.cacheDir, "sidecar_signed.apk")
                if (signedFile.exists()) signedFile.delete()

                val ks = KeyStore.getInstance("PKCS12")
                appContext.assets.open("sidecar.p12").use { ks.load(it, "droidmode".toCharArray()) }
                val privateKey = ks.getKey("sidecar", "droidmode".toCharArray()) as PrivateKey
                val cert = ks.getCertificate("sidecar") as X509Certificate

                @Suppress("DEPRECATION")
                val signerConfig = ApkSigner.SignerConfig.Builder("sidecar", privateKey, listOf(cert)).build()
                ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(unalignedFile)
                    .setOutputApk(signedFile)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .build()
                    .sign()

                // 5. Install via Shizuku stream
                val userId = android.os.Process.myUid() / 100_000
                val (installSuccess, installMessage) = ShizukuHelper.installApk(signedFile, userId)
                if (!installSuccess) {
                    return@withContext Result.failure(Exception("Shizuku install failed: $installMessage"))
                }

                // 6. Sync enabled states for each component via Sidecar IPC
                syncAllStates(appContext, apps)

                // Clean temporary APKs
                templateFile.delete()
                unalignedFile.delete()
                signedFile.delete()

                Result.success(count)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to build and install sidecar", e)
                Result.failure(e)
            }
        }

    fun syncAliasState(context: Context, packageName: String, frozen: Boolean) {
        val appContext = context.applicationContext
        val aliasClass = getAliasClassName(packageName)
        var synced = false

        // 1. Synchronous ContentProvider IPC
        try {
            val extras = android.os.Bundle().apply {
                putString("package", packageName)
                putString("alias", aliasClass)
                putBoolean("enabled", frozen)
            }
            val res = appContext.contentResolver.call(
                android.net.Uri.parse("content://$SIDECAR_PACKAGE.provider"),
                "set_state",
                null,
                extras
            )
            if (res?.getBoolean("success", false) == true) {
                synced = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ContentProvider sync failed: ${e.message}")
        }

        // 2. Explicit Broadcast
        try {
            val intent = android.content.Intent("$SIDECAR_PACKAGE.SET_STATE").apply {
                setPackage(SIDECAR_PACKAGE)
                component = android.content.ComponentName(SIDECAR_PACKAGE, "$SIDECAR_PACKAGE.SidecarReceiver")
                putExtra("package", packageName)
                putExtra("alias", aliasClass)
                putExtra("enabled", frozen)
                addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            appContext.sendBroadcast(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "sendBroadcast sync failed: ${e.message}")
        }

        // 3. Fallback: Shell am broadcast via Shizuku
        if (!synced && ShizukuHelper.hasPorterPermission()) {
            val cmd = "am broadcast -a $SIDECAR_PACKAGE.SET_STATE " +
                "-n $SIDECAR_PACKAGE/.SidecarReceiver " +
                "--es package $packageName " +
                "--es alias $aliasClass " +
                "--ez enabled $frozen " +
                "--include-stopped-packages"
            ShizukuHelper.runCommand(cmd)
        }
    }

    fun syncAllStates(context: Context, apps: List<FreezableApp>) {
        val appContext = context.applicationContext
        var synced = false
        try {
            val pkgs = apps.map { it.packageName }.toTypedArray()
            val states = apps.map { it.isFrozen }.toBooleanArray()
            val extras = android.os.Bundle().apply {
                putStringArray("packages", pkgs)
                putBooleanArray("states", states)
            }
            val res = appContext.contentResolver.call(
                android.net.Uri.parse("content://$SIDECAR_PACKAGE.provider"),
                "set_all_states",
                null,
                extras
            )
            if (res?.getBoolean("success", false) == true) {
                synced = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ContentProvider set_all_states failed: ${e.message}")
        }

        if (!synced) {
            for (app in apps) {
                syncAliasState(appContext, app.packageName, app.isFrozen)
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }
}
