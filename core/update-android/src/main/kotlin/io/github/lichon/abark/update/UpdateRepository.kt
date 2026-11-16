package io.github.lichon.abark.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Orchestrates: throttled check -> select -> download -> verify -> user-initiated install.
 *
 * Wiring in the app:
 *  - `maybeCheck()` once on cold start (returns cached state inside the 24h window, no network);
 *  - `checkNow()` from Settings ("Check for updates");
 *  - a `WorkManager` `PeriodicWorkRequest` (24h, `CONNECTED`) as a backstop when the app is rarely opened;
 *  - UI collects [UpdateInstaller.events] to show "installing…" / failure reasons.
 */
class UpdateRepository(
    private val context: Context,
    private val source: UpdateSource,
    private val dataStore: DataStore<Preferences>,
    private val client: OkHttpClient,
    private val trustedSignerAllowlist: Set<String> = emptySet(),
    private val abi: String? = Build.SUPPORTED_ABIS.firstOrNull(),
    /** From `update.json` when available; gates the APNs transport only, never the whole app. */
    private val minSupportedVersionCode: Int = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun channel(): UpdateChannel {
        val prefs = dataStore.data.first()
        return if (prefs[CHANNEL] == "beta") UpdateChannel.BETA else UpdateChannel.STABLE
    }

    suspend fun setChannel(channel: UpdateChannel) {
        dataStore.edit { it[CHANNEL] = if (channel == UpdateChannel.BETA) "beta" else "stable" }
    }

    /** Cold-start entry point: never touches the network inside the throttle window. */
    suspend fun maybeCheck(): UpdateDecision = check(manual = false)

    suspend fun checkNow(): UpdateDecision = check(manual = true)

    private suspend fun check(manual: Boolean): UpdateDecision {
        val prefs = dataStore.data.first()
        val now = clock()
        val lastCheck = prefs[LAST_CHECK_MS] ?: 0L
        val cached = decodeCandidate(prefs[CACHED_CANDIDATE])
        val cachedMandatory = prefs[CACHED_MANDATORY] ?: false

        if (!manual && lastCheck != 0L && now - lastCheck < CHECK_INTERVAL_MS) {
            return cached?.let { UpdateDecision.Available(it, cachedMandatory) }
                ?: UpdateDecision.UpToDate(lastCheck)
        }

        val channel = if (prefs[CHANNEL] == "beta") UpdateChannel.BETA else UpdateChannel.STABLE
        return when (val result = source.releases(channel, prefs[ETAG])) {
            is ReleasesResult.Snapshot -> {
                val decision = ReleaseSelector.select(
                    releases = result.releases,
                    channel = channel,
                    installedVersionCode = installedVersionCode(),
                    installedVersion = SemanticVersion.parse(installedVersionName()),
                    abi = abi,
                    minSupportedVersionCode = minSupportedVersionCode,
                )
                dataStore.edit { p ->
                    p[LAST_CHECK_MS] = now
                    result.etag?.let { p[ETAG] = it }
                    when (decision) {
                        is UpdateDecision.Available -> {
                            p[CACHED_CANDIDATE] = json.encodeToString(
                                UpdateCandidate.serializer(), decision.candidate,
                            )
                            p[CACHED_MANDATORY] = decision.mandatory
                        }
                        else -> p.remove(CACHED_CANDIDATE)
                    }
                }
                decision
            }

            ReleasesResult.NotModified -> {
                dataStore.edit { it[LAST_CHECK_MS] = now }
                cached?.let { UpdateDecision.Available(it, cachedMandatory) }
                    ?: UpdateDecision.UpToDate(now)
            }

            // Stay quiet: keep offering the last known update if there is one.
            is ReleasesResult.Failed ->
                cached?.let { UpdateDecision.Available(it, cachedMandatory) }
                    ?: UpdateDecision.Failed(result.reason)
        }
    }

    /**
     * Downloads to the app cache, verifies size, optional published SHA-256, then the signing certificate.
     * Throws on any failure; the caller deletes nothing (the file is already removed here on mismatch).
     */
    suspend fun download(
        candidate: UpdateCandidate,
        expectedSha256: String? = null,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): ApkSignatureVerifier.VerifiedApk = withContext(Dispatchers.IO) {
        require(candidate.assetUrl.startsWith("https://")) { "refusing non-https asset url" }

        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, candidate.assetName)
        val part = File(dir, "${candidate.assetName}.part")
        val digest = MessageDigest.getInstance("SHA-256")

        client.newCall(Request.Builder().url(candidate.assetUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("http-${response.code}")
            val body = response.body ?: throw IOException("empty-body")
            val total = body.contentLength().takeIf { it > 0 } ?: candidate.assetSize
            if (total > ReleaseSelector.MAX_APK_BYTES) throw IOException("asset-too-large")

            var written = 0L
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (written > ReleaseSelector.MAX_APK_BYTES) throw IOException("asset-too-large")
                        onProgress(written, total)
                    }
                }
            }
            if (total > 0 && written != total) throw IOException("truncated-download")
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            part.delete()
            throw IOException("checksum-mismatch")
        }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }

        ApkSignatureVerifier.verify(context, target, trustedSignerAllowlist) ?: run {
            target.delete()
            throw SecurityException("signature-mismatch")
        }
    }

    /** Shows the system install dialog. Requires [UpdateInstaller.canInstallPackages] to be true. */
    suspend fun install(apk: ApkSignatureVerifier.VerifiedApk) {
        UpdateInstaller(context).install(apk)
    }

    fun installer(): UpdateInstaller = UpdateInstaller(context)

    private fun decodeCandidate(raw: String?): UpdateCandidate? = raw?.let {
        runCatching { json.decodeFromString(UpdateCandidate.serializer(), it) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

        private val LAST_CHECK_MS = longPreferencesKey("update.lastCheckMs")
        private val ETAG = stringPreferencesKey("update.etag")
        private val CACHED_CANDIDATE = stringPreferencesKey("update.cachedCandidate")
        private val CACHED_MANDATORY = booleanPreferencesKey("update.cachedMandatory")
        private val CHANNEL = stringPreferencesKey("update.channel")
    }
}
