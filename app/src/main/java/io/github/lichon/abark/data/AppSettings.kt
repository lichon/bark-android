package io.github.lichon.abark.data

import android.content.Context
import io.github.lichon.abark.bark.BarkServer

/**
 * Non-secret settings. The device key is a shared secret in the sense that anyone holding it can push to this device,
 * so it lives here rather than in logs; the *identity* (private key) lives in [PrefsIdentityStore].
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Server the courier registers with. Defaults to the public [DEFAULT_SERVER] service so a fresh install only needs
     * a device key; a blank write falls back to that default instead of emptying the field.
     */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER).orEmpty().ifBlank { DEFAULT_SERVER }
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().ifBlank { DEFAULT_SERVER }).apply()

    var deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value.trim()).apply()

    /**
     * Keepalive in seconds; the courier is idle for minutes at a time, so this is the main battery knob.
     *
     * Capped at [MAX_KEEPALIVE_SECONDS] because the interval doubles as the socket read timeout: a longer gap than
     * that also delays noticing a dead line, and every extra minute of idleness is another chance at a carrier NAT
     * (commonly 30 s–5 min) reaping the mapping. Apple itself keeps idle courier sockets open far longer, so the NAT is
     * what we tune against.
     */
    var keepAliveSeconds: Int
        get() = prefs.getInt(KEY_KEEPALIVE, DEFAULT_KEEPALIVE_SECONDS)
            .coerceIn(MIN_KEEPALIVE_SECONDS, MAX_KEEPALIVE_SECONDS)
        set(value) = prefs.edit()
            .putInt(KEY_KEEPALIVE, value.coerceIn(MIN_KEEPALIVE_SECONDS, MAX_KEEPALIVE_SECONDS))
            .apply()

    /** Start the courier on boot (and after an app update). */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** Registered scoped token, for the diagnostics card. Not authoritative — the identity file is. */
    var lastRegisteredToken: String?
        get() = prefs.getString(KEY_REGISTERED_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REGISTERED_TOKEN, value).apply()

    /** A usable push target: the server URL plus the key the server assigned during registration. */
    val hasServer: Boolean get() = serverUrl.isNotBlank() && deviceKey.isNotBlank()

    fun server(): BarkServer? =
        if (hasServer) runCatching { BarkServer(serverUrl, deviceKey) }.getOrNull() else null

    companion object {
        const val MIN_KEEPALIVE_SECONDS: Int = 30
        const val MAX_KEEPALIVE_SECONDS: Int = 300
        const val DEFAULT_KEEPALIVE_SECONDS: Int = 120
        const val DEFAULT_SERVER: String = BarkServer.DEFAULT_SERVER

        /** Accepts what a user types/pastes: blank means the default, so `BarkServer` never sees an empty URL. */
        fun normalizeServerUrl(raw: String): String = raw.trim().ifBlank { DEFAULT_SERVER }
        private const val PREFS = "abark.settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_KEEPALIVE = "keepalive_seconds"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_REGISTERED_TOKEN = "registered_token"
    }
}
