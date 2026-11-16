package io.github.lichon.abark.apns.activation

import io.github.lichon.abark.net.HttpPost
import io.github.lichon.abark.net.HttpResult
import io.github.lichon.abark.net.HttpTransport
import io.github.lichon.abark.net.UrlConnectionTransport
import java.io.IOException
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** An activated device: the identity Apple certified plus the certificate it issued for it. */
class ActivatedDevice(
    val params: DeviceParams,
    val keyPair: KeyPair,
    val certificatePem: String,
    /** The whole `activation-record` dictionary, for fields we do not model yet. */
    val activationRecord: PlistValue.Dict,
) {
    val certificateDer: ByteArray = Pem.decode(certificatePem)?.der
        ?: throw ActivationException("activation record certificate is not PEM")

    val certificate: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509").generateCertificate(certificateDer.inputStream()) as X509Certificate
    }

    /** Alberts echoes the key we sent; if it does not, the certificate is useless to the courier. */
    val certificateMatchesKey: Boolean
        get() = runCatching {
            certificate.publicKey.encoded.contentEquals(keyPair.public.encoded)
        }.getOrDefault(false)

    val subject: String get() = certificate.subjectX500Principal.name

    val notAfter: java.util.Date get() = certificate.notAfter
}

sealed interface ActivationOutcome {

    data class Activated(val device: ActivatedDevice) : ActivationOutcome

    data class Rejected(val failure: ActivationFailure) : ActivationOutcome
}

/** Why activation produced no usable certificate. Each case maps to a different user-facing message. */
class ActivationFailure(
    val reason: Reason,
    val detail: String? = null,
    val httpStatus: Int? = null,
    val cause: Throwable? = null,
) {
    enum class Reason {
        /** The network call itself failed: DNS, TLS, timeout. Retry may help. */
        TRANSPORT_ERROR,

        /** Alberts answered with a non-2xx status. */
        HTTP_ERROR,

        /** 2xx but no `<Protocol>` block — usually an Apple-side rejection or a changed response format. */
        NO_PROTOCOL_BLOCK,

        /** A `<Protocol>` block existed but was not a plist we could read. */
        BAD_PROTOCOL,

        /** The plist had no usable `DeviceCertificate`. */
        NO_CERTIFICATE,

        /** The certificate covers a different key than ours — must not be used. */
        CERTIFICATE_MISMATCH,

        /** We refused to send a request (bad local input) before touching the network. */
        INVALID_REQUEST,
    }

    override fun toString(): String =
        "ActivationFailure($reason${httpStatus?.let { ", http=$it" } ?: ""}${detail?.let { ", $it" } ?: ""})"
}

class ActivationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Dark-half of the identity toolchain: POSTs the signed activation info to Alberts and turns the HTML answer into an
 * [ActivatedDevice].
 *
 * Side-effect free apart from the injected [HttpTransport], so tests exercise all response shapes offline.
 */
class ActivationClient(
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val baseUrl: String = ALBERT_URL,
) {

    fun activate(
        params: DeviceParams,
        keyPair: KeyPair,
        credentials: FairPlayCredentials,
        activationInfo: ActivationInfo = ActivationInfo.create(params, keyPair, credentials),
    ): ActivationOutcome {
        if (!DeviceKey.isDeviceKeySize(keyPair.public)) {
            return ActivationOutcome.Rejected(
                ActivationFailure(
                    ActivationFailure.Reason.INVALID_REQUEST,
                    "device key must be RSA-${DeviceKey.KEY_SIZE_BITS}, got ${DeviceKey.modulusBits(keyPair.public)}",
                ),
            )
        }

        val request = HttpPost(
            url = "$baseUrl?device=${params.deviceQuery}",
            contentType = FORM_CONTENT_TYPE,
            body = activationInfo.formEncoded(),
        )

        val response = try {
            transport.post(request)
        } catch (e: IOException) {
            return ActivationOutcome.Rejected(
                ActivationFailure(ActivationFailure.Reason.TRANSPORT_ERROR, e.message, cause = e),
            )
        } catch (e: RuntimeException) {
            return ActivationOutcome.Rejected(
                ActivationFailure(ActivationFailure.Reason.TRANSPORT_ERROR, e.message, cause = e),
            )
        }

        if (response.status !in 200..299) {
            return ActivationOutcome.Rejected(
                ActivationFailure(
                    ActivationFailure.Reason.HTTP_ERROR,
                    PlistCodec.extractError(response.text) ?: response.text.take(200).trim().ifEmpty { null },
                    httpStatus = response.status,
                ),
            )
        }

        val protocolXml = PlistCodec.extractProtocol(response.text)
            ?: return ActivationOutcome.Rejected(
                ActivationFailure(
                    ActivationFailure.Reason.NO_PROTOCOL_BLOCK,
                    PlistCodec.extractError(response.text),
                ),
            )

        val protocol = try {
            PlistCodec.parse(protocolXml)
        } catch (e: PlistException) {
            return ActivationOutcome.Rejected(
                ActivationFailure(ActivationFailure.Reason.BAD_PROTOCOL, e.message, cause = e),
            )
        }

        val record = activationRecord(protocol)
            ?: return ActivationOutcome.Rejected(
                ActivationFailure(ActivationFailure.Reason.BAD_PROTOCOL, "no activation-record in protocol plist"),
            )

        val certificatePem = deviceCertificatePem(protocol)
            ?: return ActivationOutcome.Rejected(
                ActivationFailure(
                    ActivationFailure.Reason.NO_CERTIFICATE,
                    PlistCodec.extractError(response.text) ?: "protocol plist has no usable DeviceCertificate",
                ),
            )

        val device = try {
            ActivatedDevice(params, keyPair, certificatePem, record)
        } catch (e: Exception) {
            return ActivationOutcome.Rejected(
                ActivationFailure(ActivationFailure.Reason.NO_CERTIFICATE, e.message, cause = e),
            )
        }

        if (!device.certificateMatchesKey) {
            return ActivationOutcome.Rejected(
                ActivationFailure(
                    ActivationFailure.Reason.CERTIFICATE_MISMATCH,
                    "issued certificate does not cover the device key",
                ),
            )
        }

        return ActivationOutcome.Activated(device)
    }

    /** Alberts nests the record (`device-activation.activation-record`); older responses flatten the keys. */
    private fun activationRecord(protocol: PlistValue): PlistValue.Dict? =
        (protocol.at("device-activation", "activation-record") as? PlistValue.Dict)
            ?: (protocol.at("activation-record") as? PlistValue.Dict)
            ?: (protocol as? PlistValue.Dict)?.takeIf { findLeaf(it, "DeviceCertificate") != null }

    /**
     * The issued certificate as PEM.
     *
     * Alberts has answered with `DeviceCertificate` in three shapes over the years: a `<string>` holding PEM, a
     * `<data>` blob holding the same PEM bytes (current responses), and a `<data>` blob holding raw DER. All three are
     * accepted and normalised to PEM here; anything else is "no certificate".
     */
    private fun deviceCertificatePem(protocol: PlistValue): String? {
        val value = findLeaf(protocol, "DeviceCertificate") ?: return null

        value.asString()?.let { return it.takeIf { pem -> pem.contains(PEM_CERTIFICATE_HEADER) } }

        val bytes = value.asBytes() ?: return null
        val decoded = bytes.toString(Charsets.UTF_8)
        if (decoded.contains(PEM_CERTIFICATE_HEADER)) return decoded
        // DER starts with a SEQUENCE tag; wrap it so everything downstream sees PEM.
        return if (bytes.firstOrNull() == 0x30.toByte()) Pem.encode("CERTIFICATE", bytes) else null
    }

    /**
     * Finds a leaf by its key, tolerating both nesting and dotted flattening (`device-activation.activation-record.
     * DeviceCertificate`). Bounded by the response size, which [UrlConnectionTransport] caps.
     */
    private fun findLeaf(value: PlistValue, name: String): PlistValue? = when (value) {
        is PlistValue.Dict -> value.entries[name]
            ?: value.entries.entries.firstNotNullOfOrNull { (key, entry) ->
                when {
                    key.substringAfterLast('.') == name -> entry
                    else -> findLeaf(entry, name)
                }
            }
        is PlistValue.Array -> value.items.firstNotNullOfOrNull { findLeaf(it, name) }
        else -> null
    }

    companion object {
        const val ALBERT_URL: String = "https://albert.apple.com/deviceservices/deviceActivation"

        const val FORM_CONTENT_TYPE: String = "application/x-www-form-urlencoded"

        private const val PEM_CERTIFICATE_HEADER: String = "-----BEGIN CERTIFICATE-----"
    }
}
