package io.github.lichon.abark.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import android.os.Build
import io.github.lichon.abark.Graph
import io.github.lichon.abark.R
import io.github.lichon.abark.apns.IncomingMessage
import io.github.lichon.abark.apns.Packet
import io.github.lichon.abark.apns.Topics
import io.github.lichon.abark.apns.identity.DeviceIdentityRecord
import io.github.lichon.abark.apns.toHex
import io.github.lichon.abark.apns.transport.CourierConfig
import io.github.lichon.abark.apns.transport.CourierFailure
import io.github.lichon.abark.apns.transport.CourierHandshake
import io.github.lichon.abark.apns.transport.CourierListener
import io.github.lichon.abark.apns.transport.CourierLoop
import io.github.lichon.abark.apns.transport.CourierStatus
import io.github.lichon.abark.apns.transport.TlsCourierChannelFactory
import io.github.lichon.abark.bark.BarkApi
import io.github.lichon.abark.bark.BarkPayload
import io.github.lichon.abark.bark.BarkResult
import io.github.lichon.abark.bark.BarkServer
import io.github.lichon.abark.bark.NotificationSpec
import io.github.lichon.abark.data.RealtimeRoomConfig
import io.github.lichon.abark.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The always-on half of the app: a foreground service that owns the courier socket and the optional Supabase room.
 *
 * `foregroundServiceType="remoteMessaging"` keeps the process alive while a connection idles; both transports run on
 * their own plain threads because [CourierLoop] and [SupabaseRoomReceiver] are blocking by design. Either transport can
 * run alone, so stopping is just closing the loops, and the service stays up while at least one still has work.
 */
class CourierService : Service() {

    private val courierRunning = AtomicBoolean(false)
    private val roomRunning = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val reconnectRequested = AtomicBoolean(false)
    private val roomGeneration = AtomicInteger(0)

    @Volatile
    private var loop: CourierLoop? = null

    @Volatile
    private var room: SupabaseRoomReceiver? = null

    private var worker: Thread? = null
    private var roomWorker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        ensureStatusChannel()
        startForegroundCompat(statusNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested.set(true)
                reconnectRequested.set(false)
                stopRoom()
                loop?.stop()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH -> {
                // Room settings changed: reconnect just the room, leaving a running courier untouched.
                stopRequested.set(false)
                stopRoom()
                startRoomIfConfigured()
                if (!courierRunning.get() && !roomRunning.get()) stopSelf()
                return START_STICKY
            }

            ACTION_RECONNECT -> {
                stopRequested.set(false)
                reconnectCourier()
                if (!courierRunning.get() && !roomRunning.get()) stopSelf()
                return START_STICKY
            }
        }

        stopRequested.set(false)
        reconnectRequested.set(false)
        startCourierIfConfigured()
        startRoomIfConfigured()
        if (!courierRunning.get() && !roomRunning.get()) {
            updateForeground()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        loop?.stop()
        stopRoom()
        courierRunning.set(false)
        Graph.speaker.release()
        Graph.status.value = CourierStatus.Idle
        Graph.roomStatus.value = RoomStatus.Idle
        super.onDestroy()
    }

    private fun startCourierIfConfigured() {
        if (courierRunning.get()) return
        if (Graph.identityStore.load() == null) return
        if (!courierRunning.compareAndSet(false, true)) return
        worker = Thread({ runCourier() }, "abark-courier").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    /** Close the courier socket and reconnect with the current server/keepalive without destroying the service. */
    private fun reconnectCourier() {
        if (courierRunning.get()) {
            reconnectRequested.set(true)
            loop?.stop()
        } else {
            startCourierIfConfigured()
        }
    }

    private fun startRoomIfConfigured() {
        if (roomRunning.get()) return
        val config = Graph.settings.realtimeConfig()
        if (config == null) {
            Graph.roomStatus.value = RoomStatus.Idle
            return
        }
        if (!roomRunning.compareAndSet(false, true)) return
        val generation = roomGeneration.incrementAndGet()
        Graph.roomStatus.value = RoomStatus.Connecting(config.host, config.room)
        roomWorker = Thread({ runRoom(config, generation) }, "abark-room").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun stopRoom() {
        roomGeneration.incrementAndGet()
        room?.stop()
        room = null
        roomRunning.set(false)
        Graph.roomStatus.value = RoomStatus.Idle
    }

    private fun runRoom(config: RealtimeRoomConfig, generation: Int) {
        val receiver = SupabaseRoomReceiver(
            config = config,
            onMessage = { raw, messageId ->
                if (roomGeneration.get() == generation) deliverPayload(raw, messageId)
            },
            onStatus = { status ->
                if (roomGeneration.get() == generation) {
                    Graph.roomStatus.value = status
                    updateForeground()
                }
            },
        )
        room = receiver
        try {
            receiver.run()
        } catch (e: Throwable) {
            if (roomGeneration.get() == generation) {
                Graph.roomStatus.value = RoomStatus.Stopped(e.message)
            }
        } finally {
            if (roomGeneration.get() == generation) {
                room = null
                roomRunning.set(false)
                updateForeground()
                finishIfIdle()
            }
        }
    }

    private fun finishIfIdle() {
        if (courierRunning.get() || roomRunning.get()) return
        updateForeground()
        if (!stopRequested.get()) {
            val settings = Graph.settings
            val restartable = Graph.identityStore.load() != null || settings.realtimeConfig() != null
            if (settings.autoStart && restartable) RestartReceiver.schedule(this)
        }
        stopSelf()
    }

    private fun runCourier() {
        try {
            val record = Graph.identityStore.load()
            if (record == null) {
                Graph.registration.value = "No device identity yet — import one from the CLI."
                Graph.status.value = CourierStatus.Stopped(
                    CourierFailure(CourierFailure.Reason.ACTIVATION_REJECTED, "missing identity"),
                )
                return
            }
            var identity: DeviceIdentityRecord = record
            val settings = Graph.settings
            val api = BarkApi()

            val listener = object : CourierListener {
                override fun onBaseToken(token: ByteArray) {
                    identity = identity.withTokens(baseToken = token, scopedToken = null)
                    Graph.identityStore.save(identity)
                }

                override fun onScopedToken(token: ByteArray, topicHash: ByteArray) {
                    identity = identity.withTokens(baseToken = null, scopedToken = token)
                    Graph.identityStore.save(identity)

                    // Register even without a device key: the server mints one and returns it, which is how this
                    // install gets its address without the user typing anything.
                    val serverUrl = settings.serverUrl
                    when (val result = api.register(serverUrl, token.toHex(), settings.deviceKey)) {
                        is BarkResult.Ok -> {
                            settings.lastRegisteredToken = token.toHex()
                            result.deviceKey?.let { settings.deviceKey = it }
                            val host = BarkServer.normalize(serverUrl)
                            Graph.registration.value = result.deviceKey
                                ?.let { "Registered with $host as $it" }
                                ?: "Registered with $host"
                        }

                        is BarkResult.Refused ->
                            Graph.registration.value = "Registration refused: ${result.code} ${result.message ?: ""}"

                        is BarkResult.Unreachable ->
                            Graph.registration.value = "Registration failed: ${result.detail ?: "unreachable"}"
                    }
                }

                override fun onMessage(message: IncomingMessage) {
                    deliver(message)
                }

                override fun onUnknownPacket(packet: Packet) = Unit
            }

            val config = CourierConfig(
                topics = listOf(Topics.BARK),
                keepAliveIntervalMillis = settings.keepAliveSeconds * 1000,
            )

            val courier = CourierLoop(
                credentials = identity.credentials(),
                config = config,
                channelFactory = TlsCourierChannelFactory(identity.credentials()),
                listener = listener,
                statusListener = { status ->
                    Graph.status.value = status
                    updateForeground()
                },
            )
            loop = courier
            courier.run()
        } catch (e: Throwable) {
            Graph.status.value = CourierStatus.Stopped(
                CourierFailure(CourierFailure.Reason.TRANSPORT, e.message, e),
            )
        } finally {
            loop = null
            courierRunning.set(false)
            // The worker owns the socket, not the service lifetime: reflect the final state, arm the watchdog and let
            // the system reclaim the process. The alarm starts us again if the connection dropped on its own.
            updateForeground()
            if (reconnectRequested.compareAndSet(true, false)) {
                startCourierIfConfigured()
            } else {
                finishIfIdle()
            }
        }
    }

    /** Turn one courier message into a notification and an inbox row. */
    private fun deliver(message: IncomingMessage) {
        deliverPayload(String(message.payload, Charsets.UTF_8), message.messageId.toHex())
    }

    /** Shared delivery path for the APNs courier and Supabase Realtime room. */
    private fun deliverPayload(raw: String, sourceId: String) {
        val payload = runCatching { BarkPayload.parse(raw) }.getOrNull()
        if (payload == null) {
            Graph.notifier.showRaw(raw, NotificationSpec.stableId(sourceId, raw))
            return
        }

        val spec = NotificationSpec.from(payload, sourceId)
        if (spec.isDelete) {
            spec.deleteTarget?.let {
                Graph.notifier.cancel(it)
                Graph.messages.delete(it)
            }
            return
        }

        if (spec.autoCopy && spec.copy != null) {
            runCatching {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Bark", spec.copy))
            }
        }

        val shown = Graph.notifier.show(spec)
        // Speak only what the user can also see: a blocked notification must not turn into audio.
        if (shown && Graph.settings.ttsEnabled) Graph.speaker.speak(spec)
        if (spec.archive) {
            Graph.messages.record(
                id = spec.id,
                title = spec.title,
                body = spec.body,
                group = spec.group,
                level = payload.level,
                url = spec.url,
                iconUrl = spec.iconUrl,
                messageId = sourceId,
            )
        }
        if (!shown) Graph.registration.value = "Notifications are blocked — grant the permission"
    }

    private fun updateForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                STATUS_NOTIFICATION_ID,
                statusNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                } else {
                    0
                },
            )
        }
    }

    private fun statusNotification(): Notification {
        val (title, text) = statusText()

        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, CourierService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun statusText(): Pair<String, String> {
        val courier = Graph.status.value
        val room = Graph.roomStatus.value
        return when {
            room is RoomStatus.Connected && courier is CourierStatus.Connected ->
                "Connected" to "Courier and room ${room.room}"
            room is RoomStatus.Connected ->
                "Room connected" to "${room.room} · ${room.host}"
            room is RoomStatus.Connecting && courier !is CourierStatus.Connected ->
                "Connecting to room…" to "${room.room} · ${room.host}"
            room is RoomStatus.Reconnecting && courier !is CourierStatus.Connected ->
                "Room reconnecting…" to (room.detail ?: "attempt ${room.attempt}")
            courier is CourierStatus.Connected -> "Connected" to "Waiting for pushes"
            courier is CourierStatus.Connecting -> "Connecting…" to courier.host
            courier is CourierStatus.Reconnecting ->
                "Reconnecting…" to (courier.failure.detail ?: courier.failure.reason.name.lowercase())
            courier is CourierStatus.Stopped -> "Disconnected" to (courier.failure?.detail ?: "not running")
            room is RoomStatus.Stopped -> "Disconnected" to (room.detail ?: "room stopped")
            else -> "Starting…" to "opening connections"
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, STATUS_NOTIFICATION_ID, notification, type)
        }.onFailure {
            // Android 12+ can refuse a FGS start from the background; the watchdog retries later.
            stopSelf()
        }
    }

    private fun ensureStatusChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(STATUS_CHANNEL) == null) {
            system.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL,
                    getString(R.string.status_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "Connection state of the APNs courier"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        const val ACTION_START: String = "io.github.lichon.abark.action.START"
        const val ACTION_STOP: String = "io.github.lichon.abark.action.STOP"
        const val ACTION_REFRESH: String = "io.github.lichon.abark.action.REFRESH"
        const val ACTION_RECONNECT: String = "io.github.lichon.abark.action.RECONNECT"
        const val STATUS_CHANNEL: String = "abark-status"
        const val STATUS_NOTIFICATION_ID: Int = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        fun start(context: Context) {
            val intent = Intent(context, CourierService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Reconnect the optional room after its settings changed, without touching a running courier. */
        fun refreshRoom(context: Context) {
            val intent = Intent(context, CourierService::class.java).setAction(ACTION_REFRESH)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** Reconnect the courier (new server/keepalive) without destroying the service. */
        fun reconnect(context: Context) {
            val intent = Intent(context, CourierService::class.java).setAction(ACTION_RECONNECT)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CourierService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
