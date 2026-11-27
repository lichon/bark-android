package io.github.lichon.abark.bark

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A Supabase Realtime room message, as the companion `use-supabase.ts` hook produces it.
 *
 * The room carries `{id, type, content, timestamp, sender}`; `content` is either the message text or a full Bark payload
 * object. This mirrors the desktop client's `sb://` mapping so both apps show the same fields for the same broadcast.
 */
object RoomMessage {

    /** The text of a `ChannelMessage`, or a full Bark payload when one is embedded. */
    fun toBarkPayload(message: JsonObject, room: String?): String? {
        if (message.text("type").equals("command", ignoreCase = true)) return null

        val content = message["content"]
        val payload = when (content) {
            is JsonObject -> barkObject(content)
            is JsonPrimitive -> {
                val text = content.content.trim()
                if (!content.isString || text.isEmpty()) return null
                buildJsonObject {
                    putJsonObject("aps") {
                        putJsonObject("alert") { put("body", text) }
                    }
                }
            }
            null -> barkObject(message)
            else -> return null
        }

        val result = payload.toMutableMap()
        val envelopeId = message.text("id")
        if (result.text("id").isBlank() && envelopeId.isNotBlank()) {
            result["id"] = JsonPrimitive(envelopeId)
        }

        val sender = message.text("sender")
        if (sender.isNotBlank()) {
            if (result.text("sender").isBlank()) result["sender"] = JsonPrimitive(sender)
            applySenderTitle(result, sender)
        }

        val roomName = room.orEmpty().trim()
        if (roomName.isNotEmpty() && "delete" !in result && result.text("group").isBlank()) {
            result["group"] = JsonPrimitive(roomName)
        }

        return JsonObject(result).toString()
    }

    /** Stable dedupe seed: the sender's message id, or the whole object when it has none. */
    fun messageId(message: JsonObject): String = message.text("id").ifBlank { message.toString() }

    /**
     * Bark nests `title`/`body` under `aps.alert`; a room sender may put them at the top level. A payload that already
     * has `aps` (or is a recall) is used verbatim.
     */
    private fun barkObject(content: JsonObject): JsonObject {
        if ("aps" in content || "delete" in content) return content

        val copy = content.toMutableMap()
        val alert = buildJsonObject {
            for (key in listOf("title", "subtitle", "body")) {
                val value = copy.remove(key) ?: continue
                put(key, value.plainText())
            }
        }
        when {
            alert.isNotEmpty() -> copy["aps"] = buildJsonObject { put("alert", alert) }
            copy.containsKey("message") -> {
                val text = copy.remove("message")?.plainText().orEmpty()
                copy["aps"] = buildJsonObject { putJsonObject("alert") { put("body", text) } }
            }
            "ciphertext" !in copy -> return buildJsonObject {
                putJsonObject("aps") {
                    putJsonObject("alert") { put("body", content.toString()) }
                }
            }
        }
        return JsonObject(copy)
    }

    private fun applySenderTitle(payload: MutableMap<String, JsonElement>, sender: String) {
        val aps = payload["aps"] as? JsonObject ?: return
        val alert = aps["alert"] as? JsonObject ?: return
        if (alert.text("title").isNotBlank()) return
        payload["aps"] = JsonObject(
            aps.toMutableMap().apply {
                put("alert", JsonObject(alert.toMutableMap().apply { put("title", JsonPrimitive(sender)) }))
            },
        )
    }

    private fun Map<String, JsonElement>.text(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun JsonElement.plainText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }
}
