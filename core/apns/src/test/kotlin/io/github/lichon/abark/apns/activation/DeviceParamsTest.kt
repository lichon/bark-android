package io.github.lichon.abark.apns.activation

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceParamsTest {

    @Test
    fun `windows defaults match the documented activation path`() {
        val params = DeviceParams.windows()
        assertEquals("Windows", params.deviceClass)
        assertEquals("windows1,1", params.productType)
        assertEquals("10.6.4", params.productVersion)
        assertEquals("10.6.4", params.buildVersion)
        assertEquals("WindowSerial", params.serialNumber)
        assertEquals("Windows", params.deviceQuery)
    }

    @Test
    fun `random identity is fresh every time and printer-safe`() {
        val first = DeviceParams.random()
        val second = DeviceParams.random()

        assertEquals(12, first.serialNumber.length)
        assertNotEquals(first.serialNumber, second.serialNumber)
        assertNotEquals(first.uniqueDeviceId, second.uniqueDeviceId)
        // UUID text form, which is what Alberts sees for a Windows device.
        assertEquals(36, first.uniqueDeviceId.length)
        assertTrue(first.serialNumber.all { it.isDigit() || it in 'A'..'Z' })
    }

    @Test
    fun `random draws from the injected source so tests can be deterministic`() {
        val zero = DeviceParams.random(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        val same = DeviceParams.random(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })
        assertEquals(zero.serialNumber, same.serialNumber)
    }

    @Test
    fun `rejects blank and control-character fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceParams(uniqueDeviceId = " ", serialNumber = "S")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceParams(uniqueDeviceId = "U", serialNumber = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceParams(uniqueDeviceId = "U\u0000", serialNumber = "S")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceParams(uniqueDeviceId = "U".repeat(DeviceParams.MAX_FIELD_LENGTH + 1), serialNumber = "S")
        }
    }

    @Test
    fun `form encoding keeps unreserved characters and escapes the rest`() {
        assertEquals("Windows", encodeFormValue("Windows"))
        assertEquals("a%20b", encodeFormValue("a b"))
        assertEquals("a%2Bb%2Fc", encodeFormValue("a+b/c"))
        assertEquals("%3Cdict%3E%26%3C%2Fdict%3E", encodeFormValue("<dict>&</dict>"))
        assertEquals("%C3%A9", encodeFormValue("\u00e9"))
    }
}
