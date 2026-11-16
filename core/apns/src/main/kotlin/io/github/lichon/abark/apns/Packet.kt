package io.github.lichon.abark.apns

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * APNs courier wire format (reverse-engineered, see docs/PROTOCOL.md):
 *
 * ```
 * packet := type:u8 | payloadLength:u32be | field*
 * field  := id:u8   | valueLength:u16be  | value
 * ```
 *
 * Field ids are per command type and repeat (e.g. one field id 2 per enabled topic).
 */
class Field(val id: Int, val value: ByteArray) {
    init {
        require(id in 0..0xFF) { "field id out of range: $id" }
        require(value.size <= PacketCodec.MAX_FIELD_LENGTH) { "field $id too long: ${value.size}" }
    }

    override fun equals(other: Any?): Boolean =
        other is Field && other.id == id && other.value.contentEquals(value)

    override fun hashCode(): Int = 31 * id + value.contentHashCode()

    override fun toString(): String =
        "Field(id=$id, len=${value.size}${if (value.size <= 16) ", value=${value.toHex()}" else ""})"
}

enum class PacketType(val code: Int) {
    CONNECT(7),
    CONNECT_ACK(8),
    FILTER_TOPICS(9),
    SEND_MESSAGE(10),
    SEND_MESSAGE_ACK(11),
    KEEP_ALIVE(12),
    KEEP_ALIVE_ACK(13),
    NO_STORAGE(14),
    SCOPED_TOKEN(17),
    SCOPED_TOKEN_ACK(18),
    SET_STATE(20),

    /** A command we do not model yet; Apple adds these over time, so it must not be fatal. */
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): PacketType = entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
    }
}

class Packet internal constructor(
    val type: PacketType,
    val rawType: Int,
    val fields: List<Field>,
) {
    fun field(id: Int): ByteArray? = fields.firstOrNull { it.id == id }?.value

    fun fieldsOf(id: Int): List<ByteArray> = fields.filter { it.id == id }.map { it.value }

    override fun toString(): String = "Packet(${type.name}/${rawType}, $fields)"

    companion object {
        fun of(type: PacketType, vararg fields: Field): Packet =
            Packet(type, type.code, fields.toList())

        fun of(type: PacketType, fields: List<Field>): Packet = Packet(type, type.code, fields)

        /** Used by the decoder so an unknown command keeps its code. */
        fun unknown(rawType: Int, fields: List<Field>): Packet = Packet(PacketType.UNKNOWN, rawType, fields)
    }
}

class ProtocolException(message: String) : IOException(message)

object PacketCodec {

    /** Upper bound on a packet payload. APNs messages are ~4 KiB; anything near this is a bug or an attack. */
    const val MAX_PACKET_PAYLOAD: Int = 1 shl 20

    const val MAX_FIELD_LENGTH: Int = 0xFFFF

    private const val HEADER_BYTES = 5
    private const val FIELD_HEADER_BYTES = 3

    fun encode(packet: Packet): ByteArray {
        val payload = encodeFields(packet.fields)
        if (payload.size > MAX_PACKET_PAYLOAD) {
            throw ProtocolException("payload too large: ${payload.size}")
        }
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = packet.rawType.toByte()
        payload.size.toByteArray(4).copyInto(out, 1)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    fun encodeFields(fields: List<Field>): ByteArray {
        var size = 0
        for (field in fields) size += FIELD_HEADER_BYTES + field.value.size
        if (size > MAX_PACKET_PAYLOAD) throw ProtocolException("fields too large: $size")
        val out = ByteArray(size)
        var offset = 0
        for (field in fields) {
            out[offset++] = field.id.toByte()
            field.value.size.toByteArray(2).copyInto(out, offset)
            offset += 2
            field.value.copyInto(out, offset)
            offset += field.value.size
        }
        return out
    }

    fun write(output: OutputStream, packet: Packet) {
        output.write(encode(packet))
        output.flush()
    }

    /** Reads exactly one packet, blocking until it is complete. */
    fun read(input: InputStream): Packet {
        val typeCode = input.read()
        if (typeCode < 0) throw EOFException("connection closed while reading packet type")
        val lengthBytes = input.readExactly(4)
        val length = lengthBytes.toIntBigEndian()
        if (length < 0 || length > MAX_PACKET_PAYLOAD) {
            throw ProtocolException("bad payload length: $length")
        }
        val payload = if (length == 0) ByteArray(0) else input.readExactly(length)
        return decodeBody(typeCode, payload)
    }

    /** Decodes a complete packet from [bytes] (header + payload); trailing bytes are an error. */
    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < HEADER_BYTES) throw ProtocolException("packet shorter than header: ${bytes.size}")
        val length = bytes.copyOfRange(1, HEADER_BYTES).toIntBigEndian()
        if (length < 0 || length > MAX_PACKET_PAYLOAD) throw ProtocolException("bad payload length: $length")
        if (bytes.size != HEADER_BYTES + length) {
            throw ProtocolException("declared $length payload bytes, got ${bytes.size - HEADER_BYTES}")
        }
        return decodeBody(bytes[0].toInt() and 0xFF, bytes.copyOfRange(HEADER_BYTES, bytes.size))
    }

    private fun decodeBody(typeCode: Int, payload: ByteArray): Packet {
        val fields = ArrayList<Field>()
        var offset = 0
        while (offset < payload.size) {
            if (payload.size - offset < FIELD_HEADER_BYTES) {
                throw ProtocolException("truncated field header at offset $offset")
            }
            val id = payload[offset].toInt() and 0xFF
            val length = ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                (payload[offset + 2].toInt() and 0xFF)
            offset += FIELD_HEADER_BYTES
            if (payload.size - offset < length) {
                throw ProtocolException("truncated field $id: want $length, have ${payload.size - offset}")
            }
            fields += Field(id, payload.copyOfRange(offset, offset + length))
            offset += length
        }
        return if (PacketType.fromCode(typeCode) == PacketType.UNKNOWN) {
            Packet.unknown(typeCode, fields)
        } else {
            Packet(PacketType.fromCode(typeCode), typeCode, fields)
        }
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) throw EOFException("connection closed after $read/$count bytes")
            read += n
        }
        return buffer
    }
}

internal fun ByteArray.toIntBigEndian(): Int {
    if (size != 4) throw ProtocolException("expected 4 bytes, got $size")
    return ((this[0].toInt() and 0xFF) shl 24) or
        ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8) or
        (this[3].toInt() and 0xFF)
}

internal fun ByteArray.toLongBigEndian(): Long {
    if (size != 8) throw ProtocolException("expected 8 bytes, got $size")
    var value = 0L
    for (byte in this) value = (value shl 8) or (byte.toLong() and 0xFF)
    return value
}

internal fun Int.toByteArray(size: Int): ByteArray {
    require(size in 1..4) { "unsupported int width $size" }
    val out = ByteArray(size)
    for (i in 0 until size) {
        out[size - 1 - i] = ((this ushr (8 * i)) and 0xFF).toByte()
    }
    return out
}

internal fun Long.toByteArray(size: Int): ByteArray {
    require(size in 1..8) { "unsupported long width $size" }
    val out = ByteArray(size)
    for (i in 0 until size) {
        out[size - 1 - i] = ((this ushr (8 * i)) and 0xFF).toByte()
    }
    return out
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
