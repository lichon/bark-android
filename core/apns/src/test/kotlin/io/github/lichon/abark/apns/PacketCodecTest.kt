package io.github.lichon.abark.apns

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {

    @Test
    fun `frames a packet exactly as captured on the wire`() {
        // type 7 (CONNECT), 3 payload bytes, one field: id 2, length 1, value 1
        val packet = Packet.of(PacketType.CONNECT, Field(2, byteArrayOf(1)))
        assertEquals("070000000402000101", PacketCodec.encode(packet).toHex())
    }

    @Test
    fun `round-trips repeated fields and long values`() {
        val long = ByteArray(300) { (it % 251).toByte() }
        val original = Packet.of(
            PacketType.FILTER_TOPICS,
            Field(1, ByteArray(32) { 0x11 }),
            Field(2, ByteArray(20) { 0x22 }),
            Field(2, ByteArray(20) { 0x33 }),
            Field(2, long),
        )
        val decoded = PacketCodec.decode(PacketCodec.encode(original))
        assertEquals(PacketType.FILTER_TOPICS, decoded.type)
        assertEquals(original.fields, decoded.fields)
        assertEquals(3, decoded.fieldsOf(2).size)
        assertEquals(long.size, decoded.fieldsOf(2)[2].size)
    }

    @Test
    fun `reads consecutive packets from a stream`() {
        val stream = ByteArrayOutputStream().apply {
            write(PacketCodec.encode(KeepAliveCommand.build()))
            write(PacketCodec.encode(ConnectCommand.build(ByteArray(32), ByteArray(4), rsaKeyPair().private, ByteArray(17))))
        }.toByteArray()

        val input = ByteArrayInputStream(stream)
        val first = PacketCodec.read(input)
        val second = PacketCodec.read(input)
        assertEquals(PacketType.KEEP_ALIVE, first.type)
        assertEquals(PacketType.CONNECT, second.type)
        assertEquals(6, second.fields.size)
    }

    @Test
    fun `zero-length payload is a valid packet`() {
        val decoded = PacketCodec.decode(byteArrayOf(12, 0, 0, 0, 0))
        assertEquals(PacketType.KEEP_ALIVE, decoded.type)
        assertTrue(decoded.fields.isEmpty())
    }

    @Test
    fun `rejects a truncated stream`() {
        val encoded = PacketCodec.encode(Packet.of(PacketType.SEND_MESSAGE, Field(3, ByteArray(100))))
        assertThrows(EOFException::class.java) {
            PacketCodec.read(ByteArrayInputStream(encoded.copyOf(encoded.size - 10)))
        }
        assertThrows(EOFException::class.java) {
            PacketCodec.read(ByteArrayInputStream(encoded.copyOf(3)))
        }
    }

    @Test
    fun `rejects length mismatches and absurd lengths`() {
        // Declared 5 payload bytes, only 1 present.
        assertThrows(ProtocolException::class.java) {
            PacketCodec.decode(byteArrayOf(7, 0, 0, 0, 5, 0x02, 0x00, 0x01, 0x01))
        }
        // 0x7fffffff payload bytes.
        assertThrows(ProtocolException::class.java) {
            PacketCodec.decode(byteArrayOf(7, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        }
        // Header shorter than 5 bytes.
        assertThrows(ProtocolException::class.java) {
            PacketCodec.decode(byteArrayOf(7, 0, 0))
        }
    }

    @Test
    fun `rejects a truncated field header`() {
        assertThrows(ProtocolException::class.java) {
            PacketCodec.decode(byteArrayOf(7, 0, 0, 0, 2, 0x02, 0x00))
        }
        // Field claims 8 bytes, only 2 present.
        assertThrows(ProtocolException::class.java) {
            PacketCodec.decode(byteArrayOf(7, 0, 0, 0, 5, 0x02, 0x00, 0x08, 0x01, 0x02))
        }
    }

    @Test
    fun `keeps the raw code of commands it does not model`() {
        // type 99, one field id 1 = "hi"
        val decoded = PacketCodec.decode(byteArrayOf(99, 0, 0, 0, 5, 0x01, 0x00, 0x02, 'h'.code.toByte(), 'i'.code.toByte()))
        assertEquals(PacketType.UNKNOWN, decoded.type)
        assertEquals(99, decoded.rawType)
        assertEquals("hi", String(decoded.field(1)!!))
        // and it must survive a re-encode unchanged
        assertEquals(99, PacketCodec.encode(decoded)[0].toInt())
    }

    @Test
    fun `field lengths are bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            Field(1, ByteArray(PacketCodec.MAX_FIELD_LENGTH + 1))
        }
    }

    @Test
    fun `write flushes a complete packet`() {
        val out = ByteArrayOutputStream()
        PacketCodec.write(out, Packet.of(PacketType.NO_STORAGE, Field(1, ByteArray(4) { 7 })))
        val bytes = out.toByteArray()
        assertEquals(5 + 7, bytes.size)
        assertEquals(PacketType.NO_STORAGE, PacketCodec.read(ByteArrayInputStream(bytes)).type)
    }

    @Test
    fun `rejects an encode that would exceed the payload cap`() {
        val fields = List(20) { Field(2, ByteArray(PacketCodec.MAX_FIELD_LENGTH) { 1 }) }
        assertThrows(ProtocolException::class.java) {
            PacketCodec.encode(Packet.of(PacketType.SEND_MESSAGE, fields))
        }
    }

    @Test
    fun `IOException is the common failure type so callers can reconnect on any of it`() {
        assertTrue(IOException::class.java.isAssignableFrom(ProtocolException::class.java))
    }
}
