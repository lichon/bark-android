package io.github.lichon.abark.apns.activation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlistTest {

    @Test
    fun `encodes a complete document with sorted keys`() {
        val value = PlistValue.Dict(
            linkedMapOf(
                "b" to PlistValue.Bool(true),
                "a" to PlistValue.String("x<y"),
            ),
        )
        val expected = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            ${'\t'}<key>a</key>
            ${'\t'}<string>x&lt;y</string>
            ${'\t'}<key>b</key>
            ${'\t'}<true/>
            </dict>
            </plist>

        """.trimIndent()
        assertEquals(expected, PlistCodec.encode(value))
    }

    @Test
    fun `insertion order survives when sorting is disabled`() {
        val value = PlistValue.Dict(
            linkedMapOf(
                "b" to PlistValue.Integer(1),
                "a" to PlistValue.Integer(2),
            ),
        )
        val xml = PlistCodec.encode(value, sortKeys = false)
        assertTrue(xml.indexOf("<key>b</key>") < xml.indexOf("<key>a</key>"))
    }

    @Test
    fun `round-trips every scalar type`() {
        val value = PlistValue.Dict(
            linkedMapOf(
                "string" to PlistValue.String("a & b > c \u00e9"),
                "bool" to PlistValue.Bool(false),
                "int" to PlistValue.Integer(-42),
                "real" to PlistValue.Real(1.5),
                "data" to PlistValue.Bytes(ByteArray(200) { it.toByte() }),
                "date" to PlistValue.Date("2024-01-02T03:04:05Z"),
                "array" to PlistValue.Array(listOf(PlistValue.String("x"), PlistValue.Bool(true))),
                "emptyArray" to PlistValue.Array(emptyList()),
                "emptyDict" to PlistValue.Dict(emptyMap()),
            ),
        )
        val parsed = PlistCodec.parse(PlistCodec.encode(value)) as PlistValue.Dict

        assertEquals("a & b > c \u00e9", parsed.at("string").asString())
        assertEquals(PlistValue.Bool(false), parsed.at("bool"))
        assertEquals(PlistValue.Integer(-42), parsed.at("int"))
        assertEquals(PlistValue.Real(1.5), parsed.at("real"))
        assertArrayEquals(ByteArray(200) { it.toByte() }, parsed.at("data").asBytes())
        assertEquals(PlistValue.Date("2024-01-02T03:04:05Z"), parsed.at("date"))
        assertEquals(
            PlistValue.Array(listOf(PlistValue.String("x"), PlistValue.Bool(true))),
            parsed.at("array"),
        )
        assertEquals(PlistValue.Array(emptyList()), parsed.at("emptyArray"))
        assertEquals(PlistValue.Dict(emptyMap()), parsed.at("emptyDict"))
    }

    @Test
    fun `navigates the nested activation record`() {
        val plist = PlistCodec.parse(
            """
            <plist version="1.0"><dict>
              <key>device-activation</key>
              <dict>
                <key>activation-record</key>
                <dict>
                  <key>DeviceCertificate</key>
                  <string>-----BEGIN CERTIFICATE-----</string>
                </dict>
                <key>status</key>
                <integer>0</integer>
              </dict>
            </dict></plist>
            """.trimIndent(),
        )
        assertEquals(
            "-----BEGIN CERTIFICATE-----",
            plist.at("device-activation", "activation-record", "DeviceCertificate").asString(),
        )
        assertEquals(PlistValue.Integer(0), plist.at("device-activation", "status"))
        assertNull(plist.at("device-activation", "nope"))
        assertNull(plist.at("nope", "deeper"))
    }

    @Test
    fun `rejects control characters that XML cannot carry`() {
        assertThrows(PlistException::class.java) {
            PlistCodec.encode(PlistValue.String("bell\u0007"))
        }
        // Tab and newline are legal and must survive unescaped.
        assertEquals("a\tb\nc", PlistCodec.parse(PlistCodec.encode(PlistValue.String("a\tb\nc"))).asString())
    }

    @Test
    fun `refuses entity declarations instead of resolving them`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE plist [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <plist version="1.0"><dict><key>k</key><string>&xxe;</string></dict></plist>
        """.trimIndent()
        assertThrows(PlistException::class.java) { PlistCodec.parse(xxe) }
    }

    @Test
    fun `malformed input fails without leaking parser internals`() {
        assertThrows(PlistException::class.java) { PlistCodec.parse("<plist><dict><key>a</key>") }
        assertThrows(PlistException::class.java) { PlistCodec.parse("") }
        assertThrows(PlistException::class.java) {
            PlistCodec.parse("<plist version=\"1.0\"><dict><key>a</key><widget/></dict></plist>")
        }
    }

    @Test
    fun `extracts the protocol block out of an HTML response`() {
        val html = """
            <html><body><h1>Device Activation</h1>
            <Protocol>
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict><key>device-activation</key><dict>
            <key>activation-record</key><dict><key>DeviceCertificate</key><string>PEM</string></dict>
            </dict></dict></plist>
            </Protocol>
            </body></html>
        """.trimIndent()

        val protocol = PlistCodec.extractProtocol(html)!!
        assertTrue(protocol.contains("<Protocol>").not())
        assertEquals(
            "PEM",
            PlistCodec.parse(protocol).at("device-activation", "activation-record", "DeviceCertificate").asString(),
        )
        assertNull(PlistCodec.extractError(html))
    }

    @Test
    fun `finds the error text when there is no protocol block`() {
        val html = "<html><body><b>Invalid activation info</b><br/>failed</body></html>"
        assertNull(PlistCodec.extractProtocol(html))
        assertEquals("Invalid activation info", PlistCodec.extractError(html))
        assertNull(PlistCodec.extractError("<html>nothing</html>"))
    }
}
