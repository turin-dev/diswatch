package dev.turin.diswatch

import dev.turin.diswatch.data.*
import dev.turin.diswatch.protocol.*
import org.junit.Assert.*
import org.junit.Test

class MessageReducerTest {
    private fun message(id: String, content: String = "hello") = Message(id, "c", Person("u", "투린"), content)
    @Test fun mergeRetainsNewestFiftyAndIdentity() {
        val old = (1..50).map { message(it.toString()) }
        val merged = Messages.merge(old, listOf(message("51")))
        assertEquals(50, merged.size)
        assertEquals("2", merged.first().id)
        assertSame(old[1], merged.first())
    }
    @Test fun gatewayEchoReplacesPendingNonce() {
        val pending = message("local-x").copy(nonce = "x", pending = true)
        val sent = message("10").copy(nonce = "x")
        assertEquals(listOf(sent), Messages.merge(listOf(pending), listOf(sent)))
    }
    @Test fun lateEchoReplacesFailedNonce() {
        val failed = message("local-x").copy(nonce = "x", failed = true)
        val sent = message("10").copy(nonce = "x")
        assertEquals(listOf(sent), Messages.merge(listOf(failed), listOf(sent)))
    }
    @Test fun repeatedOwnReactionEchoIsIdempotent() {
        val emoji = Emoji(name = "👍")
        val optimistic = Messages.reaction(message("1"), emoji, true, true)
        val echo = Messages.reaction(optimistic, emoji, true, true)
        assertEquals(1, echo.reactions.single().count)
        assertEquals(emptyList<Reaction>(), Messages.reaction(echo, emoji, false, true).reactions)
    }
    @Test fun otherReactionPreservesOwnSelection() {
        val emoji = Emoji(name = "👍")
        val mine = Messages.reaction(message("1"), emoji, true, true)
        val other = Messages.reaction(mine, emoji, true, false)
        assertEquals(2, other.reactions.single().count)
        assertTrue(other.reactions.single().me)
    }
    @Test fun partialUpdateDoesNotEraseAuthorOrAttachments() {
        val old = message("1").copy(photos = listOf(Photo("a", "https://cdn.discordapp.com/a", width = 300, height = 300)))
        val patch = wireJson.decodeFromString<WireMessage>("""{"id":"1","channel_id":"c","content":"edited","unknown":{"large":true}}""")
        val result = patch.model(old)
        assertEquals("edited", result.content)
        assertEquals(old.author, result.author)
        assertEquals(old.photos, result.photos)
    }
    @Test fun explicitEmptyAttachmentsClearPhotos() {
        val old = message("1").copy(photos = listOf(Photo("a", "https://cdn.discordapp.com/a", width = 3, height = 3)))
        assertTrue(wireJson.decodeFromString<WireMessage>("""{"id":"1","attachments":[]}""").model(old).photos.isEmpty())
    }
    @Test fun duplicateRestAndGatewayDeliveryHasOneMessage() {
        val event = message("1")
        assertEquals(1, Messages.merge(listOf(event), listOf(event)).size)
    }
}
