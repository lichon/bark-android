package io.github.lichon.abark.bark

import io.github.lichon.abark.net.HttpPost
import io.github.lichon.abark.net.HttpResult
import io.github.lichon.abark.net.HttpTransport
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarkPayloadTest {

    private val full = """
        {"aps":{"alert":{"title":"t","subtitle":"s","body":"b"},"sound":"minuet","thread-id":"grp",
          "category":"myNotificationCategory","mutable-content":1,"badge":3},
         "title":"t","body":"b","group":"grp","url":"https://example.com/x","level":"timeSensitive",
         "copy":"copied","icon":"https://example.com/i.png","call":"1","autocopy":"1","volume":0.5,
         "ttl":60,"badge":4,"markdown":"1","isarchive":"0","action":"none"}
    """.trimIndent()

    @Test
    fun `reads alert, aps options and top-level options`() {
        val payload = BarkPayload.parse(full)

        assertEquals("t", payload.title)
        assertEquals("s", payload.subtitle)
        assertEquals("b", payload.body)
        assertEquals("minuet", payload.sound)
        assertEquals("grp", payload.threadId)
        assertEquals("myNotificationCategory", payload.category)
        assertTrue(payload.mutableContent)
        assertEquals("grp", payload.group)
        assertEquals("https://example.com/x", payload.url)
        assertEquals("timeSensitive", payload.level)
        assertEquals("copied", payload.copy)
        assertEquals("https://example.com/i.png", payload.iconUrl)
        assertTrue(payload.isCall)
        assertTrue(payload.autoCopy)
        assertEquals(0.5, payload.volume!!, 0.0)
        assertEquals(60, payload.ttl)
        assertEquals(4, payload.badge) // top level wins over aps.badge
        assertTrue(payload.isMarkdown)
        assertTrue(!payload.isArchive)
        assertEquals("none", payload.action)
    }

    @Test
    fun `alert as a plain string still yields a body`() {
        val payload = BarkPayload.parse("""{"aps":{"alert":"just text"}}""")
        assertEquals("just text", payload.body)
        assertNull(payload.title)
        assertEquals(Urgency.ACTIVE, Urgency.fromLevel(payload.level))
    }

    @Test
    fun `minimal payload does not throw`() {
        val payload = BarkPayload.parse("{}")
        assertNull(payload.title)
        assertNull(payload.body)
        assertTrue(payload.isArchive)
        assertNull(payload.delete)
        assertTrue(!payload.hasCiphertext)
    }

    @Test
    fun `custom parameters are exposed lowercased`() {
        val payload = BarkPayload.parse("""{"aps":{},"Group":"g","LEVEL":"passive"}""")
        assertEquals("g", payload.custom["group"])
        assertEquals("passive", payload.custom["level"])
    }

    @Test
    fun `delete push is recognised`() {
        assertEquals("1", BarkPayload.parse("""{"aps":{"content-available":1},"delete":"1"}""").delete)
        assertEquals("abc", BarkPayload.parse("""{"aps":{},"delete":"abc"}""").delete)
        assertNull(BarkPayload.parse("""{"aps":{},"delete":"0"}""").delete)
    }

    @Test
    fun `ciphertext marks the payload as unreadable`() {
        assertTrue(BarkPayload.parse("""{"aps":{},"ciphertext":"BASE64"}""").hasCiphertext)
    }

    @Test
    fun `invalid JSON is an explicit error`() {
        assertThrows(BarkPayloadException::class.java) { BarkPayload.parse("not json") }
        assertThrows(BarkPayloadException::class.java) { BarkPayload.parse("[1,2,3]") }
    }

    @Test
    fun `urgency maps all four documented levels`() {
        assertEquals(Urgency.CRITICAL, Urgency.fromLevel("critical"))
        assertEquals(Urgency.TIME_SENSITIVE, Urgency.fromLevel("timeSensitive"))
        assertEquals(Urgency.TIME_SENSITIVE, Urgency.fromLevel("timesensitive"))
        assertEquals(Urgency.ACTIVE, Urgency.fromLevel("active"))
        assertEquals(Urgency.ACTIVE, Urgency.fromLevel(null))
        assertEquals(Urgency.PASSIVE, Urgency.fromLevel("passive"))
        assertEquals(Urgency.ACTIVE, Urgency.fromLevel("something-new"))
    }
}

class NotificationSpecTest {

    @Test
    fun `spec carries everything the renderer needs`() {
        val payload = BarkPayload.parse(
            """{"aps":{"alert":{"title":"t","body":"b"},"sound":"minuet","thread-id":"th"},
                "group":"chat","level":"critical","url":"https://x","copy":"c","icon":"https://i","call":"1"}""",
        )
        val spec = NotificationSpec.from(payload, messageId = "a1b2c3d4")

        assertEquals("t", spec.title)
        assertEquals("b", spec.body)
        assertEquals("chat", spec.group)
        assertEquals("abark-group-chat", spec.channelId)
        assertEquals(Urgency.CRITICAL, spec.urgency)
        assertEquals("minuet", spec.sound)
        assertEquals("https://x", spec.url)
        assertEquals("c", spec.copy)
        assertEquals("https://i", spec.iconUrl)
        assertEquals("th", spec.threadId)
        assertTrue(spec.call)
        assertTrue(!spec.isSilent)
        assertTrue(!spec.isDelete)
        assertEquals(NotificationSpec.stableId("a1b2c3d4", payload.rawJson), spec.id)
    }

    @Test
    fun `ungrouped pushes share the default channel`() {
        val spec = NotificationSpec.from(BarkPayload.parse("""{"aps":{"alert":{"body":"b"}}}"""))
        assertEquals(NotificationSpec.DEFAULT_CHANNEL, spec.channelId)
        assertNull(spec.group)
    }

    @Test
    fun `group names that are not channel-safe are slugged`() {
        assertEquals("abark-group-a-b_c", NotificationSpec.channelIdFor("A B_c/😀"))
        assertEquals("abark-group-x", NotificationSpec.channelIdFor("  x  "))
        assertEquals(NotificationSpec.DEFAULT_CHANNEL, NotificationSpec.channelIdFor(""))
    }

    @Test
    fun `same message id yields the same notification id`() {
        val payload = BarkPayload.parse("""{"aps":{"alert":{"body":"b"}}}""")
        assertEquals(NotificationSpec.from(payload, "abcd").id, NotificationSpec.from(payload, "abcd").id)
        assertTrue(NotificationSpec.from(payload, "abcd").id != NotificationSpec.from(payload, "abce").id)
        assertEquals(16, NotificationSpec.from(payload, "abcd").id.length)
    }

    @Test
    fun `identical payload without an id is still stable`() {
        val raw = """{"aps":{"alert":{"body":"same"}}}"""
        assertEquals(NotificationSpec.stableId(null, raw), NotificationSpec.stableId(null, raw))
        assertTrue(NotificationSpec.stableId(null, raw) != NotificationSpec.stableId(null, raw + " "))
    }

    @Test
    fun `delete push targets the referenced notification and is silent`() {
        val byFlag = NotificationSpec.from(
            BarkPayload.parse("""{"aps":{"content-available":1},"delete":"1"}"""),
            messageId = "deadbeef",
        )
        assertTrue(byFlag.isDelete)
        assertEquals(NotificationSpec.stableId("deadbeef", byFlag.rawJson), byFlag.deleteTarget)
        assertTrue(byFlag.isSilent)

        val byId = NotificationSpec.from(BarkPayload.parse("""{"aps":{},"delete":"feedface"}"""))
        assertEquals("feedface", byId.deleteTarget)
    }

    @Test
    fun `encrypted payload is kept but flagged unreadable`() {
        val spec = NotificationSpec.from(BarkPayload.parse("""{"aps":{},"ciphertext":"AAAA"}"""))
        assertTrue(spec.encrypted)
        assertTrue("encrypted pushes must still surface, even without readable text", !spec.isSilent)
    }

    @Test
    fun `payload with no content and no side effect is silent`() {
        val spec = NotificationSpec.from(BarkPayload.parse("""{"aps":{},"badge":"7"}"""))
        assertTrue(spec.isSilent)
        assertEquals(7, spec.badge)
    }

    @Test
    fun `spoken text joins title, subtitle and body and drops repeats`() {
        val spec = NotificationSpec.from(
            BarkPayload.parse("""{"aps":{"alert":{"title":"Build","subtitle":"CI","body":"Build"}}}"""),
        )
        assertEquals("Build. CI", spec.spokenText)
    }

    @Test
    fun `pushes without readable content are not spoken`() {
        val bodyOnly = NotificationSpec.from(BarkPayload.parse("""{"aps":{"alert":"hi"}}"""))
        assertEquals("hi", bodyOnly.spokenText)

        assertNull(NotificationSpec.from(BarkPayload.parse("""{"aps":{},"badge":"1"}""")).spokenText)
        assertNull(NotificationSpec.from(BarkPayload.parse("""{"aps":{},"delete":"feedface"}""")).spokenText)
        assertNull(NotificationSpec.from(BarkPayload.parse("""{"aps":{},"ciphertext":"AAAA"}""")).spokenText)
        assertNull(NotificationSpec.from(BarkPayload.parse("""{"aps":{"alert":{"title":"  "}}}""")).spokenText)
    }
}

private class FakeTransport(private val respond: (HttpPost) -> HttpResult) : HttpTransport {
    var lastRequest: HttpPost? = null
    override fun post(request: HttpPost): HttpResult {
        lastRequest = request
        return respond(request)
    }
}

class BarkApiTest {

    private fun api(status: Int = 200, body: String = """{"code":200,"message":"success"}"""): Pair<BarkApi, FakeTransport> {
        val transport = FakeTransport { HttpResult(status, body.toByteArray()) }
        return BarkApi(transport) to transport
    }

    private fun JsonObject.field(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun `register posts device key, token and type as JSON`() {
        val (api, transport) = api()
        val result = api.register(BarkServer("https://api.day.app", "DEVKEY"), deviceToken = "abc123")

        assertTrue(result is BarkResult.Ok)
        val request = transport.lastRequest!!
        assertEquals("https://api.day.app/register", request.url)
        assertTrue(request.contentType.startsWith("application/json"))

        val body = Json.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("DEVKEY", body.field("device_key"))
        assertEquals("abc123", body.field("device_token"))
        assertEquals("ios", body.field("device_type"))
    }

    @Test
    fun `server url is normalised and may carry a port`() {
        val (api, transport) = api()
        api.register(BarkServer("bark.example.com:8080/", "K"), "T", "watchos")

        assertEquals("https://bark.example.com:8080/register", transport.lastRequest!!.url)
        val body = Json.parseToJsonElement(transport.lastRequest!!.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("watchos", body.field("device_type"))
    }

    @Test
    fun `register without a device key lets the server assign one`() {
        val (api, transport) = api(
            body = """{"code":200,"message":"success","data":{"key":"NEWKEY","device_key":"NEWKEY","device_token":"abc123","device_type":"ios"}}""",
        )
        val result = api.register("https://api.day.app", deviceToken = "abc123") as BarkResult.Ok

        assertEquals("https://api.day.app/register", transport.lastRequest!!.url)
        val body = Json.parseToJsonElement(transport.lastRequest!!.body.toString(Charsets.UTF_8)).jsonObject
        assertNull(body.field("device_key"))
        assertEquals("abc123", body.field("device_token"))
        assertEquals("ios", body.field("device_type"))
        assertEquals("NEWKEY", result.deviceKey)
    }

    @Test
    fun `register accepts a bare host and reports no key when the server sends none`() {
        val (api, _) = api()
        val result = api.register("bark.example.com:8080/", "T") as BarkResult.Ok

        assertNull(result.deviceKey)
    }

    @Test
    fun `old servers answering data key still supply the device key`() {
        val (api, _) = api(body = """{"code":200,"data":{"key":"OLDKEY"}}""")
        val result = api.register("https://api.day.app", "T") as BarkResult.Ok

        assertEquals("OLDKEY", result.deviceKey)
    }

    @Test
    fun `push carries options for the in-app test button`() {
        val (api, transport) = api()
        api.push(BarkServer("https://api.day.app", "K"), "title", "body", mapOf("group" to "test", "level" to "active"))

        val request = transport.lastRequest!!
        assertEquals("https://api.day.app/push", request.url)
        val body = Json.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("K", body.field("device_key"))
        assertEquals("title", body.field("title"))
        assertEquals("test", body.field("group"))
    }

    @Test
    fun `non-200 code is reported as refused with the server message`() {
        val (api, _) = api(body = """{"code":400,"message":"failed to get device token"}""")
        val result = api.register(BarkServer("https://api.day.app", "K"), "T") as BarkResult.Refused

        assertEquals(400, result.code)
        assertEquals("failed to get device token", result.message)
    }

    @Test
    fun `http error without a JSON body is still a refusal`() {
        val (api, _) = api(status = 502, body = "bad gateway")
        val result = api.register(BarkServer("https://api.day.app", "K"), "T") as BarkResult.Refused
        assertEquals(502, result.code)
        assertTrue(result.message!!.contains("bad gateway"))
    }

    @Test
    fun `transport failure is unreachable, not a crash`() {
        val transport = FakeTransport { throw IOException("no route to host") }
        val result = BarkApi(transport).register(BarkServer("https://api.day.app", "K"), "T") as BarkResult.Unreachable
        assertEquals("no route to host", result.detail)
    }

    @Test
    fun `2xx with a non-JSON body is accepted`() {
        val (api, _) = api(body = "OK")
        assertTrue(api.register(BarkServer("https://api.day.app", "K"), "T") is BarkResult.Ok)
    }

    @Test
    fun `blank server or key is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { BarkServer("", "K") }
        assertThrows(IllegalArgumentException::class.java) { BarkServer("https://x", "") }
    }

    @Test
    fun `share link round-trips through the parser`() {
        val server = BarkServer("https://api.day.app", "DEVKEY")
        val parsed = BarkLink.parse(server.shareLink())
        assertEquals(BarkLink.ParseResult.Success(server), parsed)
    }
}

class BarkLinkTest {

    private fun server(link: String): BarkServer =
        (BarkLink.parse(link) as BarkLink.ParseResult.Success).server

    private fun reason(link: String): String =
        (BarkLink.parse(link) as BarkLink.ParseResult.Failure).reason

    @Test
    fun `accepts the documented link shapes`() {
        assertEquals(BarkServer("https://api.day.app", "KEY"), server("https://api.day.app/KEY"))
        assertEquals(BarkServer("https://api.day.app", "KEY"), server("  https://api.day.app/KEY/  "))
        assertEquals(BarkServer("https://api.day.app", "KEY"), server("bark://api.day.app/KEY"))
        assertEquals(BarkServer("http://192.168.1.10:8080", "KEY"), server("http://192.168.1.10:8080/KEY"))
        assertEquals(BarkServer("https://bark.example.com", "KEY"), server("bark.example.com/KEY"))
        assertEquals(BarkServer("https://api.day.app", "KEY"), server("https://api.day.app/KEY?x=1"))
    }

    @Test
    fun `rejects links that cannot be pushed to`() {
        assertTrue(reason("").contains("empty"))
        assertTrue(reason("just-a-key").contains("device key"))
        assertTrue(reason("https://api.day.app").contains("device key"))
        assertTrue(reason("https://api.day.app/").contains("device key"))
        assertTrue(reason("ftp://api.day.app/KEY").contains("unsupported scheme"))
        assertTrue(reason("https://api.day.app/a/b").contains("slash"))
    }

    @Test
    fun `share link is stable for the same server and key`() {
        val a = server("https://api.day.app/KEY")
        val b = server("bark://api.day.app/KEY")
        assertEquals(a.shareLink(), b.shareLink())
        assertNotNull(a.normalizedBaseUrl)
    }
}
