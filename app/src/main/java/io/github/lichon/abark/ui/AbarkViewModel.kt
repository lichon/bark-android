package io.github.lichon.abark.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lichon.abark.Graph
import io.github.lichon.abark.apns.activation.ActivationFailure
import io.github.lichon.abark.apns.activation.ActivationOutcome
import io.github.lichon.abark.apns.activation.Albert
import io.github.lichon.abark.apns.activation.DeviceParams
import io.github.lichon.abark.apns.identity.DeviceIdentityRecord
import io.github.lichon.abark.apns.identity.IdentityCodec
import io.github.lichon.abark.apns.transport.CourierStatus
import io.github.lichon.abark.bark.BarkApi
import io.github.lichon.abark.bark.BarkResult
import io.github.lichon.abark.data.AppSettings
import io.github.lichon.abark.data.StoredMessage
import io.github.lichon.abark.data.UpdateChecker
import io.github.lichon.abark.data.UpdateSummary
import io.github.lichon.abark.service.CourierService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val running: Boolean = false,
    val statusTitle: String = "Idle",
    val statusDetail: String = "",
    val healthy: Boolean = false,
    val messages: List<StoredMessage> = emptyList(),
    val serverUrl: String = AppSettings.DEFAULT_SERVER,
    val deviceKey: String = "",
    val keepAliveSeconds: Int = 120,
    val autoStart: Boolean = true,
    val hasIdentity: Boolean = false,
    val identitySummary: String = "",
    val scopedToken: String = "",
    val notice: String? = null,
    val busy: Boolean = false,
    val versionName: String = "",
) {
    /** A server is only usable once both halves are known. */
    val hasServer: Boolean get() = serverUrl.isNotBlank() && deviceKey.isNotBlank()
}

/**
 * Thin view model: all the logic lives in the tested JVM modules and in `Graph`. This class only maps courier status and
 * stored messages onto UI state, and forwards user actions.
 */
class AbarkViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings get() = Graph.settings

    init {
        refreshFromSettings()
        viewModelScope.launch {
            Graph.status.collect { status -> _state.update { it.withStatus(status) } }
        }
        viewModelScope.launch {
            Graph.messages.messages.collect { messages -> _state.update { it.copy(messages = messages) } }
        }
        viewModelScope.launch {
            Graph.registration.collect { registration ->
                if (!registration.isNullOrBlank()) {
                    // A registration can mint the device key, so the UI has to pick up the new settings.
                    refreshFromSettings()
                    _state.update { it.copy(notice = registration) }
                }
            }
        }
    }

    fun refreshFromSettings() {
        val record = Graph.identityStore.load()
        _state.update {
            it.copy(
                serverUrl = settings.serverUrl,
                deviceKey = settings.deviceKey,
                keepAliveSeconds = settings.keepAliveSeconds,
                autoStart = settings.autoStart,
                hasIdentity = record != null,
                identitySummary = record?.let { r ->
                    "${r.deviceClass} · ${r.productType} ${r.productVersion} · cert ${if (r.isCertificateExpired()) "expired" else "valid"}"
                }.orEmpty(),
                scopedToken = record?.scopedTokenHex.orEmpty(),
                versionName = appVersionName(),
            ).withStatus(Graph.status.value)
        }
    }

    private fun appVersionName(): String = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * Persists the server and asks the courier to register this device's scoped token with it.
     *
     * The device key is not user input: `bark-server` mints one during `/register` and the courier stores whatever the
     * server answers. Registering is a courier-side side effect — the token only exists on a live connection — so the
     * connection is (re)started here and the outcome arrives through [Graph.registration].
     */
    fun register(serverUrl: String) {
        settings.serverUrl = AppSettings.normalizeServerUrl(serverUrl)
        refreshFromSettings()
        if (!_state.value.hasIdentity) {
            _state.update { it.copy(notice = "Server saved — activate or import an identity to register") }
            return
        }
        // Reconnecting picks up the new server and re-registers the scoped token there.
        CourierService.stop(getApplication())
        CourierService.start(getApplication())
        _state.update { it.copy(notice = "Registering with ${settings.serverUrl}") }
    }

    /** Keepalive is baked into the courier config at connect time, so a change takes effect on the next connection. */
    fun setKeepAlive(seconds: Int) {
        if (seconds !in AppSettings.MIN_KEEPALIVE_SECONDS..AppSettings.MAX_KEEPALIVE_SECONDS) {
            val range = "${AppSettings.MIN_KEEPALIVE_SECONDS}–${AppSettings.MAX_KEEPALIVE_SECONDS}"
            _state.update {
                it.copy(
                    notice = "Keepalive must be $range seconds " +
                        "(${AppSettings.DEFAULT_KEEPALIVE_SECONDS} recommended)",
                )
            }
            return
        }
        settings.keepAliveSeconds = seconds
        refreshFromSettings()
        if (_state.value.running) {
            CourierService.stop(getApplication())
            CourierService.start(getApplication())
        }
        _state.update { it.copy(notice = "Keepalive set to ${settings.keepAliveSeconds}s") }
    }

    fun setAutoStart(enabled: Boolean) {
        settings.autoStart = enabled
        _state.update { it.copy(autoStart = enabled) }
    }

    /**
     * Runs Alberts activation on the phone with the FairPlay material bundled in `:core:apns` and stores the result.
     *
     * This certifies a *new* device key, so the existing identity is left untouched until Alberts answers with a
     * certificate for the key we generated — a failed or refused activation must not cost the user a working identity.
     * On success the courier is started, so the Servers tab's Activate button lands the user in a connected state
     * instead of making them press a second button.
     */
    fun activateIdentity() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val params = DeviceParams.random()
            val outcome = withContext(Dispatchers.IO) {
                runCatching { Albert().activate(params) }.getOrElse { error ->
                    ActivationOutcome.Rejected(
                        ActivationFailure(ActivationFailure.Reason.TRANSPORT_ERROR, error.message, cause = error),
                    )
                }
            }
            val message = when (outcome) {
                is ActivationOutcome.Activated -> {
                    val device = outcome.device
                    runCatching {
                        Graph.identityStore.save(
                            DeviceIdentityRecord.fromActivatedDevice(
                                certificatePem = device.certificatePem,
                                params = device.params,
                                keyPair = device.keyPair,
                            ),
                        )
                    }.fold(
                        onSuccess = {
                            CourierService.start(getApplication())
                            "Activated ${params.uniqueDeviceId.take(8)}…; certificate valid until ${device.notAfter}"
                        },
                        onFailure = { "Activated, but saving the identity failed: ${it.message}" },
                    )
                }

                is ActivationOutcome.Rejected -> "Activation failed: ${outcome.failure}"
            }
            _state.update { it.copy(busy = false, notice = message) }
            refreshFromSettings()
        }
    }

    fun importIdentity(json: String) {
        val record = runCatching { IdentityCodec.decode(json.trim()) }.getOrElse { error ->
            _state.update { state -> state.copy(notice = "Invalid identity: ${error.message}") }
            return
        }
        Graph.identityStore.save(record)
        _state.update { it.copy(notice = "Identity imported for ${record.uniqueDeviceId.take(8)}…") }
        refreshFromSettings()
    }

    fun clearIdentity() {
        Graph.identityStore.clear()
        CourierService.stop(getApplication())
        _state.update { it.copy(notice = "Identity removed") }
        refreshFromSettings()
    }

    fun start() {
        if (!_state.value.hasIdentity) {
            _state.update { it.copy(notice = "Activate or import an identity first (Settings tab)") }
            return
        }
        CourierService.start(getApplication())
        _state.update { it.copy(notice = null) }
    }

    fun stop() {
        CourierService.stop(getApplication())
    }

    fun testPush() {
        val server = settings.server()
        if (server == null) {
            _state.update { it.copy(notice = "Set a bark server before testing") }
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                BarkApi().push(server, "ABark", "Test push from the app", mapOf("group" to "abark"))
            }
            val message = when (result) {
                is BarkResult.Ok -> "Test push accepted by ${server.normalizedBaseUrl}"
                is BarkResult.Refused -> "Server refused: ${result.code} ${result.message ?: ""}"
                is BarkResult.Unreachable -> "Server unreachable: ${result.detail ?: "unknown"}"
            }
            _state.update { it.copy(busy = false, notice = message) }
        }
    }

    fun checkForUpdates() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching { UpdateChecker(getApplication()).checkNow() }
                    .getOrElse { UpdateSummary.Failed(it.message ?: "check failed") }
            }
            val message = when (summary) {
                is UpdateSummary.Available ->
                    "Update ${summary.version} available: ${summary.releaseUrl.ifBlank { "see GitHub releases" }}"
                UpdateSummary.UpToDate -> "ABark is up to date"
                is UpdateSummary.Nothing -> "No installable release (${summary.reason})"
                is UpdateSummary.Failed -> "Update check failed: ${summary.reason}"
            }
            _state.update { it.copy(busy = false, notice = message) }
        }
    }

    fun clearMessages() {
        Graph.messages.clear()
        Graph.notifier.cancelAll()
    }

    fun markRead(id: String) {
        Graph.messages.markRead(id)
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun copyShareLink() {
        val server = settings.server() ?: return
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("ABark share link", server.shareLink()))
        _state.update { it.copy(notice = "Share link copied") }
    }

    private fun UiState.withStatus(status: CourierStatus): UiState = copy(
        running = status is CourierStatus.Connected ||
            status is CourierStatus.Connecting ||
            status is CourierStatus.Reconnecting,
        statusTitle = when (status) {
            is CourierStatus.Connected -> "Connected"
            is CourierStatus.Connecting -> "Connecting"
            is CourierStatus.Reconnecting -> "Reconnecting"
            is CourierStatus.Stopped -> "Stopped"
            CourierStatus.Idle -> "Idle"
        },
        statusDetail = when (status) {
            is CourierStatus.Connected -> "${status.host} · ${status.baseToken.size * 8}-bit token"
            is CourierStatus.Connecting -> "${status.host} (attempt ${status.attempt})"
            is CourierStatus.Reconnecting ->
                "attempt ${status.attempt} in ${status.delayMillis / 1000}s · ${status.failure.reason.name.lowercase()}"
            is CourierStatus.Stopped -> status.failure?.let { it.detail ?: it.reason.name.lowercase() } ?: "not running"
            CourierStatus.Idle -> ""
        },
        healthy = status is CourierStatus.Connected,
    )
}
