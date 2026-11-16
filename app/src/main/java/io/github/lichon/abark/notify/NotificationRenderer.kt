package io.github.lichon.abark.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.lichon.abark.R
import io.github.lichon.abark.bark.NotificationSpec
import io.github.lichon.abark.bark.Urgency
import io.github.lichon.abark.ui.MainActivity

/**
 * Maps a [NotificationSpec] onto Android notifications.
 *
 * All Bark semantics live in `:core:bark`; this class only does the platform part: one channel per `group`, importance
 * from `level`, a tap target (URL or in-app inbox), and the three actions Bark can ask for (copy, open, dismiss).
 */
class NotificationRenderer(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** @return false when posting was refused (notification permission revoked). */
    @SuppressLint("MissingPermission")
    fun show(spec: NotificationSpec): Boolean {
        if (spec.isDelete) {
            spec.deleteTarget?.let(::cancel)
            return true
        }
        val channelId = ensureChannel(spec)
        val notification = builder(spec, channelId).build()
        return try {
            manager.notify(spec.id, NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun builder(spec: NotificationSpec, channelId: String): NotificationCompat.Builder {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(spec.title ?: spec.body?.take(60) ?: "Notification")
            .setContentText(spec.body ?: spec.subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body ?: spec.subtitle ?: ""))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(spec))
            .setCategory(if (spec.call) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setPriority(priorityFor(spec.urgency))

        when (spec.urgency) {
            Urgency.PASSIVE -> notification.setSilent(true)
            Urgency.CRITICAL -> notification
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
            else -> notification
        }

        spec.copy?.let { text ->
            notification.addAction(0, context.getString(R.string.action_copy), copyIntent(text))
        }
        spec.url?.let { url ->
            notification.addAction(0, context.getString(R.string.action_open), openIntent(url))
        }
        notification.addAction(0, context.getString(R.string.action_dismiss), dismissIntent(spec.id))
        return notification
    }

    fun cancel(id: String) {
        runCatching { manager.cancel(id, NOTIFICATION_ID) }
        runCatching { manager.cancel(id.hashCode()) }
    }

    fun cancelAll() {
        runCatching { manager.cancelAll() }
    }

    /** A payload we could not parse is still a push: show its raw body instead of dropping it. */
    @SuppressLint("MissingPermission")
    fun showRaw(rawJson: String, id: String) {
        val channelId = ensureChannelFor(NotificationSpec.DEFAULT_CHANNEL, context.getString(R.string.default_channel_name), Urgency.ACTIVE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Unparsed push")
            .setContentText(rawJson.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(rawJson))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id.hashCode(),
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        runCatching { manager.notify(id, NOTIFICATION_ID, notification) }
    }

    private fun contentIntent(spec: NotificationSpec): PendingIntent {
        val intent = if (spec.url != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(spec.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_MESSAGE_ID, spec.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        return PendingIntent.getActivity(
            context,
            spec.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun copyIntent(text: String): PendingIntent {
        val intent = Intent(context, CopyReceiver::class.java).apply {
            action = CopyReceiver.ACTION_COPY
            putExtra(CopyReceiver.EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            text.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openIntent(url: String): PendingIntent = PendingIntent.getActivity(
        context,
        url.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dismissIntent(id: String): PendingIntent {
        val intent = Intent(context, CopyReceiver::class.java).apply {
            action = CopyReceiver.ACTION_DISMISS
            putExtra(CopyReceiver.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** One channel per group, created on first use; importance follows the push level. */
    private fun ensureChannel(spec: NotificationSpec): String {
        val name = spec.group ?: context.getString(R.string.default_channel_name)
        return ensureChannelFor(spec.channelId, name, spec.urgency)
    }

    private fun ensureChannelFor(id: String, name: String, urgency: Urgency): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val system = context.getSystemService(NotificationManager::class.java) ?: return id
        val existing = system.getNotificationChannel(id)
        val importance = importanceFor(urgency)
        if (existing == null || existing.importance != importance) {
            val channel = NotificationChannel(id, name, importance).apply {
                description = "Bark pushes"
                enableVibration(urgency != Urgency.PASSIVE)
                setShowBadge(true)
                if (urgency == Urgency.CRITICAL) {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableLights(true)
                }
            }
            runCatching { system.createNotificationChannel(channel) }
        }
        return id
    }

    private fun importanceFor(urgency: Urgency): Int = when (urgency) {
        Urgency.CRITICAL, Urgency.TIME_SENSITIVE -> NotificationManager.IMPORTANCE_HIGH
        Urgency.ACTIVE -> NotificationManager.IMPORTANCE_DEFAULT
        Urgency.PASSIVE -> NotificationManager.IMPORTANCE_LOW
    }

    private fun priorityFor(urgency: Urgency): Int = when (urgency) {
        Urgency.CRITICAL -> NotificationCompat.PRIORITY_MAX
        Urgency.TIME_SENSITIVE -> NotificationCompat.PRIORITY_HIGH
        Urgency.ACTIVE -> NotificationCompat.PRIORITY_DEFAULT
        Urgency.PASSIVE -> NotificationCompat.PRIORITY_LOW
    }

    companion object {
        /** One id per notification *tag*; the tag carries the stable message id. */
        const val NOTIFICATION_ID: Int = 1

        const val STATUS_CHANNEL: String = "abark-status"
    }
}
