package io.github.lichon.abark.apns.activation

import java.security.KeyPair
import java.util.UUID

/**
 * The `activation-info` form body Alberts expects:
 *
 * ```
 * POST /deviceservices/deviceActivation?device=Windows
 * activation-info = plist{
 *   ActivationInfoComplete = true,
 *   ActivationInfoXML      = <data: plist{ActivationRandomness, ActivationState, BuildVersion, DeviceCertRequest,
 *                                          DeviceClass, ProductType, ProductVersion, SerialNumber, UniqueDeviceID}>,
 *   FairPlayCertChain      = <data: concatenated DER>,
 *   FairPlaySignature      = <data: RSASSA-PKCS1-v1_5-SHA1 over the ActivationInfoXML bytes>,
 * }
 * ```
 *
 * The inner document is signed as *bytes*, so it is serialised exactly once and both the signature and the upload use
 * that same array — re-encoding it would break the signature.
 */
class ActivationInfo(
    val activationInfoXml: ByteArray,
    val fairPlaySignature: ByteArray,
    /** The outer plist document (still XML text). */
    val body: ByteArray,
    val csrPem: String,
    val randomness: String,
) {
    /** `activation-info=<urlencoded plist>` — the exact request body for Alberts. */
    fun formEncoded(): ByteArray =
        (FORM_FIELD + "=" + encodeFormValue(body.toString(Charsets.UTF_8))).toByteArray(Charsets.UTF_8)

    companion object {
        const val FORM_FIELD: String = "activation-info"

        const val ACTIVATION_STATE_UNACTIVATED: String = "Unactivated"

        fun create(
            params: DeviceParams,
            keyPair: KeyPair,
            credentials: FairPlayCredentials,
            randomness: String = UUID.randomUUID().toString(),
            commonName: String = UUID.randomUUID().toString(),
        ): ActivationInfo {
            val csrPem = CertificateRequest.pem(keyPair, commonName)

            val inner = PlistValue.Dict(
                linkedMapOf(
                    "ActivationRandomness" to PlistValue.String(randomness),
                    "ActivationState" to PlistValue.String(ACTIVATION_STATE_UNACTIVATED),
                    "BuildVersion" to PlistValue.String(params.buildVersion),
                    "DeviceCertRequest" to PlistValue.Bytes(csrPem.toByteArray(Charsets.UTF_8)),
                    "DeviceClass" to PlistValue.String(params.deviceClass),
                    "ProductType" to PlistValue.String(params.productType),
                    "ProductVersion" to PlistValue.String(params.productVersion),
                    "SerialNumber" to PlistValue.String(params.serialNumber),
                    "UniqueDeviceID" to PlistValue.String(params.uniqueDeviceId),
                ),
            )
            val innerXml = PlistCodec.encode(inner, sortKeys = false).toByteArray(Charsets.UTF_8)
            val signature = credentials.signer.sign(innerXml)

            val outer = PlistValue.Dict(
                linkedMapOf(
                    "ActivationInfoComplete" to PlistValue.Bool(true),
                    "ActivationInfoXML" to PlistValue.Bytes(innerXml),
                    "FairPlayCertChain" to PlistValue.Bytes(credentials.certChainDer),
                    "FairPlaySignature" to PlistValue.Bytes(signature),
                ),
            )

            return ActivationInfo(
                activationInfoXml = innerXml,
                fairPlaySignature = signature,
                body = PlistCodec.encode(outer, sortKeys = false).toByteArray(Charsets.UTF_8),
                csrPem = csrPem,
                randomness = randomness,
            )
        }
    }
}
