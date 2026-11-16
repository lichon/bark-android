package io.github.lichon.abark.update

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ReleasesResult {
    /** `304 Not Modified` — the cached decision is still valid and this call did not cost rate limit. */
    data object NotModified : ReleasesResult

    data class Snapshot(
        val releases: List<GithubRelease>,
        val etag: String?,
    ) : ReleasesResult

    data class Failed(val reason: String, val cause: Throwable? = null) : ReleasesResult
}

/**
 * Where update metadata comes from. Today: the GitHub Releases API. Tomorrow (optional): a static
 * `update.json` on GitHub Pages, which has no rate limit and no 60 req/h-per-IP ceiling.
 */
interface UpdateSource {
    suspend fun releases(channel: UpdateChannel, etag: String? = null): ReleasesResult
}

/**
 * GitHub Releases API client.
 *
 * Notes for reviewers:
 *  - The API is unauthenticated on purpose: a GitHub token in an APK is extractable, and a token scoped to
 *    `public_repo` leaks nothing useful but invites abuse. Rate limit is ~60 req/h per IP, which is why the
 *    repository throttles to once a day and sends `If-None-Match` (304 responses are not counted).
 *  - `User-Agent` is required by the GitHub API.
 *  - Only `GET /releases` is used (not `/releases/latest`) so we can filter by channel ourselves.
 */
class GithubReleaseSource(
    private val client: OkHttpClient,
    private val owner: String,
    private val repo: String,
    private val json: Json = DefaultJson,
) : UpdateSource {

    override suspend fun releases(channel: UpdateChannel, etag: String?): ReleasesResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases?per_page=30")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "abark-android-updater")
                .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 304 -> ReleasesResult.NotModified

                        response.code == 200 -> {
                            val payload = response.body?.string().orEmpty()
                            val releases = json.decodeFromString<List<GithubRelease>>(payload)
                            ReleasesResult.Snapshot(releases, response.header("ETag"))
                        }

                        // 403/429 with an exhausted quota: stay quiet, retry tomorrow.
                        response.code == 403 &&
                            response.header("X-RateLimit-Remaining") == "0" ->
                            ReleasesResult.Failed("github-rate-limited")

                        response.code == 429 -> ReleasesResult.Failed("github-rate-limited")

                        else -> ReleasesResult.Failed("http-${response.code}")
                    }
                }
            } catch (e: IOException) {
                ReleasesResult.Failed("network", e)
            }
        }

    companion object {
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
