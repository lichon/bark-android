package io.github.lichon.abark.bark

import io.github.lichon.abark.net.HttpException
import io.github.lichon.abark.net.HttpPost
import io.github.lichon.abark.net.HttpTransport
import io.github.lichon.abark.net.UrlConnectionTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A `bark-server` (or `api.day.app`) plus the device key that authorises pushes to this install.
 *
 * The device key is not a secret we invent: it is minted by the server and is what the user shares as a
 * `bark://…` link, so it doubles as the "address" of this device.
 */
data class BarkServer(
    val baseUrl: String,
    val deviceKey: String,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(deviceKey.isNotBlank()) { "deviceKey must not be blank" }
    }

    /** `https://host[:port]` with no trailing slash, scheme defaulted to https. */
    val normalizedBaseUrl: String
        get() = normalize(baseUrl)

    fun endpoint(path: String): String = normalizedBaseUrl + "/" + path.trimStart('/')

    /** The share link a user pastes into another Bark client. */
    fun shareLink(): String = "$normalizedBaseUrl/$deviceKey"

    companion object {
        const val DEFAULT_SERVER: String = "https://api.day.app"

        /** `https://host[:port]`, no trailing slash — usable before a device key exists (registration mints it). */
        fun normalize(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return (if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed")
                .trimEnd('/')
        }
    }
}

sealed interface BarkResult {
    /**
     * The server answered `code == 200`.
     *
     * @property deviceKey the key the server assigned during registration, when the call was a `/register` and the
     *   server answered with one (it mints a key for a request that carries none). `null` for pushes.
     */
    data class Ok(val message: String?, val raw: String, val deviceKey: String? = null) : BarkResult

    /** The server answered, but refused the request (`code != 200` or a non-2xx status). */
    data class Refused(val code: Int?, val message: String?, val raw: String) : BarkResult

    /** Nothing usable came back: DNS, TLS, timeout, garbage body. */
    data class Unreachable(val detail: String?, val cause: Throwable? = null) : BarkResult
}

/**
 * The bark-server HTTP API (`/register`, `/push`).
 *
 * `bark-server` accepts a device token for `device_type=ios`; that is exactly what we register our **scoped APNs
 * token** as, so a stock server pushes to our emulated device with no server changes (PLAN.md §1).
 */
class BarkApi(
    private val transport: HttpTransport = UrlConnectionTransport(userAgent = "abark"),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Tell the server which APNs token to push to. Re-run whenever the courier mints a new scoped token.
     *
     * The device key is *not* ours to invent: send a blank key and `bark-server` mints one, returning it as
     * `data.device_key` (also mirrored as `data.key` for older servers) — see [BarkResult.Ok.deviceKey]. Passing an
     * existing key re-registers that device instead.
     *
     * @param deviceKey the known key, or blank to have the server assign one.
     * @param deviceType `ios` for a full device token (what we have), `watchos` for the watch topic.
     */
    fun register(
        serverUrl: String,
        deviceToken: String,
        deviceKey: String = "",
        deviceType: String = "ios",
    ): BarkResult {
        val body = buildJsonObject {
            if (deviceKey.isNotBlank()) put("device_key", deviceKey)
            put("device_token", deviceToken)
            put("device_type", deviceType)
        }
        return post(endpointOf(serverUrl, "register"), body)
    }

    /** Convenience for a server whose device key is already known. */
    fun register(
        server: BarkServer,
        deviceToken: String,
        deviceType: String = "ios",
    ): BarkResult = register(server.normalizedBaseUrl, deviceToken, server.deviceKey, deviceType)

    /** Send a push through the server (used by the in-app "test notification" button). */
    fun push(
        server: BarkServer,
        title: String?,
        body: String?,
        options: Map<String, String> = emptyMap(),
    ): BarkResult {
        val payload = buildJsonObject {
            put("device_key", server.deviceKey)
            if (title != null) put("title", title)
            if (body != null) put("body", body)
            for ((key, value) in options) put(key, value)
        }
        return post(server.endpoint("/push"), payload)
    }

    private fun post(url: String, body: JsonObject): BarkResult {
        val response = try {
            transport.post(HttpPost(url, CONTENT_TYPE_JSON, body.toString().toByteArray(Charsets.UTF_8)))
        } catch (e: HttpException) {
            return BarkResult.Unreachable(e.message, e)
        } catch (e: Exception) {
            return BarkResult.Unreachable(e.message, e)
        }

        val text = response.text
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val code = (parsed?.get("code") as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }
        val message = (parsed?.get("message") as? JsonPrimitive)?.content
        val deviceKey = assignedDeviceKey(parsed)

        return when {
            code == 200 -> BarkResult.Ok(message, text, deviceKey)
            code != null -> BarkResult.Refused(code, message, text)
            response.status in 200..299 -> BarkResult.Ok(message, text, deviceKey)
            else -> BarkResult.Refused(response.status, message ?: text.take(200).trim(), text)
        }
    }

    /**
     * `/register` answers `data.device_key` (plus a `data.key` alias for old servers). Absent for pushes; absent again
     * if the server is a fork that does not echo the key, in which case the caller keeps whatever it already had.
     */
    private fun assignedDeviceKey(parsed: JsonObject?): String? {
        val data = parsed?.get("data") as? JsonObject ?: return null
        fun field(name: String) =
            (data[name] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        return field("device_key") ?: field("key")
    }

    private fun endpointOf(serverUrl: String, path: String): String =
        BarkServer.normalize(serverUrl) + "/" + path.trimStart('/')

    companion object {
        const val CONTENT_TYPE_JSON: String = "application/json; charset=utf-8"
    }
}

/**
 * Parses the links users paste around: `bark://host/key`, `https://api.day.app/key`, `https://host:8080/key`.
 *
 * A bare key is rejected on purpose — guessing the server would mean pushing a user's notifications to someone else's
 * infrastructure.
 */
object BarkLink {

    sealed interface ParseResult {
        data class Success(val server: BarkServer) : ParseResult

        data class Failure(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParseResult.Failure("empty link")

        val normalized = when {
            trimmed.startsWith("bark://") -> "https://" + trimmed.removePrefix("bark://")
            // Any other explicit scheme is kept so the error names it instead of pretending it is https.
            trimmed.contains("://") -> trimmed
            // A bare host/key pair still works, but a bare key does not.
            trimmed.contains('/') -> "https://$trimmed"
            else -> return ParseResult.Failure("expected a bark:// or https:// link including the device key")
        }

        val stripped = normalized.substringBefore('?').substringBefore('#').trimEnd('/')
        val schemeEnd = stripped.indexOf("://")
        if (schemeEnd <= 0) return ParseResult.Failure("missing scheme")
        val scheme = stripped.substring(0, schemeEnd)
        if (scheme != "http" && scheme != "https") return ParseResult.Failure("unsupported scheme: $scheme")

        val authorityAndPath = stripped.substring(schemeEnd + 3)
        val slash = authorityAndPath.indexOf('/')
        if (slash <= 0) return ParseResult.Failure("missing device key")
        val host = authorityAndPath.substring(0, slash)
        val key = authorityAndPath.substring(slash + 1)
        if (host.isEmpty()) return ParseResult.Failure("missing host")
        if (key.isEmpty()) return ParseResult.Failure("missing device key")
        if (key.contains('/')) return ParseResult.Failure("device key contains a slash: $key")

        return ParseResult.Success(BarkServer(baseUrl = "$scheme://$host", deviceKey = key))
    }
}
