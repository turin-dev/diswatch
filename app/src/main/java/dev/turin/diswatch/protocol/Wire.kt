package dev.turin.diswatch.protocol

import dev.turin.diswatch.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** Unknown Discord fields are skipped by the streaming decoder, never retained as a JSON DOM. */
@Serializable data class WireMessage(
    val id: String = "", val channel_id: String = "", val author: Person? = null,
    val content: String? = null, val timestamp: String? = null, val edited_timestamp: String? = null,
    val attachments: List<Photo>? = null, val reactions: List<Reaction>? = null,
    val referenced_message: WireReply? = null, val nonce: String? = null,
    // Gateway control and dispatch fields, sharing a compact typed payload.
    val heartbeat_interval: Long = 41250, val session_id: String? = null,
    val resume_gateway_url: String? = null, val user: Person? = null,
    val user_id: String? = null, val message_id: String? = null, val emoji: Emoji? = null,
) {
    fun model(previous: Message? = null): Message = Message(
        id, channel_id.ifEmpty { previous?.channelId.orEmpty() }, author ?: previous?.author ?: Person("", "알 수 없음"),
        content ?: previous?.content.orEmpty(), timestamp ?: previous?.timestamp.orEmpty(),
        edited_timestamp != null || previous?.edited == true,
        attachments?.filter { it.width != null && it.height != null && it.content_type?.startsWith("image/") != false }
            ?: previous?.photos.orEmpty(), reactions ?: previous?.reactions.orEmpty(),
        referenced_message?.let { Reply(it.id, it.author?.label.orEmpty(), it.content.orEmpty().take(240)) } ?: previous?.reply,
        nonce ?: previous?.nonce,
    )
}
@Serializable data class WireReply(val id: String, val author: Person? = null, val content: String? = null)
@Serializable data class SendBody(val content: String, val nonce: String, val enforce_nonce: Boolean = true,
    val message_reference: Reference? = null, val allowed_mentions: Mentions = Mentions())
@Serializable data class Reference(val message_id: String, val channel_id: String, val fail_if_not_exists: Boolean = false)
@Serializable data class Mentions(val parse: List<String> = emptyList(), val replied_user: Boolean = false)
@Serializable data class EditBody(val content: String, val allowed_mentions: Mentions = Mentions())
@Serializable data class RateLimit(val retry_after: Double = 1.0, val global: Boolean = false)
@Serializable data class TicketBody(val ticket: String)
@Serializable data class TokenReply(val encrypted_token: String)
