package io.github.lichon.abark.apns.transport

import io.github.lichon.abark.apns.AckCommand
import io.github.lichon.abark.apns.ConnectAck
import io.github.lichon.abark.apns.ConnectCommand
import io.github.lichon.abark.apns.Field
import io.github.lichon.abark.apns.FilterCommand
import io.github.lichon.abark.apns.IncomingMessage
import io.github.lichon.abark.apns.KeepAliveCommand
import io.github.lichon.abark.apns.Packet
import io.github.lichon.abark.apns.PacketType
import io.github.lichon.abark.apns.ScopedTokenAck
import io.github.lichon.abark.apns.ScopedTokenCommand
import io.github.lichon.abark.apns.Topics
import io.github.lichon.abark.apns.toHex
import java.io.EOFException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packet source for the session: scripted replies, captured writes, no sockets. */
internal class ScriptedChannel(
    vararg script: Any,
) : CourierChannel {
    private val replies = ArrayDeque<Any>(script.toList())
    val written = mutableListOf<Packet>()
    var closed = false
        private set

    override fun read(timeoutMillis: Int): Packet? {
        if (replies.isEmpty()) throw EOFException("script exhausted")
        return when (val next = replies.removeFirst()) {
            is Timeout -> null
            is Packet -> next
            is Exception -> throw next
            else -> throw IllegalStateException("bad script entry: $next")
        }
    }

    override fun write(packet: Packet) {
        written += packet
    }

    override fun close() {
        closed = true
    }

    /** A keepalive timeout with nothing on the wire. */
    object Timeout
}

private class RecordingListener : CourierListener {
    val baseTokens = mutableListOf<ByteArray>()
    val scopedTokens = mutableListOf<ByteArray>()
    val messages = mutableListOf<IncomingMessage>()
    val unknown = mutableListOf<Packet>()
    var keepAlives = 0

    override fun onBaseToken(token: ByteArray) {
        baseTokens += token
    }

    override fun onScopedToken(token: ByteArray, topicHash: ByteArray) {
        scopedTokens += token
    }

    override fun onMessage(message: IncomingMessage) {
        messages += message
    }

    override fun onUnknownPacket(packet: Packet) {
        unknown += packet
    }

    override fun onKeepAlive() {
        keepAlives++
    }
}

class CourierSessionTest {

    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()

    private val certificate = ByteArray(96) { it.toByte() }
    private val baseToken = ByteArray(32) { 7 }
    private val scopedToken = ByteArray(32) { 9 }
    private val topicHash = Topics.hash(Topics.BARK)

    private fun connectAck(status: Int = 0, token: ByteArray? = baseToken): Packet =
        Packet.of(
            PacketType.CONNECT_ACK,
            *buildList {
                add(Field(1, byteArrayOf(status.toByte())))
                if (token != null) add(Field(3, token))
            }.toTypedArray(),
        )

    private fun scopedAck(status: Int = 0, token: ByteArray? = scopedToken): Packet =
        Packet.of(
            PacketType.SCOPED_TOKEN_ACK,
            *buildList {
                add(Field(1, byteArrayOf(status.toByte())))
                if (token != null) add(Field(2, token))
                add(Field(3, topicHash))
            }.toTypedArray(),
        )

    private fun message(payload: String = "{}"): Packet =
        Packet.of(
            PacketType.SEND_MESSAGE,
            Field(1, scopedToken),
            Field(2, topicHash),
            Field(3, payload.toByteArray()),
            Field(4, byteArrayOf(1, 2, 3, 4)),
        )

    private fun session(
        channel: CourierChannel,
        listener: CourierListener = RecordingListener(),
        cached: ByteArray? = null,
        config: CourierConfig = CourierConfig(keepAliveIntervalMillis = 30_000),
    ) = CourierSession(
        channel = channel,
        config = config,
        certificateDer = certificate,
        privateKey = keyPair.private,
        listener = listener,
        cachedBaseToken = cached,
        clock = { 1_700_000_000_000L },
        random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(11) },
    )

    @Test
    fun `happy path sends connect, filter, scoped token and reports both tokens`() {
        val channel = ScriptedChannel(connectAck(), scopedAck())
        val listener = RecordingListener()
        val result = session(channel, listener).handshake() as CourierHandshake.Ready

        assertEquals(3, channel.written.size)

        val connect = channel.written[0]
        assertEquals(PacketType.CONNECT, connect.type)
        assertArrayEquals(certificate, connect.field(12))
        assertArrayEquals(byteArrayOf(0, 0, 0, 65), connect.field(5))
        // The courier verifies this signature exactly as the test does.
        assertTrue(
            Signature.getInstance("SHA1withRSA").run {
                initVerify(keyPair.public)
                update(connect.field(13))
                verify(connect.field(14)!!.copyOfRange(2, connect.field(14)!!.size))
            },
        )

        val filter = channel.written[1]
        assertEquals(PacketType.FILTER_TOPICS, filter.type)
        assertArrayEquals(baseToken, filter.field(1))
        assertArrayEquals(topicHash, filter.field(2))

        val scoped = channel.written[2]
        assertEquals(PacketType.SCOPED_TOKEN, scoped.type)
        assertArrayEquals(baseToken, scoped.field(1))
        assertArrayEquals(topicHash, scoped.field(2))

        assertArrayEquals(baseToken, result.baseToken)
        assertArrayEquals(scopedToken, result.scopedToken)
        assertArrayEquals(topicHash, result.topicHash)
        assertEquals(1, listener.baseTokens.size)
        assertEquals(1, listener.scopedTokens.size)
        assertTrue(result.earlyMessages.isEmpty())
    }

    @Test
    fun `refused session is reported as an activation problem, not as a network error`() {
        val channel = ScriptedChannel(connectAck(status = 5, token = null))
        val result = session(channel).handshake() as CourierHandshake.Rejected

        assertEquals(CourierFailure.Reason.ACTIVATION_REJECTED, result.failure.reason)
        assertEquals(1, channel.written.size)
    }

    @Test
    fun `accepted without a token and without cache is a protocol error`() {
        val channel = ScriptedChannel(connectAck(token = null))
        val result = session(channel).handshake() as CourierHandshake.Rejected
        assertEquals(CourierFailure.Reason.PROTOCOL, result.failure.reason)
    }

    @Test
    fun `cached token is reused and not reported as new`() {
        val channel = ScriptedChannel(connectAck(token = null), scopedAck())
        val listener = RecordingListener()
        val result = session(channel, listener, cached = baseToken).handshake() as CourierHandshake.Ready

        assertArrayEquals(baseToken, channel.written[0].field(1))
        assertArrayEquals(baseToken, result.baseToken)
        assertTrue("cached token must not be re-persisted", listener.baseTokens.isEmpty())
    }

    @Test
    fun `new base token is reported so it can be persisted`() {
        val fresh = ByteArray(32) { 3 }
        val channel = ScriptedChannel(connectAck(token = fresh), scopedAck())
        val listener = RecordingListener()
        val result = session(channel, listener, cached = baseToken).handshake() as CourierHandshake.Ready

        assertArrayEquals(fresh, result.baseToken)
        assertEquals(1, listener.baseTokens.size)
        assertArrayEquals(fresh, listener.baseTokens.single())
    }

    @Test
    fun `first ever connect omits the base token field`() {
        val channel = ScriptedChannel(connectAck(), scopedAck())
        session(channel).handshake()

        assertNull(channel.written[0].field(1))
        assertEquals(listOf(2, 5, 12, 13, 14), channel.written[0].fields.map { it.id })
    }

    @Test
    fun `refused scoped token is fatal for the attempt`() {
        val channel = ScriptedChannel(connectAck(), scopedAck(status = 1, token = null))
        val result = session(channel).handshake() as CourierHandshake.Rejected

        assertEquals(CourierFailure.Reason.SCOPE_REJECTED, result.failure.reason)
        assertTrue(result.failure.detail!!.contains("status=1"))
    }

    @Test
    fun `message arriving before the scoped token is kept, not dropped`() {
        val channel = ScriptedChannel(connectAck(), message("""{"aps":{"alert":{"body":"early"}}}"""), scopedAck())
        val listener = RecordingListener()
        val result = session(channel, listener).handshake() as CourierHandshake.Ready

        assertEquals(1, result.earlyMessages.size)
        assertEquals("""{"aps":{"alert":{"body":"early"}}}""", String(result.earlyMessages[0].payload))
        // Early messages are not acknowledged before the session is established.
        assertTrue(channel.written.none { it.type == PacketType.SEND_MESSAGE_ACK })
    }

    @Test
    fun `unknown commands during handshake are reported and skipped`() {
        val weird = Packet.unknown(99, listOf(Field(1, byteArrayOf(1))))
        val channel = ScriptedChannel(weird, connectAck(), weird, scopedAck())
        val listener = RecordingListener()
        session(channel, listener).handshake()

        assertEquals(2, listener.unknown.size)
        assertEquals(99, listener.unknown[0].rawType)
    }

    @Test
    fun `quiet line produces keepalives instead of a dead session`() {
        // No keepalive before ConnectAck: Connect must be the first packet, so the quiet line starts after it.
        val channel = ScriptedChannel(
            connectAck(),
            ScriptedChannel.Timeout,
            ScriptedChannel.Timeout,
            scopedAck(),
        )
        val listener = RecordingListener()
        session(channel, listener).handshake()

        assertEquals(2, channel.written.count { it.type == PacketType.KEEP_ALIVE })
        // Server-initiated keepalives get an ack.
        assertTrue(channel.written.none { it.type == PacketType.KEEP_ALIVE_ACK })
    }

    @Test
    fun `server keepalive during handshake is acknowledged`() {
        val channel = ScriptedChannel(KeepAliveCommand.build(), connectAck(), scopedAck())
        session(channel).handshake()
        assertEquals(1, channel.written.count { it.type == PacketType.KEEP_ALIVE_ACK })
    }

    @Test
    fun `receive loop acknowledges every message with token and id`() {
        val channel = ScriptedChannel(connectAck(), scopedAck(), message("{}"), message("{}"))
        val listener = RecordingListener()
        val session = session(channel, listener)
        session.handshake()

        val exit = session.run()

        assertEquals(CourierExit.Failed::class.java, exit.javaClass)
        assertEquals(2, listener.messages.size)
        val acks = channel.written.filter { it.type == PacketType.SEND_MESSAGE_ACK }
        assertEquals(2, acks.size)
        assertArrayEquals(scopedToken, acks[0].field(1))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), acks[0].field(4))
        assertEquals(0, acks[0].field(8)!![0].toInt())
        assertEquals(2L, session.messageCount)
    }

    @Test
    fun `receive loop acks a server keepalive and reports the round trip`() {
        val channel = ScriptedChannel(
            connectAck(),
            scopedAck(),
            KeepAliveCommand.build(),
            KeepAliveCommand.ack(),
        )
        val listener = RecordingListener()
        val session = session(channel, listener)
        session.handshake()
        session.run()

        assertEquals(1, channel.written.count { it.type == PacketType.KEEP_ALIVE_ACK })
        assertEquals(1, listener.keepAlives)
    }

    @Test
    fun `message without a token is a protocol failure, not a crash`() {
        val broken = Packet.of(PacketType.SEND_MESSAGE, Field(2, topicHash), Field(3, "{}".toByteArray()))
        val channel = ScriptedChannel(connectAck(), scopedAck(), broken)
        val session = session(channel)
        session.handshake()

        val exit = session.run() as CourierExit.Failed
        assertEquals(CourierFailure.Reason.PROTOCOL, exit.failure.reason)
    }

    @Test
    fun `closed session stops the loop cleanly`() {
        val channel = ScriptedChannel(connectAck(), scopedAck(), message())
        val session = session(channel)
        session.handshake()

        val exit = session.run()
        assertTrue(exit is CourierExit.Failed)
        session.close()
        assertTrue(channel.closed)
        assertEquals(CourierExit.Closed, session.run())
    }

    @Test
    fun `no scoped token ack within the slack window is a protocol error`() {
        val script = ArrayList<Any>()
        repeat(4) { script += KeepAliveCommand.build() }
        val channel = ScriptedChannel(connectAck(), *script.toTypedArray())
        val result = session(channel, config = CourierConfig(handshakeSlack = 3)).handshake()

        result as CourierHandshake.Rejected
        assertEquals(CourierFailure.Reason.PROTOCOL, result.failure.reason)
    }

    @Test
    fun `eof while waiting for the connect ack is reported as closed`() {
        val channel = ScriptedChannel(EOFException("reset"))
        val result = session(channel).handshake() as CourierHandshake.Rejected

        assertEquals(CourierFailure.Reason.CLOSED, result.failure.reason)
        // Connect was already on the wire when the reset arrived — that is the honest state.
        assertEquals(1, channel.written.size)
        assertEquals(PacketType.CONNECT, channel.written.single().type)
    }

    @Test
    fun `filter and scoped token carry exactly the bark topic hash`() {
        val channel = ScriptedChannel(connectAck(), scopedAck())
        session(channel).handshake()

        assertArrayEquals(
            "667bc4834b4efda62efc2a77d37ad5e1c9703b67".chunkedHex(),
            channel.written[1].field(2),
        )
        assertEquals(FilterCommand.build(baseToken, listOf(Topics.BARK)).fields.size, channel.written[1].fields.size)
        assertEquals(ScopedTokenCommand.build(baseToken, Topics.BARK).fields.size, channel.written[2].fields.size)
        // Sanity: the scoped token we report is the one the ack carried.
        assertNotNull(ScopedTokenAck.parse(scopedAck()).scopedToken)
        assertFalse(channel.written[2].field(1)!!.contentEquals(byteArrayOf(0)))
        assertEquals("0707".chunkedHex().size, 2)
        assertEquals("0707", baseToken.copyOf(2).toHex())
    }

    private fun String.chunkedHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class BackoffPolicyTest {

    @Test
    fun `delay grows exponentially and stays inside the cap`() {
        val policy = BackoffPolicy(initialMillis = 1_000, maxMillis = 8_000, factor = 2.0, jitterFraction = 0.0)
        assertEquals(1_000, policy.delayFor(1))
        assertEquals(2_000, policy.delayFor(2))
        assertEquals(4_000, policy.delayFor(3))
        assertEquals(8_000, policy.delayFor(4))
        assertEquals(8_000, policy.delayFor(40))
    }

    @Test
    fun `jitter keeps the delay within twenty percent of the base`() {
        val policy = BackoffPolicy(initialMillis = 1_000, maxMillis = 1_000, factor = 2.0, jitterFraction = 0.2)
        repeat(50) {
            val delay = policy.delayFor(1)
            assertTrue("delay out of range: $delay", delay in 800..1_200)
        }
    }
}
