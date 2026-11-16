package io.github.lichon.abark.apns.transport

import io.github.lichon.abark.apns.Packet
import java.io.Closeable
import java.security.PrivateKey

/**
 * A duplex packet channel. The courier is framed (see [io.github.lichon.abark.apns.PacketCodec]), so a channel only
 * has to move whole packets and to be able to *time out* on a read: APNs is idle for minutes and the timeout is how we
 * know it is time to send a keepalive.
 *
 * Kept separate from the TLS socket so the session state machine can be driven by a scripted in-memory channel in
 * tests — the reconnect/ack paths are where the bugs live, and they must not need a network or an Apple certificate.
 */
interface CourierChannel : Closeable {
    /** One packet, or null when nothing arrived within [timeoutMillis]. */
    fun read(timeoutMillis: Int): Packet?

    fun write(packet: Packet)
}

fun interface CourierChannelFactory {
    fun open(host: String, port: Int): CourierChannel
}

/** The client certificate + key the courier authenticates us by. */
class CourierCredentials(
    val certificateDer: ByteArray,
    val privateKey: PrivateKey,
)

class CourierFailure(
    val reason: Reason,
    val detail: String? = null,
    val cause: Throwable? = null,
) {
    enum class Reason {
        /** Socket-level: DNS, TLS, timeout, EOF. Retry on another host. */
        TRANSPORT,

        /** `ConnectAck.status != 0` — the certificate was refused. Retrying will not help. */
        ACTIVATION_REJECTED,

        /** `ScopedTokenAck` came back without a token. */
        SCOPE_REJECTED,

        /** A packet was malformed or missing a field we cannot work without. */
        PROTOCOL,

        /** The remote end closed the session. */
        CLOSED,
    }

    override fun toString(): String = "CourierFailure($reason${detail?.let { ", $it" } ?: ""})"
}

/** Everything the session tells the outside world. All callbacks run on the session's own thread. */
interface CourierListener {
    /** A new base token was minted (or the first one received) and must be persisted. */
    fun onBaseToken(token: ByteArray) {}

    /** Scoped token for a topic; this is what the push server is told to target. */
    fun onScopedToken(token: ByteArray, topicHash: ByteArray) {}

    fun onMessage(message: io.github.lichon.abark.apns.IncomingMessage) {}

    /** A keepalive round-trip completed. */
    fun onKeepAlive() {}

    /** A command we do not model. Must never be fatal (Apple adds commands over time). */
    fun onUnknownPacket(packet: Packet) {}
}
