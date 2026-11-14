package dev.zax4r0.droidmode.features.freezer

import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class SidecarBuilderTest {
    @Test
    fun testLoadTemplateAndSign() {
        val templateFile = File("src/main/assets/sidecar_template.apk")
        assertTrue("Template APK must exist", templateFile.exists())

        val module = ApkModule.loadApkFile(templateFile)
        assertNotNull(module)

        val manifest = module.androidManifestBlock
        assertNotNull(manifest)
        val appElement = manifest.applicationElement
        assertNotNull(appElement)

        // Add dummy grayscale icon byte input source
        val dummyPng = byteArrayOf(1, 2, 3, 4)
        module.add(ByteInputSource(dummyPng, "res/drawable-nodpi/ic_test.png"))

        val tableBlock = module.tableBlock
        assertNotNull(tableBlock)
        val pkg = tableBlock.getOrCreatePackage(0x7f, "dev.zax4r0.droidmode.sidecar")
        assertNotNull(pkg)

        val entry = pkg.getOrCreate("", "drawable", "ic_test")
        entry.setValueAsString("res/drawable-nodpi/ic_test.png")
        val resId = entry.getResourceId()

        val alias = appElement.newElement("activity-alias")
        alias.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".Alias_test")
        alias.getOrCreateAndroidAttribute("targetActivity", 0x01010202).setValueAsString(".TrampolineActivity")
        alias.getOrCreateAndroidAttribute("label", 0x01010001).setValueAsString("Test App")
        alias.getOrCreateAndroidAttribute("icon", 0x01010002).setValueAsResourceId(resId)
        alias.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        alias.getOrCreateAndroidAttribute("enabled", 0x0101000e).setValueAsBoolean(false)

        val filter = alias.newElement("intent-filter")
        val action = filter.newElement("action")
        action.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.action.MAIN")
        val cat = filter.newElement("category")
        cat.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.intent.category.LAUNCHER")

        // Write unaligned
        val unalignedFile = File("build/tmp/test_sidecar_unaligned.apk")
        unalignedFile.parentFile?.mkdirs()
        if (unalignedFile.exists()) unalignedFile.delete()
        module.writeApk(unalignedFile)
        assertTrue(unalignedFile.exists() && unalignedFile.length() > 0)

        // Sign with sidecar.p12
        val p12File = File("src/main/assets/sidecar.p12")
        assertTrue(p12File.exists())

        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(p12File).use { ks.load(it, "droidmode".toCharArray()) }
        val privateKey = ks.getKey("sidecar", "droidmode".toCharArray()) as PrivateKey
        val cert = ks.getCertificate("sidecar") as X509Certificate

        val signerConfig = ApkSigner.SignerConfig.Builder("sidecar", privateKey, listOf(cert)).build()
        val signedFile = File("build/tmp/test_sidecar_signed.apk")
        if (signedFile.exists()) signedFile.delete()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unalignedFile)
            .setOutputApk(signedFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()

        assertTrue(signedFile.exists() && signedFile.length() > 0)
    }
}
