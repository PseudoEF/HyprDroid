package dev.hyprdroid.launcher.workspaceengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleManifestTest {
    @Test fun contractKeepsDeclaredFieldsOnly() {
        val manifest = ModuleManifest(
            id = "mod.deepseek",
            name = "DeepSeek Panel",
            version = "0.9.0",
            entrypoint = "dev.hyprdroid.launcher.module.deepseek/.Main",
            permissions = listOf("android.permission.SYSTEM_ALERT_WINDOW"),
        )
        assertEquals("mod.deepseek", manifest.id)
        assertEquals("DeepSeek Panel", manifest.name)
        assertEquals("0.9.0", manifest.version)
        assertEquals("dev.hyprdroid.launcher.module.deepseek/.Main", manifest.entrypoint)
        assertEquals(listOf("android.permission.SYSTEM_ALERT_WINDOW"), manifest.permissions)
        assertTrue(manifest.isValid())
        assertFalse(ModuleManifest(id = " ", name = "x", version = "0", entrypoint = "y").isValid())
    }
}
