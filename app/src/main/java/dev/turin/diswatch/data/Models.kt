package dev.turin.diswatch.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable @Serializable data class Guild(val id: String, val name: String, val icon: String? = null)
@Immutable @Serializable data class Channel(val id: String, val name: String = "채널", val type: Int = 0,
    val last_message_id: String? = null, val position: Int = 0)
@Immutable @Serializable data class Person(val id: String, val username: String = "", val global_name: String? = null) {
    val label: String get() = global_name ?: username
}
@Immutable @Serializable data class Photo(val id: String, val url: String, val proxy_url: String = url,
    val filename: String = "사진", val content_type: String? = null, val width: Int? = null, val height: Int? = null)
@Immutable @Serializable data class Emoji(val id: String? = null, val name: String? = null) {
    val route: String get() = if (id == null) name.orEmpty() else "${name.orEmpty()}:$id"
}
@Immutable @Serializable data class Reaction(val emoji: Emoji, val count: Int = 0, val me: Boolean = false)
@Immutable @Serializable data class Reply(val id: String, val author: String, val content: String)
@Immutable @Serializable data class Message(
    val id: String, val channelId: String, val author: Person, val content: String,
    val timestamp: String = "", val edited: Boolean = false, val photos: List<Photo> = emptyList(),
    val reactions: List<Reaction> = emptyList(), val reply: Reply? = null, val nonce: String? = null,
    val pending: Boolean = false, val failed: Boolean = false,
)

/** Bounded pure reducer; untouched messages preserve object identity for Compose skipping. */
object Messages {
    fun merge(old: List<Message>, incoming: List<Message>, limit: Int = 50): List<Message> {
        val replacementNonces = incoming.mapNotNull { it.nonce }.toSet()
        val byId = old.filterNot { (it.pending || it.failed) && it.nonce in replacementNonces }.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedWith(compareBy<Message> { it.pending || it.failed }
            .thenBy { it.id.toULongOrNull() ?: ULong.MAX_VALUE }).takeLast(limit)
    }
    fun reaction(message: Message, emoji: Emoji, add: Boolean, own: Boolean): Message {
        val previous = message.reactions.firstOrNull { it.emoji == emoji } ?: Reaction(emoji)
        if (own && previous.me == add) return message
        val next = previous.copy(count = (previous.count + if (add) 1 else -1).coerceAtLeast(0),
            me = if (own) add else previous.me)
        return message.copy(reactions = message.reactions.filterNot { it.emoji == emoji } +
            if (next.count > 0) listOf(next) else emptyList())
    }
}
