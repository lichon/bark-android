package io.github.lichon.abark.apns.activation

import java.io.StringReader
import java.security.Key
import java.security.KeyPair
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter

/**
 * The device key Apple certifies during activation.
 *
 * 1024 bits is not a security choice — Alberts only issues certificates for 1024-bit RSA device keys, and the courier
 * rejects anything else. It authenticates a socket, it does not protect message contents.
 */
object DeviceKey {

    const val KEY_SIZE_BITS: Int = 1024

    fun generate(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS, random) }.generateKeyPair()

    fun modulusBits(key: Key): Int = when (key) {
        is RSAPublicKey -> key.modulus.bitLength()
        is RSAPrivateKey -> key.modulus.bitLength()
        else -> 0
    }

    fun isDeviceKeySize(key: Key): Boolean = modulusBits(key) == KEY_SIZE_BITS

    /** PKCS#8 — what both the JDK and Android Keystore-adjacent tooling expect. */
    fun privateKeyPem(key: PrivateKey): String = Pem.encode("PRIVATE KEY", key.encoded)

    fun publicKeyPem(key: PublicKey): String = Pem.encode("PUBLIC KEY", key.encoded)

    /**
     * Accepts PKCS#8 (`BEGIN PRIVATE KEY`) and PKCS#1 (`BEGIN RSA PRIVATE KEY`, what OpenSSL emits).
     *
     * PKCS#8 goes through plain JCA — that is the format this module writes and the one the Android app reads back, and
     * it keeps BouncyCastle off the app's runtime path (Android bundles its own repackaged BC). PKCS#1 has no JCA
     * equivalent, so it falls back to BouncyCastle's PEM parser.
     */
    fun parsePrivateKey(pem: String): PrivateKey {
        val block = Pem.decode(pem) ?: throw PemException("no private key in PEM input")
        if (block.type == "PRIVATE KEY") {
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(block.der))
            } catch (e: Exception) {
                throw PemException("PKCS#8 key is not a usable RSA key: ${e.message}", e)
            }
        }
        return PEMParser(StringReader(pem)).use { parser ->
            val converter = JcaPEMKeyConverter()
            var parsed = parser.readObject()
            while (parsed != null) {
                when (parsed) {
                    is PEMKeyPair -> return converter.getKeyPair(parsed).private
                    is PrivateKeyInfo -> return converter.getPrivateKey(parsed)
                }
                parsed = parser.readObject()
            }
            throw PemException("no private key in PEM input")
        }
    }

    /** `BEGIN PUBLIC KEY` (X.509 SubjectPublicKeyInfo) via plain JCA. */
    fun parsePublicKey(pem: String): PublicKey {
        val block = Pem.decode(pem) ?: throw PemException("no public key in PEM input")
        return try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(block.der))
        } catch (e: Exception) {
            throw PemException("public key is not a usable RSA key: ${e.message}", e)
        }
    }
}
