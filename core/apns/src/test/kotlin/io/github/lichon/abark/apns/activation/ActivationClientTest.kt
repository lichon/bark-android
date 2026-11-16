package io.github.lichon.abark.apns.activation

import io.github.lichon.abark.net.HttpPost
import io.github.lichon.abark.net.HttpResult
import io.github.lichon.abark.net.HttpTransport
import java.io.IOException
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the request it was given so the outgoing body can be asserted. */
private class FakeTransport(
    private val respond: (HttpPost) -> HttpResult,
) : HttpTransport {
    var lastRequest: HttpPost? = null
    var calls: Int = 0

    override fun post(request: HttpPost): HttpResult {
        calls++
        lastRequest = request
        return respond(request)
    }
}

class ActivationClientTest {

    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(DeviceKey.KEY_SIZE_BITS) }.generateKeyPair()

    private val credentials: FairPlayCredentials = FairPlayCredentials(
        certChainDer = byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03),
        signer = RsaFairPlaySigner(keyPair.private),
    )

    private fun html(protocolXml: String): String = """
        <html><body><h1>Device Activation</h1>
        <Protocol>
        $protocolXml
        </Protocol>
        </body></html>
    """.trimIndent()

    private fun protocolPlist(
        certificatePem: String? = TestCertificates.selfSignedPem(keyPair),
        nested: Boolean = true,
    ): String {
        val certEntry = certificatePem?.let {
            "<key>DeviceCertificate</key><string>${PlistCodec.escape(it)}</string>"
        } ?: ""
        val record = """
            <plist version="1.0"><dict>
            ${if (nested) "<key>device-activation</key><dict><key>activation-record</key><dict>" else ""}
              $certEntry
              <key>UniqueDeviceID</key><string>UDID-1</string>
            ${if (nested) "</dict></dict>" else ""}
            </dict></plist>
        """.trimIndent()
        return record
    }

    private fun activated(outcome: ActivationOutcome): ActivatedDevice {
        val activated = outcome as? ActivationOutcome.Activated ?: throw AssertionError("expected success: $outcome")
        return activated.device
    }

    private fun rejected(outcome: ActivationOutcome): ActivationFailure {
        val rejected = outcome as? ActivationOutcome.Rejected ?: throw AssertionError("expected failure: $outcome")
        return rejected.failure
    }

    @Test
    fun `successful activation returns the certificate for our key`() {
        val transport = FakeTransport { HttpResult(200, html(protocolPlist()).toByteArray()) }
        val device = activated(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertTrue(device.certificateMatchesKey)
        assertTrue(device.certificatePem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals("UDID-1", device.activationRecord.entries["UniqueDeviceID"].asString())
        assertTrue(device.notAfter.after(java.util.Date()))
        assertTrue(device.subject.contains("Test Device"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `request is a form POST to the deviceActivation endpoint for this device class`() {
        val transport = FakeTransport { HttpResult(200, html(protocolPlist()).toByteArray()) }
        ActivationClient(transport, baseUrl = "https://albert.example/deviceservices/deviceActivation")
            .activate(DeviceParams.windows(), keyPair, credentials)

        val request = transport.lastRequest!!
        assertEquals("https://albert.example/deviceservices/deviceActivation?device=Windows", request.url)
        assertEquals("application/x-www-form-urlencoded", request.contentType)

        val body = request.body.toString(Charsets.UTF_8)
        assertTrue(body, body.startsWith("activation-info="))
        val decoded = URLDecoder.decode(body.removePrefix("activation-info="), "UTF-8")
        val outer = PlistCodec.parse(decoded) as PlistValue.Dict
        assertEquals(PlistValue.Bool(true), outer.entries["ActivationInfoComplete"])
        assertNotNull(outer.entries["FairPlaySignature"].asBytes())
    }

    @Test
    fun `accepts the flattened record shape too`() {
        val transport = FakeTransport { HttpResult(200, html(protocolPlist(nested = false)).toByteArray()) }
        val device = activated(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))
        assertTrue(device.certificateMatchesKey)
    }

    /** `DeviceCertificate` as `<data>` holding PEM bytes — what Alberts actually returns today. */
    @Test
    fun `device certificate in a data blob is accepted`() {
        val pem = TestCertificates.selfSignedPem(keyPair)
        val transport = FakeTransport {
            HttpResult(200, html(dataProtocolPlist(pem.toByteArray(Charsets.UTF_8))).toByteArray())
        }

        val device = activated(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertTrue(device.certificateMatchesKey)
        assertEquals(pem, device.certificatePem)
        assertEquals(1, transport.calls)
    }

    /** A `<data>` blob holding DER instead of PEM is wrapped, so callers only ever see PEM. */
    @Test
    fun `raw der in a data blob is wrapped in pem`() {
        val der = Pem.decode(TestCertificates.selfSignedPem(keyPair))!!.der
        val transport = FakeTransport { HttpResult(200, html(dataProtocolPlist(der)).toByteArray()) }

        val device = activated(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertTrue(device.certificatePem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertArrayEquals(der, device.certificateDer)
    }

    /** The live response's shape: everything under `device-activation.activation-record`, certificate as bytes. */
    private fun dataProtocolPlist(certificateBytes: ByteArray): String {
        val payload = java.util.Base64.getEncoder().encodeToString(certificateBytes)
        return """
            <plist version="1.0"><dict><key>device-activation</key><dict>
              <key>ack-received</key><true/>
              <key>activation-record</key><dict>
                <key>DeviceCertificate</key><data>$payload</data>
              </dict>
            </dict></dict></plist>
        """.trimIndent()
    }

    @Test
    fun `http error surfaces Apple's message`() {
        val transport = FakeTransport {
            HttpResult(400, "<html><body><b>Bad activation info</b></body></html>".toByteArray())
        }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertEquals(ActivationFailure.Reason.HTTP_ERROR, failure.reason)
        assertEquals(400, failure.httpStatus)
        assertEquals("Bad activation info", failure.detail)
    }

    @Test
    fun `2xx without a protocol block is an apple-side rejection`() {
        val transport = FakeTransport { HttpResult(200, "<html><b>Device already activated</b></html>".toByteArray()) }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertEquals(ActivationFailure.Reason.NO_PROTOCOL_BLOCK, failure.reason)
        assertEquals("Device already activated", failure.detail)
    }

    @Test
    fun `unparseable protocol block is reported as such`() {
        val transport = FakeTransport { HttpResult(200, html("<not-a-plist/>").toByteArray()) }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertEquals(ActivationFailure.Reason.BAD_PROTOCOL, failure.reason)
    }

    @Test
    fun `protocol without a certificate is not success`() {
        val transport = FakeTransport {
            HttpResult(200, html(protocolPlist(certificatePem = null)).toByteArray())
        }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertEquals(ActivationFailure.Reason.NO_CERTIFICATE, failure.reason)
    }

    @Test
    fun `certificate for a different key is refused`() {
        val foreign = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(DeviceKey.KEY_SIZE_BITS) }
            .generateKeyPair()
        val transport = FakeTransport {
            HttpResult(200, html(protocolPlist(TestCertificates.selfSignedPem(foreign))).toByteArray())
        }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertEquals(ActivationFailure.Reason.CERTIFICATE_MISMATCH, failure.reason)
    }

    @Test
    fun `transport failure is retryable and does not throw`() {
        val transport = FakeTransport { throw IOException("connect timed out") }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertEquals(ActivationFailure.Reason.TRANSPORT_ERROR, failure.reason)
        assertEquals("connect timed out", failure.detail)
    }

    @Test
    fun `wrong key size is refused before the network is touched`() {
        val wrongSize = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val transport = FakeTransport { HttpResult(200, html(protocolPlist()).toByteArray()) }

        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), wrongSize, credentials))

        assertEquals(ActivationFailure.Reason.INVALID_REQUEST, failure.reason)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `rejection detail is bounded so a hostile page cannot bloat the failure`() {
        val transport = FakeTransport {
            HttpResult(500, ("<html>" + "x".repeat(10_000) + "</html>").toByteArray())
        }
        val failure = rejected(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))

        assertTrue(failure.detail!!.length <= 200)
    }

    @Test
    fun `outcome never carries a private key`() {
        val transport = FakeTransport { HttpResult(200, html(protocolPlist()).toByteArray()) }
        val device = activated(ActivationClient(transport).activate(DeviceParams.windows(), keyPair, credentials))
        assertFalse(device.certificatePem.contains("PRIVATE KEY"))
        assertArrayEquals(keyPair.public.encoded, device.certificate.publicKey.encoded)
    }
}
