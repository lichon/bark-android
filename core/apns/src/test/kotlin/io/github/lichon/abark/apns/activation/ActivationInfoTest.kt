package io.github.lichon.abark.apns.activation

import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationInfoTest {

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(DeviceKey.KEY_SIZE_BITS) }.generateKeyPair()

    private fun credentials(keyPair: KeyPair): FairPlayCredentials {
        val chain = TestCertificates.selfSignedPem(keyPair)
        return FairPlayCredentials(
            certChainDer = Pem.decode(chain)!!.der,
            signer = RsaFairPlaySigner(keyPair.private),
        )
    }

    /** Decodes `activation-info=<percent-encoded>` back to the plist text. */
    private fun formValue(info: ActivationInfo): String {
        val body = info.formEncoded().toString(Charsets.UTF_8)
        val prefix = ActivationInfo.FORM_FIELD + "="
        assertTrue(body, body.startsWith(prefix))
        return URLDecoder.decode(body.removePrefix(prefix), "UTF-8")
    }

    @Test
    fun `inner document carries every field Alberts reads`() {
        val keyPair = keyPair()
        val params = DeviceParams.windows(uniqueDeviceId = "UDID-1", serialNumber = "SERIAL-1")
        val info = ActivationInfo.create(params, keyPair, credentials(keyPair), randomness = "RANDOM-1")

        val inner = PlistCodec.parse(info.activationInfoXml.toString(Charsets.UTF_8)) as PlistValue.Dict
        assertEquals("RANDOM-1", inner.entries["ActivationRandomness"].asString())
        assertEquals("Unactivated", inner.entries["ActivationState"].asString())
        assertEquals("10.6.4", inner.entries["BuildVersion"].asString())
        assertEquals("Windows", inner.entries["DeviceClass"].asString())
        assertEquals("windows1,1", inner.entries["ProductType"].asString())
        assertEquals("10.6.4", inner.entries["ProductVersion"].asString())
        assertEquals("SERIAL-1", inner.entries["SerialNumber"].asString())
        assertEquals("UDID-1", inner.entries["UniqueDeviceID"].asString())

        // DeviceCertRequest is a <data> block holding the PEM CSR, not a plain string.
        val csrPem = inner.entries["DeviceCertRequest"].asBytes()!!.toString(Charsets.UTF_8)
        val request = CertificateRequest.parse(csrPem)
        assertTrue(CertificateRequest.isSignatureValid(request, keyPair.public))
        assertArrayEquals(keyPair.public.encoded, request.subjectPublicKeyInfo.encoded)
        assertEquals(csrPem, info.csrPem)
    }

    @Test
    fun `fairplay signature covers the exact bytes that are uploaded`() {
        val keyPair = keyPair()
        val info = ActivationInfo.create(DeviceParams.windows(), keyPair, credentials(keyPair))

        val outer = PlistCodec.parse(formValue(info)) as PlistValue.Dict
        val uploadedInner = outer.entries["ActivationInfoXML"].asBytes()!!

        assertArrayEquals(info.activationInfoXml, uploadedInner)
        assertTrue(
            "signature must verify over the uploaded inner document",
            Signature.getInstance("SHA1withRSA").run {
                initVerify(keyPair.public)
                update(uploadedInner)
                verify(outer.entries["FairPlaySignature"].asBytes())
            },
        )
    }

    @Test
    fun `outer document is complete and carries the chain verbatim`() {
        val keyPair = keyPair()
        val chain = TestCertificates.selfSignedPem(keyPair)
        val info = ActivationInfo.create(
            DeviceParams.windows(),
            keyPair,
            FairPlayCredentials(Pem.decode(chain)!!.der, RsaFairPlaySigner(keyPair.private)),
        )

        val outer = PlistCodec.parse(formValue(info)) as PlistValue.Dict
        assertEquals(PlistValue.Bool(true), outer.entries["ActivationInfoComplete"])
        assertArrayEquals(Pem.decode(chain)!!.der, outer.entries["FairPlayCertChain"].asBytes())
        assertArrayEquals(info.fairPlaySignature, outer.entries["FairPlaySignature"].asBytes())
    }

    @Test
    fun `randomness defaults to a fresh uuid per request`() {
        val keyPair = keyPair()
        val first = ActivationInfo.create(DeviceParams.windows(), keyPair, credentials(keyPair))
        val second = ActivationInfo.create(DeviceParams.windows(), keyPair, credentials(keyPair))

        assertEquals(36, first.randomness.length)
        UUID.fromString(first.randomness)
        assertTrue(first.randomness != second.randomness)
        assertTrue(!first.activationInfoXml.contentEquals(second.activationInfoXml))
    }

    @Test
    fun `form body survives URL decoding and re-parsing`() {
        val keyPair = keyPair()
        val info = ActivationInfo.create(DeviceParams.windows(), keyPair, credentials(keyPair))
        val decoded = formValue(info)

        // The body is XML text that went through percent-encoding; `&` and `<` must come back intact.
        assertTrue(decoded.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(decoded.contains("<!DOCTYPE plist"))
        PlistCodec.parse(decoded)
    }
}
