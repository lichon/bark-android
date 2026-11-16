package io.github.lichon.abark

import android.app.Application
import io.github.lichon.abark.data.AppSettings
import io.github.lichon.abark.data.MessageStore
import io.github.lichon.abark.data.PrefsIdentityStore
import io.github.lichon.abark.notify.NotificationRenderer
import io.github.lichon.abark.notify.NotificationSpeaker
import io.github.lichon.abark.apns.transport.CourierStatus
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual dependency wiring (PLAN.md §5: no Hilt). The service, the UI and the boot receiver all go through [Graph], so
 * there is exactly one identity store, one message history and one status flow per process.
 */
object Graph {

    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun requireContext(): android.content.Context =
        appContext ?: error("Graph.init() was not called")

    val settings: AppSettings by lazy { AppSettings(requireContext()) }

    val identityStore: PrefsIdentityStore by lazy { PrefsIdentityStore(requireContext()) }

    val messages: MessageStore by lazy { MessageStore(File(requireContext().filesDir, "messages.json")) }

    val notifier: NotificationRenderer by lazy { NotificationRenderer(requireContext()) }

    /** Optional text-to-speech for arriving pushes; owns a lazily created TTS engine. */
    val speaker: NotificationSpeaker by lazy { NotificationSpeaker(requireContext()) }

    /** Current courier state, rendered by the service's notification and by the UI. */
    val status: MutableStateFlow<CourierStatus> = MutableStateFlow(CourierStatus.Idle)

    /** Result of the last registration attempt, surfaced in the UI. */
    val registration: MutableStateFlow<String?> = MutableStateFlow(null)
}

class AbarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
