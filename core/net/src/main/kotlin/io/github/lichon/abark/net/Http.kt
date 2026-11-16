package io.github.lichon.abark.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The smallest HTTP surface the app needs: one POST with an already-encoded body.
 *
 * There is deliberately no client library here. `HttpURLConnection` exists on the JDK and on Android, works on both
 * without a dependency, and every call site is a single request to a well-known endpoint (Alberts activation, the
 * bark-server API, the GitHub releases API). Tests inject a fake, so no test ever opens a socket.
 */
class HttpPost(val url: String, val contentType: String, val body: ByteArray) {
    constructor(url: String, contentType: String, body: String) : this(url, contentType, body.toByteArray(Charsets.UTF_8))
}

class HttpResult(val status: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

fun interface HttpTransport {
    fun post(request: HttpPost): HttpResult
}

class HttpException(message: String, val status: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/**
 * `HttpURLConnection` transport. Redirects are followed (the bark server may sit behind one), the response is capped,
 * and failures surface as [HttpException] rather than as a null that callers forget to check.
 */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxResponseBytes: Int = 1 shl 20,
    private val userAgent: String = "abark",
) : HttpTransport {

    override fun post(request: HttpPost): HttpResult {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Content-Type", request.contentType)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(request.body) }

            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            return HttpResult(status, stream?.use { it.readCapped(maxResponseBytes) } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readCapped(limit: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) throw HttpException("response exceeds $limit bytes")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
