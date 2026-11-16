package io.github.lichon.abark.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

sealed interface InstallEvent {
    /** The system install dialog is on screen. */
    data object ConfirmationRequired : InstallEvent
    data object Completed : InstallEvent
    data class Failed(val message: String) : InstallEvent
}

/**
 * Installs a [ApkSignatureVerifier.VerifiedApk] through a `PackageInstaller` session.
 *
 * Why a session and not `ACTION_VIEW` on a `FileProvider` URI:
 *  - no content URI is exposed to other apps;
 *  - the user still confirms via the standard system dialog (never silent);
 *  - we get a real result callback and can distinguish "signature mismatch / downgrade" from "user cancelled".
 */
class UpdateInstaller(private val context: Context) {

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Deep link to the per-source "Install unknown apps" toggle; the app cannot grant this itself. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun install(apk: ApkSignatureVerifier.VerifiedApk) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
        }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.file.inputStream().use { input ->
                    session.openWrite("app.apk", 0, apk.file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val result = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                // The system fills in the result extras, so this PendingIntent must be mutable on S+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(
                    PendingIntent.getBroadcast(context, sessionId, result, flags).intentSender
                )
            }
        } catch (e: IOException) {
            publish(InstallEvent.Failed(e.message ?: "session-error"))
            runCatching { installer.abandonSession(sessionId) }
        }
    }

    companion object {
        private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<InstallEvent> get() = _events

        /** Receivers live in another process entry point, so they cannot touch the private flow directly. */
        internal fun publish(event: InstallEvent) {
            _events.tryEmit(event)
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
                UpdateInstaller.publish(InstallEvent.ConfirmationRequired)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                UpdateInstaller.publish(InstallEvent.Completed)
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "status-$status"
                UpdateInstaller.publish(InstallEvent.Failed(message))
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "io.github.lichon.abark.update.extra.SESSION_ID"
    }
}
