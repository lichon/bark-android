package io.github.lichon.abark.apns.activation

import java.security.PrivateKey
import java.security.Signature

/**
 * Signs `ActivationInfoXML` with Apple's FairPlay key, which Alberts requires before it will issue a device
 * certificate.
 *
 * The key material is deliberately *not* in this repository: [ActivationClient] takes a [FairPlayCredentials]
 * instance, so an on-device build injects it from a blob the user supplies (PLAN.md decision 2) while the
 * desktop/CLI harness and tests inject a throwaway key. Keeping it behind an interface also means a future
 * activation-proxy mode (decision 3.3 A) changes nothing above this line.
 */
fun interface FairPlaySigner {
    fun sign(payload: ByteArray): ByteArray
}

/** `RSA-PKCS1v1.5-SHA1`, the signature scheme Alberts verifies. */
class RsaFairPlaySigner(private val privateKey: PrivateKey) : FairPlaySigner {
    override fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }

    companion object {
        const val SIGNATURE_ALGORITHM: String = "SHA1withRSA"
    }
}

/** Everything Alberts needs besides the CSR: the FairPlay certificate chain (DER, opaque) and its signing key. */
class FairPlayCredentials(
    val certChainDer: ByteArray,
    val signer: FairPlaySigner,
) {
    init {
        require(certChainDer.isNotEmpty()) { "FairPlay certificate chain must not be empty" }
    }
}

/** Loads `FairPlayCredentials` from PEM text handed in by the caller (file, keystore, remote config). */
object FairPlayMaterial {

    /**
     * @param privateKeyPem PKCS#8 or PKCS#1 RSA private key.
     * @param certificatePem one or more concatenated certificates; their DER is concatenated in order, which is the
     *   layout of `FairPlayCertChain`.
     */
    fun fromPem(privateKeyPem: String, certificatePem: String): FairPlayCredentials =
        FairPlayCredentials(
            certChainDer = Pem.concatenateDer(certificatePem),
            signer = RsaFairPlaySigner(DeviceKey.parsePrivateKey(privateKeyPem)),
        )
}
