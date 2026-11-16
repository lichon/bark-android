package io.github.lichon.abark.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lichon.abark.Graph
import java.util.concurrent.TimeUnit

/**
 * Restarts the courier after a reboot or an app update.
 *
 * A boot receiver is not enough on its own: OEM task killers also stop foreground services, so the service schedules an
 * inexact alarm for itself when it goes down and this receiver re-arms the watchdog if it cannot start immediately.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        if (!Graph.settings.autoStart) return
        if (Graph.identityStore.load() == null) return
        CourierService.start(context)
        RestartReceiver.schedule(context)
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Graph.init(context)
        if (!Graph.settings.autoStart) return
        if (Graph.identityStore.load() == null) return
        CourierService.start(context)
    }

    companion object {
        /** 15 minutes: the inexact-alarm floor on API 34 and a reasonable battery cost for a broken connection. */
        private val INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(15)
        private const val REQUEST_RESTART = 42

        fun schedule(context: Context) {
            val system = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_RESTART,
                Intent(context, RestartReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val triggerAt = System.currentTimeMillis() + INTERVAL_MILLIS
            runCatching {
                system.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }.onFailure {
                // Some OEM ROMs reject alarms for foreground services; the boot path still works.
            }
        }

        fun cancel(context: Context) {
            val system = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_RESTART,
                Intent(context, RestartReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { system.cancel(pending) }
        }
    }
}
