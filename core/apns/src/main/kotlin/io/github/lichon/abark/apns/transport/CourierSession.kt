package io.github.lichon.abark.apns.transport

import io.github.lichon.abark.apns.AckCommand
import io.github.lichon.abark.apns.ConnectAck
import io.github.lichon.abark.apns.ConnectCommand
import io.github.lichon.abark.apns.FilterCommand
import io.github.lichon.abark.apns.IncomingMessage
import io.github.lichon.abark.apns.KeepAliveCommand
import io.github.lichon.abark.apns.Packet
import io.github.lichon.abark.apns.PacketType
import io.github.lichon.abark.apns.ProtocolException
import io.github.lichon.abark.apns.ScopedTokenAck
import io.github.lichon.abark.apns.ScopedTokenCommand
import io.github.lichon.abark.apns.Topics
import java.io.EOFException
import java.security.PrivateKey
import java.security.SecureRandom

/**
 * Settings that we may have to tune from the field. Defaults are the conservative ones: a 2-minute keepalive is
 * frequent enough for NAT bindings and sparse enough not to matter for battery (docs/PROTOCOL.md §"To measure").
 */
data class CourierConfig(
    val topics: List<String> = listOf(Topics.BARK),
    val keepAliveIntervalMillis: Int = 120_000,
    val connectTimeoutMillis: Int = 15_000,
    /** Timeout for a packet that has started arriving; must not be confused with the keepalive timeout. */
    val packetTimeoutMillis: Int = 60_000,
    val flags: Int = ConnectCommand.FLAGS,
    val state: Int = ConnectCommand.STATE,
    /** How many packets a handshake may skip while waiting for the reply it wants. */
    val handshakeSlack: Int = 32,
) {
    init {
        require(topics.isNotEmpty()) { "at least one topic is required" }
        require(keepAliveIntervalMillis > 0) { "keepalive interval must be positive" }
    }
}

/** Result of the connect → filter → scoped-token exchange. */
sealed interface CourierHandshake {
    data class Ready(
        val baseToken: ByteArray,
        val scopedToken: ByteArray?,
        val topicHash: ByteArray?,
        /** Messages that arrived before the scoped token did; they are still delivered in order. */
        val earlyMessages: List<IncomingMessage>,
    ) : CourierHandshake

    data class Rejected(val failure: CourierFailure) : CourierHandshake
}

sealed interface CourierExit {
    /** Clean shutdown from our side ([close]); a reconnect is not wanted. */
    data object Closed : CourierExit

    data class Failed(val failure: CourierFailure) : CourierExit
}

/**
 * The courier session state machine, exactly as documented in docs/PROTOCOL.md §"Session sequence":
 *
 * ```
 * Connect ─► ConnectAck ─► FilterTopics ─► ScopedToken ─► ScopedTokenAck ─► receive loop
 * ```
 *
 * Deliberately single-threaded and blocking: one owner thread, no locks, no shared mutable state outside this class.
 * The receive loop uses read timeouts as its clock, so a keepalive is sent whenever the courier has been quiet for
 * [CourierConfig.keepAliveIntervalMillis] — there is no timer thread to leak when the service is killed.
 */
class CourierSession(
    private val channel: CourierChannel,
    private val config: CourierConfig,
    private val certificateDer: ByteArray,
    private val privateKey: PrivateKey,
    private val listener: CourierListener,
    /** Cached base token from a previous session; null on the first ever connect. */
    private val cachedBaseToken: ByteArray? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {

    private var closed = false
    private var keepAlivesSent = 0L
    private var messagesReceived = 0L

    /** Number of keepalives sent since the session started — surfaced in diagnostics. */
    val keepAliveCount: Long get() = keepAlivesSent

    val messageCount: Long get() = messagesReceived

    fun handshake(): CourierHandshake {
        val nonce = ConnectCommand.nonce(clock(), ByteArray(8).also(random::nextBytes))
        val connect = try {
            ConnectCommand.build(cachedBaseToken, certificateDer, privateKey, nonce, config.flags)
        } catch (e: Exception) {
            return CourierHandshake.Rejected(
                CourierFailure(CourierFailure.Reason.PROTOCOL, "cannot build Connect: ${e.message}", e),
            )
        }

        val earlyMessages = ArrayList<IncomingMessage>()
        val baseToken = try {
            write(connect)
            readConnectAck()
        } catch (e: EOFException) {
            return CourierHandshake.Rejected(
                CourierFailure(CourierFailure.Reason.CLOSED, "courier closed during Connect", e),
            )
        } catch (e: ProtocolException) {
            return CourierHandshake.Rejected(
                CourierFailure(CourierFailure.Reason.PROTOCOL, e.message, e),
            )
        } catch (e: Exception) {
            return CourierHandshake.Rejected(
                CourierFailure(CourierFailure.Reason.TRANSPORT, e.message, e),
            )
        }

        if (baseToken == null) {
            return CourierHandshake.Rejected(
                CourierFailure(CourierFailure.Reason.ACTIVATION_REJECTED, "ConnectAck refused the session"),
            )
        }

        try {
            write(FilterCommand.build(baseToken, config.topics))
            write(ScopedTokenCommand.build(baseToken, config.topics.first()))
        } catch (e: Exception) {
            return CourierHandshake.Rejected(
                CourierFailure(CourierFailure.Reason.TRANSPORT, "cannot send filter/scoped-token: ${e.message}", e),
            )
        }

        // The scoped-token reply can be preceded by a message or a keepalive: skip them, keep the messages.
        var slack = config.handshakeSlack
        while (true) {
            if (slack-- <= 0) {
                return CourierHandshake.Rejected(
                    CourierFailure(CourierFailure.Reason.PROTOCOL, "no ScopedTokenAck after ${config.handshakeSlack} packets"),
                )
            }
            val packet = try {
                read()
            } catch (e: EOFException) {
                return CourierHandshake.Rejected(
                    CourierFailure(CourierFailure.Reason.CLOSED, "courier closed before ScopedTokenAck", e),
                )
            } catch (e: Exception) {
                return CourierHandshake.Rejected(
                    CourierFailure(CourierFailure.Reason.TRANSPORT, e.message, e),
                )
            } ?: continue

            when (packet.type) {
                PacketType.SCOPED_TOKEN_ACK -> {
                    val ack = try {
                        ScopedTokenAck.parse(packet)
                    } catch (e: ProtocolException) {
                        return CourierHandshake.Rejected(
                            CourierFailure(CourierFailure.Reason.PROTOCOL, e.message, e),
                        )
                    }
                    if (!ack.isAccepted) {
                        return CourierHandshake.Rejected(
                            CourierFailure(
                                CourierFailure.Reason.SCOPE_REJECTED,
                                "ScopedTokenAck status=${ack.status}",
                            ),
                        )
                    }
                    val scoped = ack.scopedToken!!
                    listener.onScopedToken(scoped, ack.topicHash ?: Topics.hash(config.topics.first()))
                    return CourierHandshake.Ready(baseToken, scoped, ack.topicHash, earlyMessages)
                }

                PacketType.SEND_MESSAGE -> earlyMessages += IncomingMessage.parse(packet)

                PacketType.KEEP_ALIVE -> write(KeepAliveCommand.ack())

                else -> listener.onUnknownPacket(packet)
            }
        }
    }

    /**
     * Receive loop. Returns only when the channel dies or [close] is called. Every message is acknowledged: an
     * unacknowledged stream gets throttled and then dropped by APNs.
     */
    fun run(): CourierExit {
        while (!closed) {
            val packet = try {
                read()
            } catch (e: EOFException) {
                if (closed) return CourierExit.Closed
                return CourierExit.Failed(
                    CourierFailure(CourierFailure.Reason.CLOSED, "courier closed the connection", e),
                )
            } catch (e: ProtocolException) {
                return CourierExit.Failed(CourierFailure(CourierFailure.Reason.PROTOCOL, e.message, e))
            } catch (e: Exception) {
                return CourierExit.Failed(CourierFailure(CourierFailure.Reason.TRANSPORT, e.message, e))
            }

            // `null` only happens when close() was called; keepalives are handled inside read().
            if (packet == null) return CourierExit.Closed

            try {
                when (packet.type) {
                    PacketType.SEND_MESSAGE -> {
                        val message = IncomingMessage.parse(packet)
                        write(AckCommand.build(message.token, message.messageId))
                        messagesReceived++
                        listener.onMessage(message)
                    }

                    PacketType.KEEP_ALIVE -> write(KeepAliveCommand.ack())

                    PacketType.KEEP_ALIVE_ACK -> listener.onKeepAlive()

                    PacketType.NO_STORAGE -> listener.onUnknownPacket(packet)

                    else -> listener.onUnknownPacket(packet)
                }
            } catch (e: ProtocolException) {
                return CourierExit.Failed(CourierFailure(CourierFailure.Reason.PROTOCOL, e.message, e))
            } catch (e: Exception) {
                return CourierExit.Failed(CourierFailure(CourierFailure.Reason.TRANSPORT, e.message, e))
            }
        }
        return CourierExit.Closed
    }

    fun close() {
        closed = true
        runCatching { channel.close() }
    }

    /** Reads one packet, sending a keepalive for every quiet interval. Null only when [closed]. */
    private fun read(): Packet? {
        while (true) {
            val packet = channel.read(config.keepAliveIntervalMillis)
            if (packet != null) return packet
            if (closed) return null
            write(KeepAliveCommand.build())
            keepAlivesSent++
        }
    }

    /** Returns the base token, or null when the courier refused us. */
    private fun readConnectAck(): ByteArray? {
        repeat(config.handshakeSlack) {
            val packet = channel.read(config.connectTimeoutMillis)
                ?: throw ProtocolException("no ConnectAck from courier")
            when (packet.type) {
                PacketType.CONNECT_ACK -> {
                    val ack = ConnectAck.parse(packet)
                    if (!ack.isAccepted) return null
                    val token = ack.token ?: cachedBaseToken
                    if (token == null) throw ProtocolException("ConnectAck accepted without a base token")
                    if (!token.contentEquals(cachedBaseToken ?: ByteArray(0))) listener.onBaseToken(token)
                    return token
                }
                PacketType.KEEP_ALIVE -> write(KeepAliveCommand.ack())
                else -> listener.onUnknownPacket(packet)
            }
        }
        throw ProtocolException("no ConnectAck within ${config.handshakeSlack} packets")
    }

    private fun write(packet: Packet) {
        if (closed) throw EOFException("session closed")
        channel.write(packet)
    }
}
