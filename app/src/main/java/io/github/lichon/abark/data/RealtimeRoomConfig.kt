package io.github.lichon.abark.data

import java.net.URI

/** A joinable Supabase Realtime room: project URL, anon key and the room name senders use. */
data class RealtimeRoomConfig(
    val url: String,
    val key: String,
    val room: String,
) {
    /** The channel shape shared with the desktop client and the companion `use-supabase.ts` hook. */
    val channelId: String get() = "room:$room:messages"

    /** The host shown in status lines, without the scheme or path. */
    val host: String
        get() = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }

    /** Never let an accidental log line carry the anon key. */
    override fun toString(): String = "RealtimeRoomConfig(url=$url, room=$room, key=***)"
}
