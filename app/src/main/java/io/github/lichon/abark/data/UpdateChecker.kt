package io.github.lichon.abark.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import io.github.lichon.abark.update.GithubReleaseSource
import io.github.lichon.abark.update.UpdateRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "abark-update")

/**
 * Update checking against GitHub Releases (`docs/DISTRIBUTION.md`).
 *
 * Only *checking* is wired: the install path (`UpdateRepository.download` + `PackageInstaller`) needs a signed release
 * and the "install unknown apps" permission, so it stays a manual flow for a personal build — the app reports the
 * version and opens the release page.
 */
class UpdateChecker(context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val repository = UpdateRepository(
        context = context.applicationContext,
        source = GithubReleaseSource(client, OWNER, REPO),
        dataStore = context.applicationContext.updateDataStore,
        client = client,
    )

    suspend fun checkNow(): UpdateSummary = when (val decision = repository.checkNow()) {
        is io.github.lichon.abark.update.UpdateDecision.Available ->
            UpdateSummary.Available(decision.candidate.version, decision.candidate.releaseUrl, decision.mandatory)

        is io.github.lichon.abark.update.UpdateDecision.UpToDate -> UpdateSummary.UpToDate
        is io.github.lichon.abark.update.UpdateDecision.NothingUsable -> UpdateSummary.Nothing(decision.reason)
        is io.github.lichon.abark.update.UpdateDecision.Failed -> UpdateSummary.Failed(decision.reason)
    }

    companion object {
        const val OWNER: String = "lichon"
        const val REPO: String = "bark-android"
    }
}

sealed interface UpdateSummary {
    data class Available(val version: String, val releaseUrl: String, val mandatory: Boolean) : UpdateSummary

    data object UpToDate : UpdateSummary

    data class Nothing(val reason: String) : UpdateSummary

    data class Failed(val reason: String) : UpdateSummary
}
