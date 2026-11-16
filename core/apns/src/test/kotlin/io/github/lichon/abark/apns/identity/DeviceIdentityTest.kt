package io.github.lichon.abark.apns.identity

import io.github.lichon.abark.apns.activation.DeviceKey
import io.github.lichon.abark.apns.activation.DeviceParams
import io.github.lichon.abark.apns.activation.TestCertificates
import java.io.File
import java.util.Date
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceIdentityTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun record(nowMillis: Long = 1_700_000_000_000): DeviceIdentityRecord {
        val keyPair = DeviceKey.generate()
        return DeviceIdentityRecord.fromActivatedDevice(
            certificatePem = TestCertificates.selfSignedPem(keyPair, days = 30),
            params = DeviceParams.windows(uniqueDeviceId = "UDID", serialNumber = "SERIAL"),
            keyPair = keyPair,
            nowMillis = nowMillis,
        )
    }

    @Test
    fun `record exposes the live objects the courier needs`() {
        // The certificate is issued "now", so the record has to be stamped with the same clock to compare validity.
        val record = record(nowMillis = System.currentTimeMillis())
        assertEquals("Windows", record.params().deviceClass)
        assertEquals("UDID", record.params().uniqueDeviceId)
        assertEquals(1024, DeviceKey.modulusBits(record.keyPair().private))
        assertTrue(record.credentials().certificateDer.isNotEmpty())
        assertFalse(record.isCertificateExpired())
        val validFor = record.certificate.notAfter.time - record.createdAtMillis
        assertTrue("certificate should be valid for about 30 days, was ${validFor}ms", kotlin.math.abs(validFor - 30L * 24 * 3600_000) < 5 * 60_000)
    }

    @Test
    fun `codec round-trips every field`() {
        val record = record()
        val decoded = IdentityCodec.decode(IdentityCodec.encode(record))

        assertEquals(record.copy(createdAtMillis = decoded.createdAtMillis), decoded.copy(createdAtMillis = record.createdAtMillis))
        assertArrayEquals(record.certificateDer, decoded.certificateDer)
        assertArrayEquals(record.keyPair().private.encoded, decoded.keyPair().private.encoded)
    }

    @Test
    fun `tokens are updated without losing the identity`() {
        val record = record()
        val updated = record.withTokens(baseToken = ByteArray(32) { 1 }, scopedToken = ByteArray(32) { 2 })

        assertArrayEquals(ByteArray(32) { 1 }, updated.baseToken)
        assertArrayEquals(ByteArray(32) { 2 }, updated.scopedToken)
        assertEquals(record.certificatePem, updated.certificatePem)
        assertEquals(record.privateKeyPem, updated.privateKeyPem)

        // Passing null keeps the previous token (a reconnect that reuses the cached base token).
        assertArrayEquals(ByteArray(32) { 2 }, updated.withTokens(null, null).scopedToken)
    }

    @Test
    fun `expired certificate is detected`() {
        val keyPair = DeviceKey.generate()
        val expired = DeviceIdentityRecord.fromActivatedDevice(
            certificatePem = TestCertificates.selfSignedPem(keyPair, days = 1),
            params = DeviceParams.windows(),
            keyPair = keyPair,
        )
        assertFalse(expired.isCertificateExpired())
        assertTrue(expired.isCertificateExpired(nowMillis = System.currentTimeMillis() + 3 * 24 * 3600_000))
    }

    @Test
    fun `decode rejects newer versions and half-written identities`() {
        assertThrows(IdentityFormatException::class.java) {
            IdentityCodec.decode(IdentityCodec.encode(record()).replace("\"version\": 1", "\"version\": 99"))
        }
        assertThrows(IdentityFormatException::class.java) { IdentityCodec.decode("{}") }
        assertThrows(IdentityFormatException::class.java) { IdentityCodec.decode("not json") }
        assertThrows(IdentityFormatException::class.java) {
            IdentityCodec.decode(IdentityCodec.encode(record()).replace(Regex("\"privateKeyPem\": \"[^\"]*\""), "\"privateKeyPem\": \"\""))
        }
    }

    @Test
    fun `file store saves, reloads and clears`() {
        val file = File(temporaryFolder.newFolder("identity"), "device.json")
        val store = FileIdentityStore(file)

        assertNull(store.load())
        val record = record()
        store.save(record)
        assertTrue(file.isFile)

        val reloaded = store.load()!!
        assertArrayEquals(record.keyPair().private.encoded, reloaded.keyPair().private.encoded)
        assertEquals(record.uniqueDeviceId, reloaded.uniqueDeviceId)

        store.save(record.withTokens(ByteArray(32) { 9 }, null))
        assertArrayEquals(ByteArray(32) { 9 }, store.load()!!.baseToken)

        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun `file store replaces atomically and leaves no temp files behind`() {
        val directory = temporaryFolder.newFolder("atomic")
        val file = File(directory, "device.json")
        val store = FileIdentityStore(file)

        store.save(record())
        store.save(record())

        assertEquals(1, store.load()!!.version)
        assertEquals(listOf("device.json"), directory.list()!!.toList())
    }

    @Test
    fun `corrupt file on disk is not a crash`() {
        val file = temporaryFolder.newFile("broken.json")
        file.writeText("{ this is not json")
        assertNull(FileIdentityStore(file).load())
    }

    @Test
    fun `memory store behaves like the real one`() {
        val store = MemoryIdentityStore()
        assertNull(store.load())
        store.save(record())
        assertTrue(store.load() != null)
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `updated timestamp moves with the tokens`() {
        val record = record(nowMillis = 1_000)
        val updated = record.withTokens(null, null, nowMillis = 2_000)
        assertEquals(2_000, updated.updatedAtMillis)
        assertEquals(1_000, updated.createdAtMillis)
        assertTrue(updated.updatedAtMillis > 0 && Date(updated.updatedAtMillis).before(Date(Long.MAX_VALUE)))
    }
}
