package io.github.lichon.abark.apns.activation

import java.security.KeyPair
import java.util.UUID

/**
 * Alberts, ready to use: [ActivationClient] + the FairPlay material from [AlbertFairPlay].
 *
 * This is the one entry point the app and the CLI need for "get a device certificate". Everything mechanism-specific
 * (plist shape, CSR, signature, HTML response) stays in [ActivationClient]; everything deployment-specific (which
 * FairPlay key) is injected, defaulting to the embedded pair. Tests point [client] at a fake transport and [credentials]
 * at a throwaway key, so the whole path is exercised without Apple material.
 *
 * ```kotlin
 * when (val outcome = Albert().activate()) {
 *     is ActivationOutcome.Activated -> save(outcome.device)
 *     is ActivationOutcome.Rejected -> show(outcome.failure)
 * }
 * ```
 *
 * Each successful call has certified a *fresh* device key, so callers must persist the returned
 * [ActivatedDevice.keyPair] — Apple will not re-issue, and the private key is only in memory until they do.
 */
class Albert(
    private val client: ActivationClient = ActivationClient(),
    private val credentials: FairPlayCredentials = AlbertFairPlay.credentials,
) {

    /** The credentials this instance signs with, for tooling that wants to report which chain was used. */
    val fairPlayCredentials: FairPlayCredentials get() = credentials

    /**
     * Activates [params] with [keyPair], requesting the certificate for a random common name unless [commonName] is
     * given. [randomness] and [commonName] are parameters only so tests and the CLI can pin them.
     */
    fun activate(
        params: DeviceParams = DeviceParams.windows(),
        keyPair: KeyPair = DeviceKey.generate(),
        randomness: String = UUID.randomUUID().toString(),
        commonName: String = UUID.randomUUID().toString(),
    ): ActivationOutcome =
        client.activate(
            params = params,
            keyPair = keyPair,
            credentials = credentials,
            activationInfo = ActivationInfo.create(params, keyPair, credentials, randomness, commonName),
        )

    companion object {
        /** The production client: Apple's Alberts endpoint, embedded FairPlay material. */
        fun default(): Albert = Albert()
    }
}
