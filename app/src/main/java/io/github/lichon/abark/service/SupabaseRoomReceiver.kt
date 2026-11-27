package io.github.lichon.abark.service

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.lichon.abark.bark.RoomMessage
import io.github.lichon.abark.data.RealtimeRoomConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Connection state of the optional Supabase Realtime room, displayed next to the courier state. */
sealed interface RoomStatus {
    data object Idle : RoomStatus
    data class Connecting(val host: String, val room: String, val attempt: Int = 1) : RoomStatus
    data class Connected(val host: String, val room: String) : RoomStatus
    data class Reconnecting(
        val host: String,
        val room: String,
        val attempt: Int,
        val detail: String?,
        val delayMillis: Long,
    ) : RoomStatus
    data class Stopped(val detail: String?) : RoomStatus
}

/**
 * Long-lived Supabase Realtime receiver, run on its own worker thread.
 *
 * The room shape matches the companion `use-supabase.ts` hook and the desktop client: each broadcast payload is a
 * `ChannelMessage` whose `content` is either the message text or a full Bark payload. [RoomMessage] converts it into
 * the same JSON the APNs courier delivers, so notifications and the inbox work unchanged.
 *
 * The SDK owns the WebSocket and its reconnect loop; this class only rebuilds the client if a session ends and retries
 * with a capped backoff. No test opens a socket: this is covered by the pure mapping tests in `:core:bark`.
 */
class SupabaseRoomReceiver(
    private val config: RealtimeRoomConfig,
    private val onMessage: (raw: String, messageId: String) -> Unit,
    private val onStatus: (RoomStatus) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var stopped = false

    @Volatile
    private var session: Job? = null

    @Volatile
    private var client: SupabaseClient? = null

    fun run() {
        var attempt = 0
        while (!stopped) {
            attempt++
            onStatus(RoomStatus.Connecting(config.host, config.room, attempt))

            var failure: Throwable? = null
            val job = scope.launch {
                try {
                    connectAndListen()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failure = e
                }
            }
            session = job
            runBlocking { job.join() }

            if (stopped) break
            val delay = retryDelayMillis(attempt)
            onStatus(RoomStatus.Reconnecting(config.host, config.room, attempt, failure?.message, delay))
            runCatching { Thread.sleep(delay) }
        }
        onStatus(RoomStatus.Stopped(null))
    }

    fun stop() {
        stopped = true
        runCatching { client?.realtime?.disconnect() }
        session?.cancel()
        scope.cancel()
    }

    private suspend fun connectAndListen() {
        val supabase = createSupabaseClient(config.url, config.key) {
            httpEngine = OkHttp.create()
            // The SDK logs the WebSocket URL (which carries the anon key) on connection errors.
            defaultLogLevel = LogLevel.NONE
            install(Realtime)
        }
        client = supabase

        val handlers = mutableListOf<Job>()
        try {
            val channel = supabase.channel(config.channelId)
            // Register every event name the companion hook and the desktop client accept before joining.
            for (event in BROADCAST_EVENTS) {
                handlers += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    channel.broadcastFlow<JsonObject>(event).collect { handle(it) }
                }
            }

            channel.subscribe(blockUntilSubscribed = true)
            if (stopped) return
            onStatus(RoomStatus.Connected(config.host, config.room))

            supabase.realtime.status.collect { status ->
                if (stopped) throw CancellationException("room receiver stopped")
                when (status) {
                    Realtime.Status.CONNECTED ->
                        onStatus(RoomStatus.Connected(config.host, config.room))
                    Realtime.Status.CONNECTING ->
                        onStatus(RoomStatus.Connecting(config.host, config.room))
                    Realtime.Status.DISCONNECTED ->
                        onStatus(RoomStatus.Reconnecting(config.host, config.room, 1, "connection lost", 0))
                }
            }
        } finally {
            handlers.forEach { it.cancel() }
            client = null
            withContext(NonCancellable) { runCatching { supabase.close() } }
        }
    }

    private fun handle(message: JsonObject) {
        if (stopped) return
        val raw = RoomMessage.toBarkPayload(message, config.room) ?: return
        onMessage(raw, RoomMessage.messageId(message))
    }

    private fun retryDelayMillis(attempt: Int): Long =
        (INITIAL_BACKOFF_MS shl (attempt - 1).coerceIn(0, MAX_BACKOFF_STEP)).coerceAtMost(MAX_BACKOFF_MS)

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_STEP = 5
        val BROADCAST_EVENTS = listOf("message", "event", "broadcast")
    }
}
