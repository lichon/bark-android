package io.github.lichon.abark.apns.activation

import java.security.KeyPair
import java.security.KeyPairGenerator
import org.bouncycastle.asn1.x500.style.BCStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateRequestTest {

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(DeviceKey.KEY_SIZE_BITS) }.generateKeyPair()

    @Test
    fun `produces a PEM CSR over the device key that verifies`() {
        val keyPair = keyPair()
        val pem = CertificateRequest.pem(keyPair, commonName = "9F1E")

        assertTrue(pem, pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        val request = CertificateRequest.parse(pem)

        assertEquals("9F1E", CertificateRequest.subjectCommonName(request))
        assertTrue(CertificateRequest.isSignatureValid(request, keyPair.public))
        assertArrayEquals(
            keyPair.public.encoded,
            request.subjectPublicKeyInfo.encoded,
        )
        // No private key material may ever appear in the outgoing request.
        assertTrue(pem, !pem.contains("PRIVATE"))
    }

    @Test
    fun `subject carries the fields Alberts sees from a Windows client`() {
        val request = CertificateRequest.parse(CertificateRequest.pem(keyPair(), commonName = "CN-test"))
        val name = request.subject

        assertEquals("US", name.getRDNs(BCStyle.C).first().first.value.toString())
        assertEquals("CA", name.getRDNs(BCStyle.ST).first().first.value.toString())
        assertEquals("Cupertino", name.getRDNs(BCStyle.L).first().first.value.toString())
        assertEquals("Apple Inc.", name.getRDNs(BCStyle.O).first().first.value.toString())
        assertEquals("iPhone", name.getRDNs(BCStyle.OU).first().first.value.toString())
        assertEquals("CN-test", name.getRDNs(BCStyle.CN).first().first.value.toString())
    }

    @Test
    fun `common name defaults to a fresh uuid`() {
        val keyPair = keyPair()
        val first = CertificateRequest.parse(CertificateRequest.pem(keyPair)).let(CertificateRequest::subjectCommonName)
        val second = CertificateRequest.parse(CertificateRequest.pem(keyPair)).let(CertificateRequest::subjectCommonName)

        assertEquals(36, first!!.length)
        assertTrue(first != second)
    }

    @Test
    fun `signature from a different key does not verify`() {
        val ours = keyPair()
        val other = keyPair()
        val request = CertificateRequest.parse(CertificateRequest.pem(ours))
        assertTrue(CertificateRequest.isSignatureValid(request, ours.public))
        assertTrue(!CertificateRequest.isSignatureValid(request, other.public))
    }

    @Test
    fun `parsing a PEM that is not a CSR fails`() {
        assertThrows(Exception::class.java) {
            CertificateRequest.parse(Pem.encode("CERTIFICATE", byteArrayOf(1, 2, 3, 4)))
        }
        assertThrows(PemException::class.java) { CertificateRequest.parse("nothing") }
    }
}

class FairPlayTest {

    private fun rsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    @Test
    fun `signer produces a PKCS1v15 SHA1 signature over the payload`() {
        val keyPair = rsaKeyPair()
        val payload = PlistCodec.encode(PlistValue.Dict(mapOf("a" to PlistValue.String("b"))))
            .toByteArray(Charsets.UTF_8)

        val signature = RsaFairPlaySigner(keyPair.private).sign(payload)

        assertTrue(
            "signature must verify as SHA1withRSA",
            java.security.Signature.getInstance("SHA1withRSA").run {
                initVerify(keyPair.public)
                update(payload)
                verify(signature)
            },
        )
    }

    @Test
    fun `credentials from PEM concatenate the whole chain and can sign`() {
        val fairPlay = rsaKeyPair()
        val intermediate = rsaKeyPair()

        val chainPem = TestCertificates.selfSignedPem(fairPlay) + TestCertificates.selfSignedPem(intermediate)
        val credentials = FairPlayMaterial.fromPem(
            privateKeyPem = DeviceKey.privateKeyPem(fairPlay.private),
            certificatePem = chainPem,
        )

        val expected = Pem.decodeAll(chainPem).flatMap { it.der.asIterable() }.toByteArray()
        assertArrayEquals(expected, credentials.certChainDer)
        assertTrue(credentials.signer.sign(byteArrayOf(1, 2, 3)).isNotEmpty())
    }

    @Test
    fun `empty chain is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FairPlayCredentials(ByteArray(0), FairPlaySigner { ByteArray(0) })
        }
    }
}
