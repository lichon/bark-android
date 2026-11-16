package io.github.lichon.abark.apns.identity

import io.github.lichon.abark.apns.Topics
import io.github.lichon.abark.apns.toHex
import io.github.lichon.abark.apns.activation.DeviceKey
import io.github.lichon.abark.apns.activation.DeviceParams
import io.github.lichon.abark.apns.activation.Pem
import io.github.lichon.abark.apns.transport.CourierCredentials
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that must survive an app restart, as plain serialisable data.
 *
 * Kept separate from the live objects (`KeyPair`, `X509Certificate`) so the same record can live in a file on desktop,
 * in SharedPreferences on Android, or in an encrypted preference store later — the codec is the only thing that has to
 * change.
 */
@Serializable
data class DeviceIdentityRecord(
    val version: Int = CURRENT_VERSION,
    val deviceClass: String,
    val uniqueDeviceId: String,
    val serialNumber: String,
    val productType: String,
    val productVersion: String,
    val buildVersion: String,
    /** PKCS#8 PEM. On Android this is the blob that belongs in the Keystore, not in plain prefs. */
    val privateKeyPem: String,
    val certificatePem: String,
    /** Courier base token (hex), reusable across reconnects. */
    val baseTokenHex: String? = null,
    /** Scoped token (hex) registered with the push server. */
    val scopedTokenHex: String? = null,
    val scopedTopic: String? = Topics.BARK,
    @SerialName("created_at") val createdAtMillis: Long = 0,
    @SerialName("updated_at") val updatedAtMillis: Long = 0,
) {
    fun params(): DeviceParams = DeviceParams(
        deviceClass = deviceClass,
        uniqueDeviceId = uniqueDeviceId,
        serialNumber = serialNumber,
        productType = productType,
        productVersion = productVersion,
        buildVersion = buildVersion,
    )

    fun privateKey(): PrivateKey = DeviceKey.parsePrivateKey(privateKeyPem)

    val certificateDer: ByteArray get() = Pem.decode(certificatePem)?.der ?: ByteArray(0)

    val certificate: X509Certificate get() =
        CertificateFactory.getInstance("X.509").generateCertificate(certificateDer.inputStream()) as X509Certificate

    fun keyPair(): KeyPair = KeyPair(certificate.publicKey, privateKey())

    fun credentials(): CourierCredentials = CourierCredentials(certificateDer, privateKey())

    val baseToken: ByteArray? get() = baseTokenHex?.hexToBytes()

    val scopedToken: ByteArray? get() = scopedTokenHex?.hexToBytes()

    /** True when the certificate is past its validity date — the app must re-activate. */
    fun isCertificateExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        runCatching { certificate.notAfter.time <= nowMillis }.getOrDefault(true)

    fun withTokens(baseToken: ByteArray?, scopedToken: ByteArray?, nowMillis: Long = System.currentTimeMillis()) =
        copy(
            baseTokenHex = baseToken?.toHex() ?: baseTokenHex,
            scopedTokenHex = scopedToken?.toHex() ?: scopedTokenHex,
            updatedAtMillis = nowMillis,
        )

    companion object {
        const val CURRENT_VERSION: Int = 1

        fun fromActivatedDevice(
            certificatePem: String,
            params: DeviceParams,
            keyPair: KeyPair,
            nowMillis: Long = System.currentTimeMillis(),
        ): DeviceIdentityRecord = DeviceIdentityRecord(
            deviceClass = params.deviceClass,
            uniqueDeviceId = params.uniqueDeviceId,
            serialNumber = params.serialNumber,
            productType = params.productType,
            productVersion = params.productVersion,
            buildVersion = params.buildVersion,
            privateKeyPem = DeviceKey.privateKeyPem(keyPair.private),
            certificatePem = certificatePem,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
    }
}

/** Storage abstraction: file on desktop/CLI, SharedPreferences (ideally encrypted) on Android. */
interface DeviceIdentityStore {
    fun load(): DeviceIdentityRecord?

    fun save(record: DeviceIdentityRecord)

    fun clear()
}

/** JSON codec shared by every store implementation. */
object IdentityCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(record: DeviceIdentityRecord): String = json.encodeToString(DeviceIdentityRecord.serializer(), record)

    /** @throws IdentityFormatException for anything that is not a record we can use. */
    fun decode(text: String): DeviceIdentityRecord {
        val record = try {
            json.decodeFromString(DeviceIdentityRecord.serializer(), text)
        } catch (e: Exception) {
            throw IdentityFormatException("identity is not valid JSON: ${e.message}", e)
        }
        if (record.version > DeviceIdentityRecord.CURRENT_VERSION) {
            throw IdentityFormatException("identity version ${record.version} is newer than this build")
        }
        if (record.privateKeyPem.isBlank() || record.certificatePem.isBlank()) {
            throw IdentityFormatException("identity is missing the device key or certificate")
        }
        return record
    }
}

class IdentityFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * File-backed store: written atomically (temp file + rename) so a crash mid-write cannot leave a truncated identity —
 * losing it means re-activating, and the old record is the only copy of a key Apple has already certified.
 */
class FileIdentityStore(
    private val file: File,
) : DeviceIdentityStore {

    override fun load(): DeviceIdentityRecord? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { IdentityCodec.decode(text) }.getOrNull()
    }

    override fun save(record: DeviceIdentityRecord) {
        val directory = file.absoluteFile.parentFile ?: throw IOException("identity file has no parent directory")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("cannot create $directory")
        val temp = File.createTempFile(file.name, ".tmp", directory)
        try {
            temp.writeText(IdentityCodec.encode(record))
            restrictToOwner(temp)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { throw e }
        } finally {
            temp.delete()
        }
        restrictToOwner(file)
    }

    override fun clear() {
        file.delete()
    }

    private fun restrictToOwner(target: File) {
        // Best effort: POSIX permissions on desktop, no-op where unsupported (Windows, Android app sandbox).
        runCatching {
            val path = target.toPath()
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(path, perms)
        }
    }
}

/** In-memory store for tests and for a CLI session that must not persist anything. */
class MemoryIdentityStore(private var record: DeviceIdentityRecord? = null) : DeviceIdentityStore {
    override fun load(): DeviceIdentityRecord? = record

    override fun save(record: DeviceIdentityRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
}
