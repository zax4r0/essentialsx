package eu.zax4r0.essentialsx.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManagerTest {

    @Test
    fun `stable version comparisons work as expected`() {
        assertTrue(UpdateManager.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(UpdateManager.isNewerVersion("1.1.0", "1.1.1"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.0.9"))
    }

    @Test
    fun `pre-release to newer pre-release or stable works properly`() {
        // Beta 1 -> Beta 2
        assertTrue(UpdateManager.isNewerVersion("1.1.0-beta.1", "1.1.0-beta.2"))
        assertFalse(UpdateManager.isNewerVersion("1.1.0-beta.2", "1.1.0-beta.1"))

        // Beta -> Stable of same base version (1.1.0-beta.2 -> 1.1.0 is an upgrade!)
        assertTrue(UpdateManager.isNewerVersion("1.1.0-beta.1", "1.1.0"))
        assertTrue(UpdateManager.isNewerVersion("1.1.0-beta.2", "1.1.0"))

        // Stable -> Beta of SAME base version is NOT an upgrade
        assertFalse(UpdateManager.isNewerVersion("1.1.0", "1.1.0-beta.2"))

        // Stable -> Beta of NEXT base version IS an upgrade when opted into pre-releases
        assertTrue(UpdateManager.isNewerVersion("1.1.0", "1.2.0-beta.1"))
    }

    @Test
    fun `background download state guards against duplicate concurrent downloads`() {
        assertTrue(UpdateManager.canStartDownload(UpdateManager.DownloadState.Idle))
        assertTrue(UpdateManager.canStartDownload(UpdateManager.DownloadState.Failed("1.2.0", "Network error")))
        assertTrue(UpdateManager.canStartDownload(UpdateManager.DownloadState.ReadyToInstall("1.2.0", File("/tmp/a.apk"))))
        assertFalse(
            UpdateManager.canStartDownload(
                UpdateManager.DownloadState.Downloading(
                    versionName = "1.2.0-beta.3",
                    progress = 42,
                    downloadedBytes = 2_100_000L,
                    totalBytes = 5_000_000L
                )
            )
        )
    }

    @Test
    fun `formats download progress with megabytes and percentage`() {
        val status = UpdateManager.formatDownloadStatus(
            UpdateManager.DownloadState.Downloading(
                versionName = "1.2.0-beta.3",
                progress = 50,
                downloadedBytes = 2_621_440L, // 2.5 MB
                totalBytes = 5_242_880L       // 5.0 MB
            )
        )
        assertEquals("Downloading v1.2.0-beta.3 • 50% (2.5 / 5.0 MB)", status)
    }

    @Test
    fun `shouldShowWhatsNew triggers when version changes and stays dismissed for same version`() {
        // First time seeing this version or upgraded from previous version -> show What's New
        assertTrue(UpdateManager.shouldShowWhatsNew(lastSeenVersion = null, currentVersion = "1.2.0-beta.3"))
        assertTrue(UpdateManager.shouldShowWhatsNew(lastSeenVersion = "1.2.0-beta.2", currentVersion = "1.2.0-beta.3"))
        assertTrue(UpdateManager.shouldShowWhatsNew(lastSeenVersion = "1.1.1", currentVersion = "1.2.0"))

        // Already dismissed for this exact version -> do not show again automatically
        assertFalse(UpdateManager.shouldShowWhatsNew(lastSeenVersion = "1.2.0-beta.3", currentVersion = "1.2.0-beta.3"))
        assertFalse(UpdateManager.shouldShowWhatsNew(lastSeenVersion = "v1.2.0-beta.3", currentVersion = "1.2.0-beta.3"))
    }

    @Test
    fun `getWhatsNewHighlights returns structured release highlights`() {
        val highlights = UpdateManager.getWhatsNewHighlights("1.2.0-beta.3")
        assertTrue(highlights.isNotEmpty())
        assertTrue(highlights.any { it.title.contains("Tab", ignoreCase = true) || it.title.contains("Search", ignoreCase = true) })
    }
}
