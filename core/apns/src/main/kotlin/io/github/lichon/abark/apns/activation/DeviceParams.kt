package io.github.lichon.abark.apns.activation

import java.security.SecureRandom
import java.util.UUID

/**
 * The identity Alberts is asked to certify.
 *
 * Defaults follow the only activation path that is known to work today: Alberts' Windows service
 * (`?device=Windows`), which is what makes activation possible without an Apple device at all. Every value here is
 * **empirical** — see docs/PROTOCOL.md §Activation and the phase-0 checklist.
 *
 * Never reuse somebody else's identifiers: [random] mints a fresh identity so no two installs share a serial.
 */
data class DeviceParams(
    val deviceClass: String = DEFAULT_DEVICE_CLASS,
    val uniqueDeviceId: String,
    val serialNumber: String,
    val productType: String = DEFAULT_PRODUCT_TYPE,
    val productVersion: String = DEFAULT_PRODUCT_VERSION,
    val buildVersion: String = DEFAULT_BUILD_VERSION,
) {
    init {
        require(uniqueDeviceId.isNotBlank()) { "uniqueDeviceId must not be blank" }
        require(serialNumber.isNotBlank()) { "serialNumber must not be blank" }
        for ((name, value) in asMap()) {
            require(value.length <= MAX_FIELD_LENGTH) { "$name longer than $MAX_FIELD_LENGTH characters" }
            require(value.none { it < ' ' }) { "$name contains control characters" }
        }
    }

    /** The `device` query parameter value, e.g. `Windows`. */
    val deviceQuery: String get() = encodeFormValue(deviceClass)

    private fun asMap(): List<Pair<String, String>> = listOf(
        "deviceClass" to deviceClass,
        "uniqueDeviceId" to uniqueDeviceId,
        "serialNumber" to serialNumber,
        "productType" to productType,
        "productVersion" to productVersion,
        "buildVersion" to buildVersion,
    )

    companion object {
        const val DEFAULT_DEVICE_CLASS: String = "Windows"
        const val DEFAULT_PRODUCT_TYPE: String = "windows1,1"
        const val DEFAULT_PRODUCT_VERSION: String = "10.6.4"
        const val DEFAULT_BUILD_VERSION: String = "10.6.4"
        const val MAX_FIELD_LENGTH: Int = 128

        /** Fresh, non-shared identity for the known-good Windows activation path. */
        fun random(random: SecureRandom = SecureRandom()): DeviceParams = DeviceParams(
            uniqueDeviceId = UUID.randomUUID().toString(),
            serialNumber = randomToken(12, random),
        )

        /** Fixture used by tests and by the CLI when a stable identity is wanted. */
        fun windows(
            uniqueDeviceId: String = UUID.randomUUID().toString(),
            serialNumber: String = "WindowSerial",
        ): DeviceParams = DeviceParams(
            uniqueDeviceId = uniqueDeviceId,
            serialNumber = serialNumber,
        )

        private val TOKEN_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        internal fun randomToken(length: Int, random: SecureRandom): String =
            buildString(length) {
                repeat(length) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
            }
    }
}

/**
 * `application/x-www-form-urlencoded` value encoding (RFC 3986 unreserved set kept verbatim). Space becomes `%20`
 * rather than `+`; both decode identically, and this avoids ambiguity in the plist body.
 */
internal fun encodeFormValue(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size + 8)
    for (byte in bytes) {
        val char = byte.toInt().toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '.' || char == '_' || char == '~'
        ) {
            out.append(char)
        } else {
            out.append('%')
            out.append(HEX[(byte.toInt() shr 4) and 0x0F])
            out.append(HEX[byte.toInt() and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
