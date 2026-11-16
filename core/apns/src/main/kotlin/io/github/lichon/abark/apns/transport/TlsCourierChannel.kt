package io.github.lichon.abark.apns.transport

import io.github.lichon.abark.apns.Packet
import io.github.lichon.abark.apns.PacketCodec
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The courier is authenticated **by the client certificate**, not by the server's hostname: Apple's push courier
 * presents certificates from an internal PKI that no public trust store knows about, and the identity of the server is
 * pinned by the connection parameters instead (fixed host pattern, fixed port, ALPN, and the Connect handshake which
 * only works if the other end really is APNs).
 *
 * So verification is not disabled globally — it is scoped to exactly this socket, which is the only place where it is
 * acceptable (PLAN.md §4 "Trust", docs/PROTOCOL.md §Transport).
 */
object CourierTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * TLS 1.2+ socket with SNI and ALPN, carrying our device certificate.
 *
 * ALPN negotiation is recorded rather than enforced: if Apple ever stops offering `apns-security-v3`, the handshake
 * still completes and the failure shows up as a `ConnectAck` status instead of an opaque TLS error.
 */
class TlsCourierChannelFactory(
    private val credentials: CourierCredentials,
    private val port: Int = 5223,
    private val connectTimeoutMillis: Int = 15_000,
    private val alpnProtocols: List<String> = listOf(ALPN_V3, ALPN_V2),
    private val trustManager: X509TrustManager = CourierTrustManager,
    private val onNegotiatedProtocol: (String?) -> Unit = {},
) : CourierChannelFactory {

    override fun open(host: String, port: Int): CourierChannel =
        SocketCourierChannel(connect(host, port))

    private fun connect(host: String, port: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers(), arrayOf(trustManager), SecureRandom())
        }
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        socket.sslParameters = socket.sslParameters.apply {
            applicationProtocols = alpnProtocols.toTypedArray()
            // The certificate chain is the credential; hostname verification would only reject Apple's own servers.
            endpointIdentificationAlgorithm = null
            this.protocols = this.protocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }
                .ifEmpty { this.protocols.toList() }
                .toTypedArray()
        }
        socket.startHandshake()
        onNegotiatedProtocol(socket.applicationProtocol)
        return socket
    }

    private fun keyManagers(): Array<javax.net.ssl.KeyManager> {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(credentials.certificateDer)) as X509Certificate
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(KEY_ALIAS, credentials.privateKey, PASSWORD, arrayOf(certificate))
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            init(keyStore, PASSWORD)
            keyManagers
        }
    }

    companion object {
        const val ALPN_V3: String = "apns-security-v3"
        const val ALPN_V2: String = "apns-security-v2"
        const val DEFAULT_PORT: Int = 5223
        private const val KEY_ALIAS = "device"
        private val PASSWORD = CharArray(0)
    }
}

/**
 * [CourierChannel] over an [SSLSocket].
 *
 * The socket timeout is used as the keepalive clock. Only the *first* byte is read under the keepalive timeout; once a
 * packet has started arriving the rest is read under a longer timeout, so a slow-but-real packet is never mistaken for
 * an idle line (which would desynchronise the frame stream).
 */
class SocketCourierChannel(
    private val socket: SSLSocket,
    private val packetTimeoutMillis: Int = 60_000,
) : CourierChannel {

    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun read(timeoutMillis: Int): Packet? {
        socket.soTimeout = timeoutMillis
        val first = try {
            input.read()
        } catch (e: SocketTimeoutException) {
            return null
        }
        if (first < 0) throw EOFException("courier closed the connection")

        socket.soTimeout = packetTimeoutMillis
        val stream = SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), input)
        return PacketCodec.read(stream)
    }

    override fun write(packet: Packet) = PacketCodec.write(output, packet)

    /** Negotiated ALPN protocol, for the diagnostics screen. */
    val negotiatedProtocol: String? get() = socket.applicationProtocol

    override fun close() = socket.close()
}

/** A plain TCP [Socket]-backed channel, used by `tools/mock-courier` and by tests that fake TLS. */
class PlainSocketCourierChannel(
    private val socket: Socket,
    private val packetTimeoutMillis: Int = 60_000,
) : CourierChannel {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(timeoutMillis: Int): Packet? {
        socket.soTimeout = timeoutMillis
        val first = try {
            input.read()
        } catch (e: SocketTimeoutException) {
            return null
        }
        if (first < 0) throw EOFException("peer closed the connection")
        socket.soTimeout = packetTimeoutMillis
        return PacketCodec.read(SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), input))
    }

    override fun write(packet: Packet) = PacketCodec.write(output, packet)

    override fun close() = socket.close()
}
