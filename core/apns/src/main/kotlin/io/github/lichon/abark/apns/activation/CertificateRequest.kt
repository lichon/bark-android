package io.github.lichon.abark.apns.activation

import java.security.KeyPair
import java.security.PublicKey
import java.util.UUID
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder

/**
 * PKCS#10 certificate signing request for Alberts (`ActivationInfoXML.DeviceCertRequest`).
 *
 * Only the CSR — never the private key — is sent to Apple. The subject is a throwaway UUID common name in Apple's own
 * naming style; Alberts does not validate it, it issues a certificate over whatever key we ask for.
 */
object CertificateRequest {

    const val SIGNATURE_ALGORITHM: String = "SHA256withRSA"

    /** Subject fields observed in the reference Windows activation. */
    val SUBJECT_TEMPLATE: List<Pair<ASN1ObjectIdentifier, String>> = listOf(
        BCStyle.C to "US",
        BCStyle.ST to "CA",
        BCStyle.L to "Cupertino",
        BCStyle.O to "Apple Inc.",
        BCStyle.OU to "iPhone",
    )

    fun subject(commonName: String): X500Name {
        val builder = X500NameBuilder(BCStyle.INSTANCE)
        for ((oid, value) in SUBJECT_TEMPLATE) builder.addRDN(oid, value)
        builder.addRDN(BCStyle.CN, commonName)
        return builder.build()
    }

    fun der(keyPair: KeyPair, commonName: String = UUID.randomUUID().toString()): ByteArray {
        val builder = JcaPKCS10CertificationRequestBuilder(subject(commonName), keyPair.public)
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
        return builder.build(signer).encoded
    }

    /** `-----BEGIN CERTIFICATE REQUEST-----`, the form Alberts wants inside the plist. */
    fun pem(keyPair: KeyPair, commonName: String = UUID.randomUUID().toString()): String =
        Pem.encode("CERTIFICATE REQUEST", der(keyPair, commonName))

    fun parse(pem: String): PKCS10CertificationRequest {
        val block = Pem.decode(pem) ?: throw PemException("no CSR in PEM input")
        return PKCS10CertificationRequest(block.der)
    }

    /** True when the CSR's own signature verifies against [publicKey] — what Alberts checks first. */
    fun isSignatureValid(request: PKCS10CertificationRequest, publicKey: PublicKey): Boolean =
        request.isSignatureValid(
            JcaContentVerifierProviderBuilder().build(publicKey),
        )

    fun subjectCommonName(request: PKCS10CertificationRequest): String? =
        request.subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString()
}
