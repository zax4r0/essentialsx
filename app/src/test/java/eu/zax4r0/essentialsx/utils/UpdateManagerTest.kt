package eu.zax4r0.essentialsx.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
