package io.github.lichon.abark.bark

import java.security.MessageDigest

/**
 * What the renderer needs, derived once from a [BarkPayload].
 *
 * Keeping this separate from the payload means the *decisions* (which channel, which importance, is this a delete, does
 * it need a network fetch) are testable on a plain JVM, while `:app` only maps fields onto Android objects.
 */
data class NotificationSpec(
    /** Stable id: 16 hex chars of SHA-256 over the message id (or the payload when the courier gave none). */
    val id: String,
    val title: String?,
    val subtitle: String?,
    val body: String?,
    val group: String?,
    val urgency: Urgency,
    val sound: String?,
    val badge: Int?,
    val volume: Double?,
    val call: Boolean,
    val url: String?,
    val copy: String?,
    val autoCopy: Boolean,
    val iconUrl: String?,
    val threadId: String?,
    val category: String?,
    val markdown: Boolean,
    val archive: Boolean,
    /** Non-null when this push asks us to remove an earlier notification. */
    val deleteTarget: String?,
    /** The payload was encrypted and cannot be displayed. */
    val encrypted: Boolean,
    /** True when there is nothing to show but a side effect (badge/copy/delete). */
    val isSilent: Boolean,
    val rawJson: String,
) {
    val isDelete: Boolean get() = deleteTarget != null

    /** Notification channels are per group; ungrouped pushes share one default channel. */
    val channelId: String get() = channelIdFor(group)

    companion object {
        const val DEFAULT_CHANNEL: String = "abark-default"

        fun channelIdFor(group: String?): String =
            if (group.isNullOrBlank()) DEFAULT_CHANNEL else "abark-group-${group.slug()}"

        /**
         * @param messageId the courier's 4-byte message id, hex-encoded. Used for dedupe and for
         *   `NotificationManager.notify`, so a repeated delivery replaces instead of duplicating.
         */
        fun from(payload: BarkPayload, messageId: String? = null): NotificationSpec {
            val deleteTarget = payload.delete?.let { value ->
                // `"1"`/`"true"` mean "remove this message"; anything else is the id to remove.
                if (value == "1" || value.equals("true", ignoreCase = true)) stableId(messageId, payload.rawJson) else value
            }
            val hasContent = !payload.title.isNullOrBlank() || !payload.body.isNullOrBlank() ||
                !payload.alertBody.isNullOrBlank()
            val encrypted = payload.hasCiphertext

            return NotificationSpec(
                id = stableId(messageId, payload.rawJson),
                title = payload.title,
                subtitle = payload.subtitle,
                body = payload.body,
                group = payload.group,
                urgency = Urgency.fromLevel(payload.level),
                sound = payload.sound,
                badge = payload.badge,
                volume = payload.volume,
                call = payload.isCall,
                url = payload.url,
                copy = payload.copy,
                autoCopy = payload.autoCopy,
                iconUrl = payload.iconUrl,
                threadId = payload.threadId ?: payload.group,
                category = payload.category,
                markdown = payload.isMarkdown,
                archive = payload.isArchive,
                deleteTarget = deleteTarget,
                encrypted = encrypted,
                isSilent = deleteTarget != null || (!hasContent && !encrypted),
                rawJson = payload.rawJson,
            )
        }

        /**
         * Deterministic 16-hex id. Same message delivered twice maps to the same id (dedupe, replace-in-place);
         * a payload without an id still gets a stable one.
         */
        fun stableId(messageId: String?, rawJson: String): String {
            val seed = messageId?.takeIf { it.isNotBlank() } ?: rawJson
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun String.slug(): String = lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
            .trim('-')
            .takeIf { it.isNotEmpty() } ?: "group"
    }
}
