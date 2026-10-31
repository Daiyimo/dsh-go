package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.*
import org.junit.Test

class SecureSettingsGrantTest {
    @Test fun prefersAnAuthorizedChannelWithoutAnotherPrompt() {
        val shizuku = ShizukuStatus(installed = true, running = true, granted = true)
        val adb = AdbStatus(paired = true, wirelessDebugging = true, connected = false)
        assertEquals(SecureSettingsGrant.GRANTED, SecureSettingsGrant.path(true, shizuku, adb))
        assertEquals(SecureSettingsGrant.SHIZUKU, SecureSettingsGrant.path(false, shizuku, adb))
        assertEquals(SecureSettingsGrant.ADB, SecureSettingsGrant.path(false, shizuku.copy(granted = false), adb))
        assertEquals(SecureSettingsGrant.SHIZUKU_PERMISSION,
            SecureSettingsGrant.path(false, shizuku.copy(granted = false), adb.copy(wirelessDebugging = false)))
    }
}
