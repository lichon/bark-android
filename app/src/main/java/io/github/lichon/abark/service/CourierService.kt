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
import io.github.lichon.abark.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The always-on half of the app: a foreground service that owns the courier socket.
 *
 * `foregroundServiceType="remoteMessaging"` keeps the process alive while the socket idles; the loop itself runs on a
 * plain thread because [CourierLoop] is blocking by design, and the loop is the only thing that ever touches the
 * socket. Stopping is therefore just `loop.stop()`, which closes the channel and unblocks the read.
 */
class CourierService : Service() {

    private val running = AtomicBoolean(false)

    @Volatile
    private var loop: CourierLoop? = null

    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        ensureStatusChannel()
        startForegroundCompat(statusNotification(CourierStatus.Idle))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            loop?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        startCourier()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        loop?.stop()
        running.set(false)
        Graph.status.value = CourierStatus.Idle
        super.onDestroy()
    }

    private fun startCourier() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runCourier() }, "abark-courier").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    private fun runCourier() {
        var restartWanted = true
        try {
            val record = Graph.identityStore.load()
            if (record == null) {
                Graph.registration.value = "No device identity yet — import one from the CLI."
                Graph.status.value = CourierStatus.Stopped(
                    CourierFailure(CourierFailure.Reason.ACTIVATION_REJECTED, "missing identity"),
                )
                restartWanted = false
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
                    updateForeground(status)
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
            running.set(false)
            // The worker owns the socket, not the service lifetime: reflect the final state, arm the watchdog and let
            // the system reclaim the process. The alarm starts us again if the connection dropped on its own.
            updateForeground(Graph.status.value)
            if (restartWanted) RestartReceiver.schedule(this)
            stopSelf()
        }
    }

    /** Turn one courier message into a notification and an inbox row. */
    private fun deliver(message: IncomingMessage) {
        val messageId = message.messageId.toHex()
        val raw = String(message.payload, Charsets.UTF_8)
        val payload = runCatching { BarkPayload.parse(raw) }.getOrNull()
        if (payload == null) {
            Graph.notifier.showRaw(raw, NotificationSpec.stableId(messageId, raw))
            return
        }

        val spec = NotificationSpec.from(payload, messageId)
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
        if (spec.archive) {
            Graph.messages.record(
                id = spec.id,
                title = spec.title,
                body = spec.body,
                group = spec.group,
                level = payload.level,
                url = spec.url,
                iconUrl = spec.iconUrl,
                messageId = messageId,
            )
        }
        if (!shown) Graph.registration.value = "Notifications are blocked — grant the permission"
    }

    private fun updateForeground(status: CourierStatus) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                STATUS_NOTIFICATION_ID,
                statusNotification(status),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                } else {
                    0
                },
            )
        }
    }

    private fun statusNotification(status: CourierStatus): Notification {
        val (title, text) = when (status) {
            is CourierStatus.Connected -> "Connected" to "Waiting for pushes"
            is CourierStatus.Connecting -> "Connecting…" to status.host
            is CourierStatus.Reconnecting ->
                "Reconnecting…" to (status.failure.detail ?: status.failure.reason.name.lowercase())
            is CourierStatus.Stopped -> "Disconnected" to (status.failure?.detail ?: "not running")
            CourierStatus.Idle -> "Starting…" to "opening the courier connection"
        }

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
        const val STATUS_CHANNEL: String = "abark-status"
        const val STATUS_NOTIFICATION_ID: Int = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2

        fun start(context: Context) {
            val intent = Intent(context, CourierService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CourierService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
