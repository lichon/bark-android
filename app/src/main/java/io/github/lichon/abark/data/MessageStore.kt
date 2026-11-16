package io.github.lichon.abark.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One received push, kept in the in-app inbox. Bark has no server-side history (`PLAN.md` §1), so this is the only copy. */
@Serializable
data class StoredMessage(
    val id: String,
    val title: String? = null,
    val body: String? = null,
    val group: String? = null,
    val level: String? = null,
    val url: String? = null,
    val iconUrl: String? = null,
    val messageId: String = "",
    val receivedAt: Long = 0,
    val read: Boolean = false,
)

/**
 * Inbox backed by a JSON file instead of Room.
 *
 * The whole history is a bounded list of a few hundred small records; reading it into memory is faster than opening a
 * database and keeps the module dependency-free (no KSP, no schema migrations). Writes are atomic (temp + rename).
 */
class MessageStore(
    private val file: File,
    private val limit: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val lock = Any()
    private val _messages = MutableStateFlow(loadFromDisk())
    val messages: StateFlow<List<StoredMessage>> = _messages.asStateFlow()

    /** Newest first; a repeated id replaces the existing record (courier redelivery, or a re-registered token). */
    fun add(message: StoredMessage): List<StoredMessage> = synchronized(lock) {
        val current = _messages.value.filterNot { it.id == message.id }
        val updated = (listOf(message) + current).take(limit)
        _messages.value = updated
        persist(updated)
        updated
    }

    fun record(
        id: String,
        title: String?,
        body: String?,
        group: String?,
        level: String?,
        url: String?,
        iconUrl: String?,
        messageId: String,
    ): StoredMessage = add(
        StoredMessage(
            id = id,
            title = title,
            body = body,
            group = group,
            level = level,
            url = url,
            iconUrl = iconUrl,
            messageId = messageId,
            receivedAt = clock(),
        ),
    ).first { it.id == id }

    fun markRead(id: String) = update { message ->
        if (message.id == id) message.copy(read = true) else message
    }

    fun markAllRead() = update { it.copy(read = true) }

    fun delete(id: String) = synchronized(lock) {
        val updated = _messages.value.filterNot { it.id == id }
        _messages.value = updated
        persist(updated)
    }

    fun clear() = synchronized(lock) {
        _messages.value = emptyList()
        persist(emptyList())
    }

    fun unreadCount(): Int = _messages.value.count { !it.read }

    private fun update(transform: (StoredMessage) -> StoredMessage) = synchronized(lock) {
        val updated = _messages.value.map(transform)
        _messages.value = updated
        persist(updated)
    }

    private fun loadFromDisk(): List<StoredMessage> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer, text) }.getOrDefault(emptyList())
    }

    private fun persist(messages: List<StoredMessage>) {
        try {
            val directory = file.absoluteFile.parentFile ?: return
            if (!directory.exists()) directory.mkdirs()
            val temp = File.createTempFile("messages", ".tmp", directory)
            temp.writeText(json.encodeToString(ListSerializer, messages))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            temp.delete()
        } catch (e: IOException) {
            // History is a convenience: losing a write must never take the notification down with it.
        }
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(StoredMessage.serializer())
    }
}
