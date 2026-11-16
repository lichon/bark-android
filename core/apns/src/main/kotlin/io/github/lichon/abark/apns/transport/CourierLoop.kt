package io.github.lichon.abark.apns.transport

import io.github.lichon.abark.apns.Topics
import java.security.PrivateKey
import java.security.SecureRandom

/**
 * Host selection. Real APNs clients pick `N-courier.push.apple.com` with N in 1..50; after a failure they move to a
 * different index rather than hammering the same box (docs/PROTOCOL.md §Transport).
 */
fun interface CourierHostProvider {
    fun nextHost(): String

    companion object {
        const val HOST_SUFFIX: String = "courier.push.apple.com"
        const val HOST_RANGE: Int = 50

        /** Deterministic rotation over the documented 1..50 range. */
        fun rotating(random: SecureRandom = SecureRandom()): CourierHostProvider {
            var index = (random.nextInt(HOST_RANGE) + 1)
            return CourierHostProvider {
                index = (index % HOST_RANGE) + 1
                "$index-$HOST_SUFFIX"
            }
        }
    }
}

/** What the outside world observes about the connection. Rendered by the app's diagnostics screen. */
sealed interface CourierStatus {
    data object Idle : CourierStatus

    data class Connecting(val host: String, val attempt: Int) : CourierStatus

    data class Connected(
        val host: String,
        val baseToken: ByteArray,
        val scopedToken: ByteArray?,
        val negotiatedAlpn: String?,
    ) : CourierStatus

    data class Reconnecting(
        val host: String?,
        val attempt: Int,
        val failure: CourierFailure,
        val delayMillis: Long,
    ) : CourierStatus

    data class Stopped(val failure: CourierFailure?) : CourierStatus
}

/**
 * Owns the connect → session → backoff cycle.
 *
 * Blocking by design: the caller runs it on a dedicated thread (Android: the foreground service's worker). [stop]
 * closes the current channel, which unblocks the session immediately.
 */
class CourierLoop(
    private val credentials: CourierCredentials,
    private val config: CourierConfig,
    private val channelFactory: CourierChannelFactory,
    private val hostProvider: CourierHostProvider = CourierHostProvider.rotating(),
    private val port: Int = TlsCourierChannelFactory.DEFAULT_PORT,
    private val listener: CourierListener,
    private val statusListener: (CourierStatus) -> Unit = {},
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile
    private var stopped = false

    @Volatile
    private var current: CourierSession? = null

    /** Last base token seen, so a reconnect can reuse it without touching storage. */
    @Volatile
    private var baseToken: ByteArray? = null

    @Volatile
    private var lastScopedToken: ByteArray? = null

    fun stop() {
        stopped = true
        current?.close()
    }

    /**
     * Runs until [stop]. Returns the failure that ended the last attempt, if any.
     */
    fun run(): CourierFailure? {
        var attempt = 0
        var lastFailure: CourierFailure? = null
        statusListener(CourierStatus.Idle)

        while (!stopped) {
            attempt++
            val host = hostProvider.nextHost()
            statusListener(CourierStatus.Connecting(host, attempt))

            var channel: CourierChannel? = null
            val session: CourierSession
            try {
                channel = channelFactory.open(host, port)
                session = CourierSession(
                    channel = channel,
                    config = config,
                    certificateDer = credentials.certificateDer,
                    privateKey = credentials.privateKey,
                    listener = object : CourierListener by listener {
                        override fun onBaseToken(token: ByteArray) {
                            baseToken = token
                            listener.onBaseToken(token)
                        }

                        override fun onScopedToken(token: ByteArray, topicHash: ByteArray) {
                            lastScopedToken = token
                            listener.onScopedToken(token, topicHash)
                        }
                    },
                    cachedBaseToken = baseToken,
                    clock = clock,
                    random = random,
                )
                current = session
            } catch (e: Exception) {
                // Could not even open the socket: this is the common "wrong network" case.
                runCatching { channel?.close() }
                lastFailure = CourierFailure(CourierFailure.Reason.TRANSPORT, e.message, e)
                if (stopped) break
                val delay = backoff.delayFor(attempt)
                statusListener(CourierStatus.Reconnecting(null, attempt, lastFailure, delay))
                sleep(delay)
                continue
            }

            val handshake = session.handshake()
            when (handshake) {
                is CourierHandshake.Rejected -> {
                    session.close()
                    current = null
                    lastFailure = handshake.failure
                    if (stopped) break
                    // A refused certificate will not fix itself by reconnecting to another host, but Apple may have
                    // rebuilt the session; retry with a long delay rather than a tight loop.
                    val delay = backoff.delayFor(attempt)
                    statusListener(CourierStatus.Reconnecting(host, attempt, lastFailure, delay))
                    sleep(delay)
                    continue
                }

                is CourierHandshake.Ready -> {
                    attempt = 0
                    statusListener(
                        CourierStatus.Connected(host, handshake.baseToken, handshake.scopedToken, null),
                    )
                    // Messages that arrived before the scoped token are handled by the receive loop, in order.
                    val exit = session.run()
                    session.close()
                    current = null
                    when (exit) {
                        CourierExit.Closed -> break
                        is CourierExit.Failed -> lastFailure = exit.failure
                    }
                }
            }

            if (stopped) break
            val delay = backoff.delayFor(++attempt)
            statusListener(CourierStatus.Reconnecting(host, attempt, lastFailure ?: CourierFailure(CourierFailure.Reason.CLOSED, "disconnected"), delay))
            sleep(delay)
        }

        statusListener(CourierStatus.Stopped(lastFailure))
        return lastFailure
    }

    /** Diagnostics: last tokens without touching the identity store. */
    fun snapshot(): Pair<ByteArray?, ByteArray?> = baseToken to lastScopedToken
}

/**
 * Exponential backoff with jitter, capped at a minute. The first reconnect after a network blip should be fast; a
 * courier that keeps refusing must not cost us a wakeup per second.
 */
data class BackoffPolicy(
    val initialMillis: Long = 2_000,
    val maxMillis: Long = 60_000,
    val factor: Double = 2.0,
    val jitterFraction: Double = 0.2,
) {
    fun delayFor(attempt: Int): Long {
        val base = (initialMillis * Math.pow(factor, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
            .coerceIn(initialMillis, maxMillis)
        val jitter = (base * jitterFraction).toLong()
        return if (jitter <= 0) base else base - jitter + (Math.random() * (2 * jitter)).toLong()
    }
}

/** Convenience: a config with the bark topic pre-filled. */
fun courierConfigForBark(
    keepAliveIntervalMillis: Int = 120_000,
    topics: List<String> = listOf(Topics.BARK),
): CourierConfig = CourierConfig(topics = topics, keepAliveIntervalMillis = keepAliveIntervalMillis)

/** Keeps `CourierCredentials` construction in one place (certificate DER + key). */
fun courierCredentials(certificateDer: ByteArray, privateKey: PrivateKey): CourierCredentials =
    CourierCredentials(certificateDer, privateKey)
