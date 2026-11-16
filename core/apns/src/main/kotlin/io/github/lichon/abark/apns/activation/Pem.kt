package io.github.lichon.abark.apns.activation

import java.util.Base64

/** A single `-----BEGIN X-----` / `-----END X-----` block. */
class PemBlock(val type: String, val der: ByteArray) {
    override fun toString(): String = "PemBlock($type, ${der.size}B)"
}

/**
 * Minimal PEM codec. Both platforms hand us PEM (CSR request, Alberts certificate) or want it back (persisted
 * identity), and `java.util.Base64` covers the transport, so no ASN.1 library is needed for this part.
 */
object Pem {

    fun encode(type: String, der: ByteArray, lineLength: Int = 64): String {
        require(lineLength > 0) { "line length must be positive" }
        val body = Base64.getEncoder().encodeToString(der)
        val builder = StringBuilder(body.length + 64)
        builder.append("-----BEGIN ").append(type).append("-----\n")
        var offset = 0
        while (offset < body.length) {
            val end = minOf(offset + lineLength, body.length)
            builder.append(body, offset, end).append('\n')
            offset = end
        }
        builder.append("-----END ").append(type).append("-----\n")
        return builder.toString()
    }

    /** First block in [text], or null when there is none. */
    fun decode(text: String): PemBlock? = decodeAll(text).firstOrNull()

    /** Every block in [text], in order (Alberts hands back a chain, not a single certificate). */
    fun decodeAll(text: String): List<PemBlock> {
        val blocks = ArrayList<PemBlock>()
        var index = 0
        while (true) {
            val begin = text.indexOf("-----BEGIN ", index)
            if (begin < 0) break
            val typeStart = begin + "-----BEGIN ".length
            val typeEnd = text.indexOf("-----", typeStart)
            if (typeEnd < 0) break
            val type = text.substring(typeStart, typeEnd).trim()
            val bodyStart = text.indexOf('\n', typeEnd)
            if (bodyStart < 0) break
            val endMarker = "-----END $type-----"
            val end = text.indexOf(endMarker, bodyStart)
            if (end < 0) break
            val payload = text.substring(bodyStart + 1, end).filterNot { it.isWhitespace() }
            index = end + endMarker.length
            if (payload.isEmpty()) continue
            blocks += try {
                PemBlock(type, Base64.getDecoder().decode(payload))
            } catch (e: IllegalArgumentException) {
                throw PemException("bad base64 in PEM block $type", e)
            }
        }
        return blocks
    }

    /** Concatenates the DER of every certificate in [chainPem] — the layout Alberts expects in `FairPlayCertChain`. */
    fun concatenateDer(chainPem: String): ByteArray {
        val blocks = decodeAll(chainPem)
        if (blocks.isEmpty()) throw PemException("PEM chain contains no blocks")
        val total = blocks.sumOf { it.der.size }
        val out = ByteArray(total)
        var offset = 0
        for (block in blocks) {
            block.der.copyInto(out, offset)
            offset += block.der.size
        }
        return out
    }
}

class PemException(message: String, cause: Throwable? = null) : Exception(message, cause)
