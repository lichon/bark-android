package io.github.lichon.abark.apns.activation

import java.io.StringWriter
import java.security.KeyPair
import java.security.KeyPairGenerator
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.util.io.pem.PemWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyTest {

    private fun rsaKeyPair(bits: Int = DeviceKey.KEY_SIZE_BITS): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    @Test
    fun `generates exactly the key size Alberts issues certificates for`() {
        val keyPair = DeviceKey.generate()
        assertEquals(DeviceKey.KEY_SIZE_BITS, DeviceKey.modulusBits(keyPair.public))
        assertTrue(DeviceKey.isDeviceKeySize(keyPair.public))
        assertTrue(DeviceKey.isDeviceKeySize(keyPair.private))
        assertFalse(DeviceKey.isDeviceKeySize(rsaKeyPair(2048).public))
    }

    @Test
    fun `private key survives a PKCS8 round-trip`() {
        val keyPair = DeviceKey.generate()
        val pem = DeviceKey.privateKeyPem(keyPair.private)
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----\n"))
        assertArrayEquals(keyPair.private.encoded, DeviceKey.parsePrivateKey(pem).encoded)
    }

    @Test
    fun `private key loader also accepts the PKCS1 form openssl emits`() {
        val keyPair = DeviceKey.generate()
        val writer = StringWriter()
        PemWriter(writer).use { it.writeObject(JcaMiscPEMGenerator(keyPair.private)) }
        val pkcs1 = writer.toString()

        assertTrue(pkcs1, pkcs1.contains("BEGIN RSA PRIVATE KEY"))
        assertArrayEquals(keyPair.private.encoded, DeviceKey.parsePrivateKey(pkcs1).encoded)
    }

    @Test
    fun `public key survives a round-trip`() {
        val keyPair = DeviceKey.generate()
        val pem = DeviceKey.publicKeyPem(keyPair.public)
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"))
        assertArrayEquals(keyPair.public.encoded, DeviceKey.parsePublicKey(pem).encoded)
    }

    @Test
    fun `garbage input is rejected rather than silently producing a key`() {
        assertThrows(PemException::class.java) { DeviceKey.parsePrivateKey("not a pem") }
        assertThrows(PemException::class.java) { DeviceKey.parsePublicKey("-----BEGIN PUBLIC KEY-----\n-----END PUBLIC KEY-----\n") }
    }
}

class PemTest {

    @Test
    fun `wraps base64 at 64 columns and decodes back`() {
        val der = ByteArray(200) { (it * 7).toByte() }
        val pem = Pem.encode("CERTIFICATE REQUEST", der)

        val body = pem.lines().filter { it.isNotEmpty() && !it.startsWith("-----") }
        assertTrue(body.all { it.length <= 64 })
        assertArrayEquals(der, Pem.decode(pem)!!.der)
    }

    @Test
    fun `decodes concatenated blocks in order`() {
        val chain = Pem.encode("CERTIFICATE", byteArrayOf(1, 2, 3)) +
            Pem.encode("CERTIFICATE", byteArrayOf(4, 5))
        val blocks = Pem.decodeAll(chain)

        assertEquals(2, blocks.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), blocks[0].der)
        assertArrayEquals(byteArrayOf(4, 5), blocks[1].der)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), Pem.concatenateDer(chain))
    }

    @Test
    fun `tolerates CRLF and stray whitespace`() {
        val pem = "-----BEGIN CERTIFICATE-----\r\nAAEC\r\nAw==\r\n-----END CERTIFICATE-----\r\n"
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), Pem.decode(pem)!!.der)
    }

    @Test
    fun `missing or empty blocks are an error, not an empty result`() {
        assertEquals(null, Pem.decode("no pem here"))
        assertThrows(PemException::class.java) {
            Pem.decode("-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n")
        }
        assertThrows(PemException::class.java) { Pem.concatenateDer("nothing") }
    }
}
