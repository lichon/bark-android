package io.github.lichon.abark.data

import android.content.Context
import io.github.lichon.abark.apns.identity.DeviceIdentityRecord
import io.github.lichon.abark.apns.identity.DeviceIdentityStore
import io.github.lichon.abark.apns.identity.IdentityCodec

/**
 * Identity storage on Android.
 *
 * The record contains the device private key, so this is the one place that has to be hardened: today it is app-private
 * SharedPreferences (unreadable by other apps, included in no backup because `allowBackup=false`), and the planned
 * upgrade is a Keystore-wrapped AES-GCM blob — `androidx.security:security-crypto` is deprecated and is not used.
 *
 * The identity holds a device private key plus the certificate Apple issued over it. It can be produced here
 * (Settings → Activate on this device, which signs with the FairPlay material bundled in `:core:apns`) or imported as
 * JSON from `tools/cli activate`; both end up in this store.
 */
class PrefsIdentityStore(context: Context) : DeviceIdentityStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): DeviceIdentityRecord? {
        val raw = prefs.getString(KEY_IDENTITY, null) ?: return null
        return runCatching { IdentityCodec.decode(raw) }.getOrNull()
    }

    override fun save(record: DeviceIdentityRecord) {
        prefs.edit().putString(KEY_IDENTITY, IdentityCodec.encode(record)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_IDENTITY).apply()
    }

    companion object {
        private const val PREFS = "abark.identity"
        private const val KEY_IDENTITY = "identity_json"
    }
}
