package io.github.lichon.abark.bark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

class BarkPayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One push as it arrives from the courier: the raw JSON `bark-server` handed to APNs.
 *
 * `bark-server` builds `aps.alert.{title,subtitle,body}` plus `aps.sound`/`aps.thread-id`/`aps.category`/
 * `aps.mutable-content`, and copies every custom parameter to the **top level**, lowercased. Both places are read:
 * `aps.alert` is what iOS renders, the top level is where Bark's own options (`level`, `group`, `url`, …) live.
 */
class BarkPayload private constructor(
    private val root: JsonObject,
    val rawJson: String,
    /** `aps.alert` flattened. */
    val alertTitle: String?,
    val alertSubtitle: String?,
    val alertBody: String?,
    val sound: String?,
    val threadId: String?,
    val category: String?,
    val mutableContent: Boolean,
    val contentAvailable: Boolean,
    val apsBadge: Int?,
    /** Every top-level custom parameter, as text. */
    val custom: Map<String, String>,
) {

    /** `title` from the top level, falling back to `aps.alert.title`. */
    val title: String? get() = string("title") ?: alertTitle

    val subtitle: String? get() = string("subtitle") ?: alertSubtitle

    val body: String? get() = string("body") ?: alertBody

    /** Notification group; becomes the Android channel. */
    val group: String? get() = string("group")

    /** `critical` | `timeSensitive` | `active` | `passive`. See [Urgency]. */
    val level: String? get() = string("level")

    val url: String? get() = string("url")?.takeIf { it.isNotBlank() }

    /** Text the client should put on the clipboard when the notification arrives. */
    val copy: String? get() = string("copy")?.takeIf { it.isNotEmpty() }

    val iconUrl: String? get() = string("icon")?.takeIf { it.isNotBlank() }

    /** `1`/`true`: keep ringing like a call instead of a single alert. */
    val isCall: Boolean get() = flag("call")

    /** Copy without requiring the user to open the notification. */
    val autoCopy: Boolean get() = flag("autoCopy") || flag("autocopy")

    /** Render the body as markdown (Bark's `markdown` option). */
    val isMarkdown: Boolean get() = flag("markdown")

    /** Whether the client should keep this message in history. Absent means "keep". */
    val isArchive: Boolean
        get() {
            val value = root["isarchive"] as? JsonPrimitive ?: return true
            return value.booleanOrNull ?: when (value.content.lowercase()) {
                "0", "false", "no" -> false
                else -> true
            }
        }

    /** Seconds the message stays valid; advisory only. */
    val ttl: Int? get() = int("ttl")

    val volume: Double? get() = double("volume")

    val badge: Int? get() = int("badge") ?: apsBadge

    /** `action=none` suppresses the tap action. */
    val action: String? get() = string("action")

    /** Present when the payload is end-to-end encrypted; we cannot render it yet. */
    val hasCiphertext: Boolean get() = string("ciphertext").isNullOrEmpty().not()

    /**
     * `delete` pushes ask the client to remove an earlier notification. The value is either `"1"`/`true` (remove this
     * one) or the id to remove. **verify** on device — see docs/PROTOCOL.md.
     */
    val delete: String? get() = string("delete")?.takeIf { it.isNotEmpty() && it != "0" && it != "false" }

    private fun string(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun int(key: String): Int? = (root[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

    private fun double(key: String): Double? =
        (root[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

    private fun flag(key: String): Boolean {
        val value = root[key] as? JsonPrimitive ?: return false
        return value.booleanOrNull ?: when (value.content.lowercase()) {
            "1", "true", "yes" -> true
            else -> false
        }
    }

    override fun toString(): String = "BarkPayload(title=$title, body=$body, group=$group, level=$level)"

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * @throws BarkPayloadException when the body is not a JSON object at all. Any other surprise is tolerated: a
         *   push with an unreadable field must still be displayed, not dropped.
         */
        fun parse(rawJson: String): BarkPayload {
            val root = try {
                json.parseToJsonElement(rawJson) as? JsonObject
                    ?: throw BarkPayloadException("payload is not a JSON object")
            } catch (e: BarkPayloadException) {
                throw e
            } catch (e: Exception) {
                throw BarkPayloadException("payload is not valid JSON: ${e.message}", e)
            }

            val aps = root["aps"] as? JsonObject
            val alert = aps?.get("alert")
            val alertObject = alert as? JsonObject
            val alertText = (alert as? JsonPrimitive)?.takeIf { it.isString }?.content

            val custom = LinkedHashMap<String, String>()
            for ((key, value) in root) {
                if (key == "aps") continue
                (value as? JsonPrimitive)?.let { custom[key.lowercase()] = it.content }
            }

            return BarkPayload(
                root = root,
                rawJson = rawJson,
                alertTitle = (alertObject?.get("title") as? JsonPrimitive)?.content,
                alertSubtitle = (alertObject?.get("subtitle") as? JsonPrimitive)?.content,
                alertBody = (alertObject?.get("body") as? JsonPrimitive)?.content ?: alertText,
                sound = (aps?.get("sound") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                threadId = (aps?.get("thread-id") as? JsonPrimitive)?.content,
                category = (aps?.get("category") as? JsonPrimitive)?.content,
                mutableContent = (aps?.get("mutable-content") as? JsonPrimitive)?.content.let { it == "1" || it == "true" },
                contentAvailable = (aps?.get("content-available") as? JsonPrimitive)?.content.let {
                    it == "1" || it == "true"
                },
                apsBadge = (aps?.get("badge") as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() },
                custom = custom,
            )
        }

        /** For tests and for building a payload by hand. */
        fun parse(element: JsonElement): BarkPayload = parse(element.toString())
    }
}

/**
 * Bark's four documented levels, mapped to what the renderer does with them. Android-specific importance lives in
 * `:app`; this module stays free of platform constants.
 */
enum class Urgency {
    /** `level=critical` — breaks through Do Not Disturb, alarm-like. */
    CRITICAL,

    /** `level=timeSensitive` — heads-up. */
    TIME_SENSITIVE,

    /** Default. */
    ACTIVE,

    /** `level=passive` — silent, notification-shade only. */
    PASSIVE,
    ;

    companion object {
        fun fromLevel(level: String?): Urgency = when (level?.trim()?.lowercase()) {
            "critical" -> CRITICAL
            "timesensitive" -> TIME_SENSITIVE
            "passive" -> PASSIVE
            else -> ACTIVE
        }
    }
}
