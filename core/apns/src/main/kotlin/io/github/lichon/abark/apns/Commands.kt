package io.github.lichon.abark.apns

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature

/** Topic helpers. APNs filters on `sha1(topic)`, and topics are bundle-id shaped. */
object Topics {
    /** The topic a stock `bark-server` sends to (see apns/apns.go). */
    const val BARK: String = "me.fin.bark"

    fun hash(topic: String): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(topic.toByteArray(Charsets.UTF_8))
}

/**
 * `Connect` (type 7) — first command after the TLS handshake (ALPN `apns-security-v3`).
 *
 * The device proves possession of the Alberts-issued key by signing a nonce; the certificate itself travels in
 * field 12. `state` and `flags` were recovered empirically (see docs/PROTOCOL.md): 1 and 65 work today.
 */
object ConnectCommand {

    const val STATE: Int = 1
    const val FLAGS: Int = 65

    /** `0x00 | nowMillis:u64be | 8 random bytes` — matches the observed client layout. */
    fun nonce(nowMillis: Long, random: ByteArray): ByteArray {
        require(random.size == 8) { "nonce randomness must be 8 bytes, got ${random.size}" }
        return byteArrayOf(0x00) + nowMillis.toByteArray(8) + random
    }

    /** `0x01 0x01 | RSA-PKCS1v1.5-SHA1(nonce)` — the two leading bytes are constant in captured traffic. */
    fun signature(key: PrivateKey, nonce: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(key)
        signer.update(nonce)
        return byteArrayOf(0x01, 0x01) + signer.sign()
    }

    fun build(
        baseToken: ByteArray?,
        certificateDer: ByteArray,
        key: PrivateKey,
        nonce: ByteArray,
        flags: Int = FLAGS,
    ): Packet {
        val fields = ArrayList<Field>(6)
        if (baseToken != null && baseToken.isNotEmpty()) {
            fields += Field(1, baseToken)
        }
        fields += Field(2, STATE.toByteArray(1))
        fields += Field(5, flags.toByteArray(4))
        fields += Field(12, certificateDer)
        fields += Field(13, nonce)
        fields += Field(14, signature(key, nonce))
        return Packet.of(PacketType.CONNECT, fields)
    }
}

/** Server reply to [ConnectCommand]: `status` 0 means accepted; `token` is absent when the sent token was reused. */
class ConnectAck(val status: Int, val token: ByteArray?) {

    val isAccepted: Boolean get() = status == 0

    override fun toString(): String =
        "ConnectAck(status=$status, token=${token?.toHex()?.take(16)})"

    companion object {
        fun parse(packet: Packet): ConnectAck {
            if (packet.type != PacketType.CONNECT_ACK) {
                throw ProtocolException("expected CONNECT_ACK, got ${packet.type}")
            }
            val status = packet.field(1)?.toIntUnsigned() ?: 0
            return ConnectAck(status, packet.field(3)?.takeIf { it.isNotEmpty() })
        }
    }
}

/** `FilterTopics` (type 9): the set of topics this connection wants delivered. */
object FilterCommand {

    fun build(baseToken: ByteArray, topics: List<String>): Packet =
        buildHashed(baseToken, topics.map(Topics::hash))

    fun buildHashed(baseToken: ByteArray, topicHashes: List<ByteArray>): Packet {
        val fields = ArrayList<Field>(1 + topicHashes.size)
        fields += Field(1, baseToken)
        for (hash in topicHashes) fields += Field(2, hash)
        return Packet.of(PacketType.FILTER_TOPICS, fields)
    }

    fun parse(packet: Packet): List<ByteArray> = packet.fieldsOf(2)
}

/** `ScopedToken` (type 17): mint a per-topic token, which is the thing you hand to a push sender. */
object ScopedTokenCommand {

    fun build(baseToken: ByteArray, topic: String): Packet =
        Packet.of(PacketType.SCOPED_TOKEN, Field(1, baseToken), Field(2, Topics.hash(topic)))
}

class ScopedTokenAck(
    val status: Int,
    val scopedToken: ByteArray?,
    val topicHash: ByteArray?,
) {
    val isAccepted: Boolean get() = status == 0 && scopedToken != null

    override fun toString(): String =
        "ScopedTokenAck(status=$status, token=${scopedToken?.toHex()?.take(16)}, topic=${topicHash?.toHex()?.take(16)})"

    companion object {
        fun parse(packet: Packet): ScopedTokenAck {
            if (packet.type != PacketType.SCOPED_TOKEN_ACK) {
                throw ProtocolException("expected SCOPED_TOKEN_ACK, got ${packet.type}")
            }
            return ScopedTokenAck(
                status = packet.field(1)?.toIntUnsigned() ?: -1,
                scopedToken = packet.field(2)?.takeIf { it.isNotEmpty() },
                topicHash = packet.field(3)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** An inbound push. `payload` is the raw JSON body that `bark-server` built. */
class IncomingMessage(
    val token: ByteArray,
    val topicHash: ByteArray,
    val payload: ByteArray,
    val messageId: ByteArray,
) {
    override fun toString(): String =
        "IncomingMessage(token=${token.toHex().take(16)}, topic=${topicHash.toHex().take(16)}, " +
            "id=${messageId.toHex().take(16)}, payload=${payload.size}B)"

    companion object {
        fun parse(packet: Packet): IncomingMessage {
            if (packet.type != PacketType.SEND_MESSAGE) {
                throw ProtocolException("expected SEND_MESSAGE, got ${packet.type}")
            }
            return IncomingMessage(
                token = packet.field(1) ?: throw ProtocolException("message without token"),
                topicHash = packet.field(2) ?: throw ProtocolException("message without topic"),
                payload = packet.field(3) ?: ByteArray(0),
                messageId = packet.field(4) ?: ByteArray(0),
            )
        }
    }
}

/**
 * `SendMessageAck` (type 11) — APNs expects every delivered message to be acknowledged; an unanswered connection is
 * eventually throttled or dropped.
 */
object AckCommand {

    fun build(token: ByteArray, messageId: ByteArray, status: Int = 0): Packet =
        Packet.of(
            PacketType.SEND_MESSAGE_ACK,
            Field(1, token),
            Field(4, messageId),
            Field(8, status.toByteArray(1)),
        )
}

/** `KeepAlive` (12) / `KeepAliveAck` (13) — keeps NAT bindings and the session alive. */
object KeepAliveCommand {

    fun build(): Packet = Packet.of(PacketType.KEEP_ALIVE)

    /**
     * Reply to a server-initiated keepalive. Captured traffic shows an empty ack; field 1 exists in some traces but we
     * have not found a case where it matters (docs/PROTOCOL.md §"To measure").
     */
    fun ack(): Packet = Packet.of(PacketType.KEEP_ALIVE_ACK)

    fun isAck(packet: Packet): Boolean = packet.type == PacketType.KEEP_ALIVE_ACK
}

internal fun ByteArray.toIntUnsigned(): Int {
    var value = 0
    for (byte in this) value = (value shl 8) or (byte.toInt() and 0xFF)
    return value
}
