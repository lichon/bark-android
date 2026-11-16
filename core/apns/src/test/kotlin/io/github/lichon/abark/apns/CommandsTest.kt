package io.github.lichon.abark.apns

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun rsaKeyPair(bits: Int = 1024): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

class CommandsTest {

    @Test
    fun `topic hash matches the documented value`() {
        // sha1("me.fin.bark") — the topic a stock bark-server pushes to.
        assertEquals(
            "667bc4834b4efda62efc2a77d37ad5e1c9703b67",
            Topics.hash(Topics.BARK).toHex(),
        )
    }

    @Test
    fun `nonce is marker, millis, then 8 random bytes`() {
        val random = ByteArray(8) { 0xAA.toByte() }
        val nonce = ConnectCommand.nonce(0x0102030405060708L, random)
        assertEquals(17, nonce.size)
        assertEquals(0x00, nonce[0].toInt())
        assertEquals("0102030405060708", nonce.copyOfRange(1, 9).toHex())
        assertEquals("aaaaaaaaaaaaaaaa", nonce.copyOfRange(9, 17).toHex())
    }

    @Test
    fun `connect carries the documented fields and a verifiable signature`() {
        val keyPair = rsaKeyPair()
        val certificate = ByteArray(64) { it.toByte() }
        val baseToken = ByteArray(32) { 5 }
        val nonce = ConnectCommand.nonce(1_700_000_000_000L, ByteArray(8) { 1 })

        val packet = ConnectCommand.build(baseToken, certificate, keyPair.private, nonce)
        assertEquals(PacketType.CONNECT, packet.type)
        assertEquals(listOf(1, 2, 5, 12, 13, 14), packet.fields.map { it.id })

        assertArrayEquals(baseToken, packet.field(1))
        assertArrayEquals(byteArrayOf(1), packet.field(2))
        assertArrayEquals(byteArrayOf(0, 0, 0, 65), packet.field(5))
        assertArrayEquals(certificate, packet.field(12))
        assertArrayEquals(nonce, packet.field(13))

        val signature = packet.field(14)!!
        assertEquals(2 + 128, signature.size) // 0x01 0x01 + RSA-1024 signature
        assertArrayEquals(byteArrayOf(1, 1), signature.copyOfRange(0, 2))

        val verifier = Signature.getInstance("SHA1withRSA").apply {
            initVerify(keyPair.public)
            update(nonce)
        }
        assertTrue("signature must verify against the device key", verifier.verify(signature.copyOfRange(2, signature.size)))
    }

    @Test
    fun `first connect omits the push token field`() {
        val keyPair = rsaKeyPair()
        val packet = ConnectCommand.build(null, ByteArray(4), keyPair.private, ByteArray(17))
        assertEquals(listOf(2, 5, 12, 13, 14), packet.fields.map { it.id })
        // An empty token must be omitted too, not sent as a zero-length field.
        val empty = ConnectCommand.build(ByteArray(0), ByteArray(4), keyPair.private, ByteArray(17))
        assertEquals(listOf(2, 5, 12, 13, 14), empty.fields.map { it.id })
    }

    @Test
    fun `parses connect ack with and without a token`() {
        val withToken = ConnectAck.parse(
            Packet.of(PacketType.CONNECT_ACK, Field(1, byteArrayOf(0)), Field(3, ByteArray(32) { 9 })),
        )
        assertTrue(withToken.isAccepted)
        assertEquals(32, withToken.token!!.size)

        val reused = ConnectAck.parse(Packet.of(PacketType.CONNECT_ACK, Field(1, byteArrayOf(0))))
        assertTrue(reused.isAccepted)
        assertNull("absent token means the sent token was accepted", reused.token)

        val rejected = ConnectAck.parse(Packet.of(PacketType.CONNECT_ACK, Field(1, byteArrayOf(3))))
        assertEquals(3, rejected.status)
        assertTrue(!rejected.isAccepted)
    }

    @Test
    fun `filter topics sends the token then one hash per topic`() {
        val baseToken = ByteArray(32) { 3 }
        val packet = FilterCommand.build(baseToken, listOf(Topics.BARK, "com.apple.madrid"))
        assertEquals(PacketType.FILTER_TOPICS, packet.type)
        assertEquals(listOf(1, 2, 2), packet.fields.map { it.id })
        assertArrayEquals(baseToken, packet.field(1))
        assertEquals(2, FilterCommand.parse(packet).size)
        assertArrayEquals(Topics.hash(Topics.BARK), FilterCommand.parse(packet)[0])
    }

    @Test
    fun `scoped token request hashes the topic and its ack is parsed`() {
        val baseToken = ByteArray(32) { 4 }
        val request = ScopedTokenCommand.build(baseToken, Topics.BARK)
        assertEquals(PacketType.SCOPED_TOKEN, request.type)
        assertArrayEquals(Topics.hash(Topics.BARK), request.field(2))

        val ack = ScopedTokenAck.parse(
            Packet.of(
                PacketType.SCOPED_TOKEN_ACK,
                Field(1, byteArrayOf(0)),
                Field(2, ByteArray(32) { 8 }),
                Field(3, Topics.hash(Topics.BARK)),
            ),
        )
        assertTrue(ack.isAccepted)
        assertEquals(64, ack.scopedToken!!.toHex().length)
        assertArrayEquals(Topics.hash(Topics.BARK), ack.topicHash)
    }

    @Test
    fun `scoped token failure is not accepted`() {
        val ack = ScopedTokenAck.parse(Packet.of(PacketType.SCOPED_TOKEN_ACK, Field(1, byteArrayOf(5))))
        assertEquals(5, ack.status)
        assertTrue(!ack.isAccepted)
    }

    @Test
    fun `inbound message is parsed and acknowledged`() {
        val scopedToken = ByteArray(32) { 7 }
        val payload = """{"aps":{"alert":{"title":"hi","body":"there"}},"url":"https://example.test"}"""
            .toByteArray()
        val messageId = ByteArray(16) { 0x0F }

        val message = IncomingMessage.parse(
            Packet.of(
                PacketType.SEND_MESSAGE,
                Field(1, scopedToken),
                Field(2, Topics.hash(Topics.BARK)),
                Field(3, payload),
                Field(4, messageId),
            ),
        )
        assertArrayEquals(scopedToken, message.token)
        assertArrayEquals(Topics.hash(Topics.BARK), message.topicHash)
        assertEquals(payload.size, message.payload.size)
        assertArrayEquals(messageId, message.messageId)

        val ack = AckCommand.build(message.token, message.messageId)
        assertEquals(PacketType.SEND_MESSAGE_ACK, ack.type)
        assertEquals(listOf(1, 4, 8), ack.fields.map { it.id })
        assertArrayEquals(byteArrayOf(0), ack.field(8))
    }

    @Test
    fun `malformed messages fail loudly rather than delivering garbage`() {
        org.junit.Assert.assertThrows(ProtocolException::class.java) {
            IncomingMessage.parse(Packet.of(PacketType.SEND_MESSAGE, Field(2, ByteArray(20))))
        }
        org.junit.Assert.assertThrows(ProtocolException::class.java) {
            ConnectAck.parse(Packet.of(PacketType.KEEP_ALIVE_ACK))
        }
    }

    @Test
    fun `keepalive has no fields and is distinguishable from its ack`() {
        val keepAlive = KeepAliveCommand.build()
        assertEquals(PacketType.KEEP_ALIVE, keepAlive.type)
        assertTrue(keepAlive.fields.isEmpty())
        assertTrue(KeepAliveCommand.isAck(Packet.of(PacketType.KEEP_ALIVE_ACK)))
        assertTrue(!KeepAliveCommand.isAck(keepAlive))
    }
}
