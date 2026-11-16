package io.github.lichon.abark.notify

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.lichon.abark.Graph

/**
 * Notification actions. Bark can ask for a copy without the app ever being opened, so the copy action must work from a
 * broadcast with no activity alive.
 */
class CopyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Bark", text))
                toast(context, "Copied")
            }

            ACTION_DISMISS -> {
                intent.getStringExtra(EXTRA_ID)?.let { Graph.notifier.cancel(it) }
            }
        }
    }

    private fun toast(context: Context, message: String) {
        if (!isMainThread()) {
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    companion object {
        const val ACTION_COPY: String = "io.github.lichon.abark.COPY"
        const val ACTION_DISMISS: String = "io.github.lichon.abark.DISMISS"
        const val EXTRA_TEXT: String = "text"
        const val EXTRA_ID: String = "id"
    }
}
