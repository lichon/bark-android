package io.github.lichon.abark.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * The only real trust boundary in the update path.
 *
 * The APK, its checksums and `update.json` all come from the same GitHub release, so a compromised release
 * compromises all of them. Comparing the archive's signing certificate with the certificate already installed on
 * the device means an attacker needs the *release signing key*, not just push access to the repo.
 *
 * [allowlist] exists for an eventual key rotation: publish the new cert digest in the current release, ship it in
 * the next update, then add it here. Rotation is still a manual, deliberate act — see docs/DISTRIBUTION.md §6.
 */
object ApkSignatureVerifier {

    /** Opaque proof that an APK passed signature verification; the only thing [UpdateInstaller] will install. */
    class VerifiedApk internal constructor(val file: File)

    fun installedSignerDigests(
        context: Context,
        packageName: String = context.packageName,
    ): Set<String> = try {
        digestsOf(context.packageManager.getPackageInfo(packageName, signatureFlags()))
    } catch (e: PackageManager.NameNotFoundException) {
        emptySet()
    }

    fun archiveSignerDigests(context: Context, apk: File): Set<String> =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags())
            ?.let(::digestsOf)
            .orEmpty()

    /**
     * Returns a [VerifiedApk] only if every signer of [apk] is trusted (the installed signer digests plus any
     * explicit rotation allowlist), otherwise null. The caller must treat null as "delete the file".
     */
    fun verify(
        context: Context,
        apk: File,
        allowlist: Set<String> = emptySet(),
    ): VerifiedApk? {
        if (!apk.isFile || apk.length() == 0L) return null
        val trusted = installedSignerDigests(context) + allowlist
        if (trusted.isEmpty()) return null
        val actual = archiveSignerDigests(context, apk)
        if (actual.isEmpty() || !actual.all { it in trusted }) return null
        return VerifiedApk(apk)
    }

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    private fun digestsOf(info: PackageInfo): Set<String> {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // `apkContentsSigners` covers single- and multi-signer APKs and is not deprecated
            // (`signingCertificate` was removed from the SDK after API 33).
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { sha256(it.toByteArray()) }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
