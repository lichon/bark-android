package io.github.lichon.abark.apns.activation

import io.github.lichon.abark.net.HttpPost
import io.github.lichon.abark.net.HttpResult
import io.github.lichon.abark.net.HttpTransport
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captures the activation POST so the body can be unpacked and checked. */
private class RecordingTransport(private val respond: (HttpPost) -> HttpResult) : HttpTransport {
    var lastRequest: HttpPost? = null

    override fun post(request: HttpPost): HttpResult {
        lastRequest = request
        return respond(request)
    }
}

/** `ActivationInfoXML`, `FairPlaySignature` and `FairPlayCertChain` as Alberts reads them out of the form body. */
private class ActivationBody(request: HttpPost) {
    val outer: PlistValue.Dict

    init {
        val body = request.body.toString(Charsets.UTF_8)
        require(body.startsWith("activation-info=")) { "not an activation form body" }
        val decoded = URLDecoder.decode(body.removePrefix("activation-info="), "UTF-8")
        outer = PlistCodec.parse(decoded) as PlistValue.Dict
    }

    val activationInfoXml: ByteArray get() = outer.entries["ActivationInfoXML"].asBytes()!!
    val fairPlaySignature: ByteArray get() = outer.entries["FairPlaySignature"].asBytes()!!
    val fairPlayCertChain: ByteArray get() = outer.entries["FairPlayCertChain"].asBytes()!!
}

/** `SHA1withRSA` verification, done the way Alberts does it: against the certificate that came with the request. */
private fun verifies(payload: ByteArray, signature: ByteArray, key: PublicKey): Boolean =
    Signature.getInstance(RsaFairPlaySigner.SIGNATURE_ALGORITHM).run {
        initVerify(key)
        update(payload)
        verify(signature)
    }

private fun commonName(certificate: X509Certificate): String? =
    certificate.subjectX500Principal.name
        .split(',')
        .firstOrNull { it.trim().startsWith("CN=") }
        ?.substringAfter("CN=")
        ?.trim()

/** A self-signed certificate over [keyPair] stands in for the one Alberts issues. */
private fun certificateHtml(certificatePem: String): String = """
    <html><body><h1>Device Activation</h1>
    <Protocol>
    <plist version="1.0"><dict>
    <key>device-activation</key><dict><key>activation-record</key><dict>
      <key>DeviceCertificate</key><string>${PlistCodec.escape(certificatePem)}</string>
      <key>UniqueDeviceID</key><string>UDID-1</string>
    </dict></dict>
    </dict></plist>
    </Protocol>
    </body></html>
""".trimIndent()

class AlbertFairPlayTest {

    @Test
    fun `embedded chain is Apple's four certificate activation chain`() {
        val certificates = AlbertFairPlay.certificates

        assertEquals(4, certificates.size)
        // Leaf: the FairPlay certificate Apple issued for this key (its CN is a bare UUID).
        assertTrue(certificates[0].subjectX500Principal.name, certificates[0].subjectX500Principal.name.contains("160D3FA1"))
        assertEquals("Apple iPhone Device CA", commonName(certificates[1]))
        assertEquals("Apple iPhone Certification Authority", commonName(certificates[2]))
        assertEquals("Apple Root CA", commonName(certificates[3]))
        // FairPlayCertChain is the concatenation of the same DER, and that is what gets uploaded.
        assertArrayEquals(AlbertFairPlay.certChainDer, certificates.fold(ByteArray(0)) { acc, c -> acc + c.encoded })
    }

    @Test
    fun `embedded private key is the 1024-bit key the leaf certificate covers`() {
        val privateKey = DeviceKey.parsePrivateKey(AlbertFairPlay.privateKeyPem) as RSAPrivateKey
        val publicKey = AlbertFairPlay.leafCertificate.publicKey as RSAPublicKey

        assertEquals(DeviceKey.KEY_SIZE_BITS, privateKey.modulus.bitLength())
        assertEquals(privateKey.modulus, publicKey.modulus)
        assertEquals(java.math.BigInteger.valueOf(65537), publicKey.publicExponent)
    }

    @Test
    fun `embedded key signs what the FairPlay certificate verifies`() {
        val payload = "activation-info-xml".toByteArray()
        val signature = AlbertFairPlay.credentials.signer.sign(payload)

        assertTrue(verifies(payload, signature, AlbertFairPlay.leafCertificate.publicKey))
        assertFalse(verifies("tampered".toByteArray(), signature, AlbertFairPlay.leafCertificate.publicKey))
    }

    @Test
    fun `credentials are parsed once and exposed as PEM and DER`() {
        assertSame(AlbertFairPlay.credentials, AlbertFairPlay.credentials)
        assertTrue(AlbertFairPlay.privateKeyPem.startsWith("-----BEGIN RSA PRIVATE KEY-----"))
        assertTrue(AlbertFairPlay.certChainDer.isNotEmpty())
        assertArrayEquals(AlbertFairPlay.certChainDer, AlbertFairPlay.credentials.certChainDer)
    }
}

class AlbertTest {

    private fun activated(outcome: ActivationOutcome): ActivatedDevice =
        (outcome as? ActivationOutcome.Activated)?.device ?: throw AssertionError("expected success: $outcome")

    @Test
    fun `activate posts the embedded chain and a signature over the uploaded bytes`() {
        val keyPair = DeviceKey.generate()
        val transport = RecordingTransport {
            HttpResult(200, certificateHtml(TestCertificates.selfSignedPem(DeviceKey.generate())).toByteArray())
        }

        Albert(ActivationClient(transport), AlbertFairPlay.credentials)
            .activate(DeviceParams.windows(), keyPair, commonName = "CN-1")

        val request = transport.lastRequest!!
        assertEquals("${ActivationClient.ALBERT_URL}?device=Windows", request.url)
        assertEquals(ActivationClient.FORM_CONTENT_TYPE, request.contentType)

        val body = ActivationBody(request)
        assertEquals(PlistValue.Bool(true), body.outer.entries["ActivationInfoComplete"])
        assertArrayEquals(AlbertFairPlay.certChainDer, body.fairPlayCertChain)
        assertTrue(
            "the signature must verify with the FairPlay certificate's public key",
            verifies(body.activationInfoXml, body.fairPlaySignature, AlbertFairPlay.leafCertificate.publicKey),
        )

        // The CSR asks Apple for a certificate over the device key we generated, not over the FairPlay key.
        val inner = PlistCodec.parse(String(body.activationInfoXml, Charsets.UTF_8)) as PlistValue.Dict
        assertEquals(DeviceParams.windows().deviceClass, inner.entries["DeviceClass"].asString())
        val csr = CertificateRequest.parse(String(inner.entries["DeviceCertRequest"].asBytes()!!, Charsets.UTF_8))
        assertTrue(CertificateRequest.isSignatureValid(csr, keyPair.public))
        assertEquals("CN-1", CertificateRequest.subjectCommonName(csr))
    }

    @Test
    fun `activate returns the issued certificate for the key it generated`() {
        val keyPair = DeviceKey.generate()
        val transport = RecordingTransport { HttpResult(200, certificateHtml(TestCertificates.selfSignedPem(keyPair)).toByteArray()) }

        val device = activated(Albert(ActivationClient(transport), AlbertFairPlay.credentials).activate(keyPair = keyPair))

        assertTrue(device.certificateMatchesKey)
        assertSame(keyPair, device.keyPair)
        assertEquals("UDID-1", device.activationRecord.entries["UniqueDeviceID"].asString())
    }

    @Test
    fun `callers can override the FairPlay material`() {
        val fairPlay = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(DeviceKey.KEY_SIZE_BITS) }
            .generateKeyPair()
        val credentials = FairPlayCredentials(
            certChainDer = AlbertFairPlay.certChainDer,
            signer = RsaFairPlaySigner(fairPlay.private),
        )
        val transport = RecordingTransport {
            HttpResult(200, certificateHtml(TestCertificates.selfSignedPem(DeviceKey.generate())).toByteArray())
        }

        val albert = Albert(ActivationClient(transport), credentials)
        albert.activate(DeviceParams.windows(), DeviceKey.generate())

        val body = ActivationBody(transport.lastRequest!!)
        assertTrue(verifies(body.activationInfoXml, body.fairPlaySignature, fairPlay.public))
        assertFalse(verifies(body.activationInfoXml, body.fairPlaySignature, AlbertFairPlay.leafCertificate.publicKey))
        assertSame(credentials, albert.fairPlayCredentials)
    }

    @Test
    fun `alberts rejection is reported, not thrown`() {
        val transport = RecordingTransport {
            HttpResult(400, "<html><body><b>Bad activation-info</b></body></html>".toByteArray())
        }

        val outcome = Albert(ActivationClient(transport), AlbertFairPlay.credentials).activate()

        val failure = (outcome as? ActivationOutcome.Rejected)?.failure ?: throw AssertionError("expected rejection")
        assertEquals(ActivationFailure.Reason.HTTP_ERROR, failure.reason)
        assertEquals(400, failure.httpStatus)
        assertEquals("Bad activation-info", failure.detail)
    }
}
