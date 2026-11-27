package io.github.lichon.abark.bark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMessageTest {

    private fun convert(raw: String, room: String? = null): JsonObject? =
        RoomMessage.toBarkPayload(Json.parseToJsonElement(raw).jsonObject, room)?.let {
            Json.parseToJsonElement(it).jsonObject
        }

    @Test
    fun `string content becomes the alert body and keeps the message id`() {
        val bark = convert("""{"id":"x","content":"hello"}""")!!
        assertEquals("hello", bark["aps"]!!.jsonObject["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content)
        assertEquals("x", bark["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a string that looks like JSON is still plain text`() {
        val content = """{"aps":{"alert":{"title":"T","body":"B"}}}"""
        val message = buildJsonObject {
            put("id", "j")
            put("sender", "Ana")
            put("content", content)
        }
        val bark = RoomMessage.toBarkPayload(message, null)?.let { Json.parseToJsonElement(it).jsonObject }!!
        val alert = bark["aps"]!!.jsonObject["alert"]!!.jsonObject
        assertEquals(content, alert["body"]!!.jsonPrimitive.content)
        assertEquals("Ana", alert["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain title and body objects are nested under aps alert`() {
        val bark = convert("""{"id":"y","content":{"title":"T","body":"B"}}""")!!
        val alert = bark["aps"]!!.jsonObject["alert"]!!.jsonObject
        assertEquals("T", alert["title"]!!.jsonPrimitive.content)
        assertEquals("B", alert["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `full bark payload content is used verbatim`() {
        val bark = convert(
            """{"id":"sender-id","content":{"aps":{"alert":{"title":"Deploy","body":"build 412"}},"group":"ops"}}""",
        )!!
        val alert = bark["aps"]!!.jsonObject["alert"]!!.jsonObject
        assertEquals("Deploy", alert["title"]!!.jsonPrimitive.content)
        assertEquals("ops", bark["group"]!!.jsonPrimitive.content)
        assertEquals("sender-id", bark["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct bark payloads without content are supported`() {
        val bark = convert("""{"aps":{"alert":{"title":"T","body":"B"}},"id":"d"}""")!!
        assertEquals("d", bark["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sender becomes the title only when the content has none`() {
        val without = convert("""{"id":"s","sender":"iPhone · Ana","content":{"body":"B"}}""")!!
        val alert = without["aps"]!!.jsonObject["alert"]!!.jsonObject
        assertEquals("iPhone · Ana", alert["title"]!!.jsonPrimitive.content)
        assertEquals("iPhone · Ana", without["sender"]!!.jsonPrimitive.content)

        val with = convert(
            """{"id":"s","sender":"Ana","content":{"type":"note","title":"Deploy","body":"B"}}""",
        )!!
        assertEquals("Deploy", with["aps"]!!.jsonObject["alert"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `room becomes the group unless one is provided or this is a recall`() {
        assertEquals("alerts", convert("""{"content":{"body":"B"}}""", "alerts")!!["group"]!!.jsonPrimitive.content)
        assertEquals(
            "ops",
            convert("""{"content":{"body":"B","group":"ops"}}""", "alerts")!!["group"]!!.jsonPrimitive.content,
        )
        assertFalse(convert("""{"content":{"body":"B"}}""")!!.containsKey("group"))
        assertFalse(convert("""{"content":{"delete":"1","id":"m"}}""", "alerts")!!.containsKey("group"))
    }

    @Test
    fun `command messages are not notifications`() {
        assertNull(convert("""{"type":"command","content":"x"}"""))
    }

    @Test
    fun `unknown objects stay readable`() {
        val bark = convert("""{"content":{"method":"ping"}}""")!!
        assertTrue(bark["aps"]!!.jsonObject["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content.contains("ping"))
    }

    @Test
    fun `message id falls back to the whole object`() {
        val message = Json.parseToJsonElement("""{"content":"hi"}""").jsonObject
        assertTrue(RoomMessage.messageId(message).contains("hi"))
        val identified = Json.parseToJsonElement("""{"id":"abc","content":"hi"}""").jsonObject
        assertEquals("abc", RoomMessage.messageId(identified))
    }
}
