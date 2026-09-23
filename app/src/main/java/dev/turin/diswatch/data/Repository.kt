package dev.turin.diswatch.data

import dev.turin.diswatch.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable private data class CacheSnapshot(val account: Person?, val channels: Map<String, List<Message>>,
    val read: Map<String, String>)

class Repository(private val api: DiscordApi, private val vault: SecureStore) {
    val me = MutableStateFlow<Person?>(null)
    val unread = MutableStateFlow<Set<String>>(emptySet())
    private val lock = Mutex()
    private val diskLock = Mutex()
    private var cleared = false
    private val buffers = LinkedHashMap<String, MutableStateFlow<List<Message>>>(4, 0.75f, true)
    private val recent = LinkedHashMap<String, List<Message>>(4, 0.75f, true)
    private val read = linkedMapOf<String, String>()
    private val revisions = mutableMapOf<String, Long>()
    private val mutationLocks = mutableMapOf<String, Mutex>()
    private var save: Job? = null
    var activeChannel: String? = null
    var foreground = false
    private var scope: CoroutineScope? = null
    fun attach(scope: CoroutineScope) { this.scope = scope }
    suspend fun restore() = withContext(Dispatchers.IO) {
        val snapshot = vault.read("cache")?.let { runCatching { wireJson.decodeFromString<CacheSnapshot>(it.toString(Charsets.UTF_8)) }.getOrNull() }
        lock.withLock { snapshot?.let { me.value = it.account; recent.putAll(it.channels.entries.take(4).associate { e -> e.key to e.value.takeLast(50) }); read.putAll(it.read) } }
    }
    suspend fun identify() { val person = api.me(); lock.withLock { cleared = false; me.value = person }; persistLater() }
    suspend fun readCursor(channel: String): String? = lock.withLock { read[channel] }
    suspend fun open(channel: String): StateFlow<List<Message>> = lock.withLock {
        activeChannel = channel
        buffers.getOrPut(channel) { MutableStateFlow(recent[channel].orEmpty()) }.also {
            while (buffers.size > 4) { val evict = buffers.keys.first { id -> id != channel }; buffers.remove(evict); revisions.remove(evict); mutationLocks.remove(evict) }
        }
    }
    suspend fun refresh(channel: String, before: String? = null): Boolean {
        // Do not overwrite a newer gateway/mutation result with an older in-flight REST snapshot.
        val version = lock.withLock { revisions[channel] ?: 0L }
        val page = api.messages(channel, before).map { it.model() }
        lock.withLock {
            val buffer = buffers[channel] ?: return@withLock
            if (version != (revisions[channel] ?: 0L)) return@withLock
            buffer.value = if (before == null) Messages.merge(buffer.value.filter { it.pending || it.failed }, page)
                else (page + buffer.value).distinctBy { it.id }.sortedBy { it.id.toULongOrNull() ?: ULong.MAX_VALUE }.take(50)
            if (before == null) { recent[channel] = buffer.value.filterNot { it.pending || it.failed }; trimRecent() }
        }
        persistLater()
        return page.size == 25
    }
    suspend fun markRead(channel: String, messageId: String) {
        lock.withLock {
            if ((messageId.toULongOrNull() ?: 0uL) > (read[channel]?.toULongOrNull() ?: 0uL)) read[channel] = messageId
            while (read.size > 500) read.remove(read.keys.first())
            unread.value = unread.value - channel
        }
        persistLater()
    }
    suspend fun updateUnread(channels: List<Channel>) = lock.withLock {
        unread.value = (unread.value + channel).takeLastBounded(500)s.filter { c ->
            val last = c.last_message_id?.toULongOrNull() ?: 0uL
            // First visit establishes a local baseline, not a claim of Discord-wide unread sync.
            val seen = read[c.id]?.toULongOrNull()
            if (seen == null && c.last_message_id != null) read[c.id] = c.last_message_id
            seen != null && last > seen
        }.map { it.id }
        while (read.size > 500) read.remove(read.keys.first())
        unread.value = unread.value.takeLastBounded(500)
        persistLater()
    }
    suspend fun event(type: String, data: WireMessage) {
        if (type == "READY") { data.user?.let { me.value = it }; return }
        if (type == "RESUMED") return
        val channel = data.channel_id
        val changed = lock.withLock {
            if (type == "MESSAGE_CREATE" && data.author?.id != me.value?.id) unread.value = (unread.value + channel).takeLastBounded(500)
            val buffer = buffers[channel]
            val old = buffer?.value ?: recent[channel] ?: return@withLock false
            val id = data.message_id ?: data.id
            fun reduce(old: List<Message>): List<Message> = when (type) {
                "MESSAGE_CREATE" -> Messages.merge(old, listOf(data.model()))
                "MESSAGE_UPDATE" -> old.map { if (it.id == id) data.model(it) else it }
                "MESSAGE_DELETE" -> old.filterNot { it.id == id }
                "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE" -> old.map {
                    if (it.id == id && data.emoji != null) Messages.reaction(it, data.emoji, type.endsWith("ADD"), data.user_id == me.value?.id) else it
                }
                else -> old
            }
            buffer?.value = reduce(old)
            recent[channel] = reduce(recent[channel].orEmpty()).filterNot { it.pending || it.failed }.takeLast(50)
            revisions[channel] = (revisions[channel] ?: 0) + 1
            trimRecent()
            true
        }
        if (changed) persistLater()
    }
    private fun Set<String>.takeLastBounded(limit: Int) = toList().takeLast(limit).toSet()
    private suspend fun change(channel: String, transform: (List<Message>) -> List<Message>) = lock.withLock {
        buffers[channel]?.let { buffer ->
            buffer.value = transform(buffer.value)
            recent[channel] = transform(recent[channel].orEmpty()).filterNot { it.pending || it.failed }.takeLast(50)
            revisions[channel] = (revisions[channel] ?: 0L) + 1
            trimRecent()
        }
        persistLater()
    }
    suspend fun send(channel: String, text: String, reply: Message?, retry: Message? = null) {
        require(text.isNotBlank() && text.length <= 2000)
        val nonce = retry?.nonce ?: java.util.UUID.randomUUID().toString().replace("-", "").take(25)
        val local = retry?.copy(pending = true, failed = false) ?: Message("local-$nonce", channel,
            me.value ?: error("로그인이 필요합니다"), text, nonce = nonce, pending = true,
            reply = reply?.let { Reply(it.id, it.author.label, it.content.take(240)) })
        change(channel) { Messages.merge(it.filterNot { m -> m.id == local.id }, listOf(local)) }
        try {
            val sent = api.send(channel, text, nonce, reply?.id ?: retry?.reply?.id).model()
            change(channel) { Messages.merge(it.filterNot { m -> m.id == local.id }, listOf(sent)) }
        } catch (e: Exception) {
            withContext(NonCancellable) { change(channel) { it.map { m -> if (m.id == local.id) m.copy(pending = false, failed = true) else m } } }
            throw e
        }
    }
    private suspend fun mutate(message: Message, transform: (Message) -> Message?, request: suspend () -> Message?) {
        val mutex = lock.withLock { mutationLocks.getOrPut(message.channelId) { Mutex() } }
        mutex.withLock {
            val actual = lock.withLock { buffers[message.channelId]?.value?.firstOrNull { it.id == message.id } } ?: return
            val optimistic = transform(actual)
            change(message.channelId) { it.mapNotNull { m -> if (m.id == actual.id) optimistic else m } }
            try {
                val confirmed = request()
                if (confirmed != null) change(message.channelId) { list -> list.map { if (it.id == confirmed.id) confirmed else it } }
            } catch (e: Exception) {
                // Restore only this mutation, preserving unrelated gateway updates.
                withContext(NonCancellable) {
                    change(message.channelId) { list ->
                        if (optimistic == null && list.none { it.id == actual.id }) Messages.merge(list, listOf(actual))
                        else list.map { if (it.id == actual.id && it == optimistic) actual else it }
                    }
                }
                if (e !is CancellationException) runCatching { refresh(message.channelId) }
                throw e
            }
        }
    }
    suspend fun edit(message: Message, content: String) {
        require(message.author.id == me.value?.id && content.isNotBlank() && content.length <= 2000)
        mutate(message, { it.copy(content = content, edited = true) }) { api.edit(message.channelId, message.id, content).model() }
    }
    suspend fun delete(message: Message) {
        require(message.author.id == me.value?.id)
        mutate(message, { null }) { api.delete(message.channelId, message.id); null }
    }
    suspend fun react(message: Message, emoji: Emoji) {
        var add = false
        mutate(message, { m -> add = m.reactions.none { it.emoji == emoji && it.me }; Messages.reaction(m, emoji, add, true) }) {
            api.react(message.channelId, message.id, emoji, add); null
        }
    }
    private fun trimRecent() { while (recent.size > 4) recent.remove(recent.keys.first()) }
    @Synchronized private fun persistLater() {
        save?.cancel()
        save = scope?.launch(Dispatchers.IO) { delay(400); persist() }
    }
    suspend fun persist() = withContext(Dispatchers.IO) {
        diskLock.withLock {
            val snapshot = lock.withLock { if (cleared) null else CacheSnapshot(me.value, recent.toMap(), read.toMap()) }
            if (snapshot != null) vault.write("cache", wireJson.encodeToString(snapshot).toByteArray())
        }
    }
    suspend fun clear() { save?.cancelAndJoin(); lock.withLock { cleared = true; buffers.clear(); recent.clear(); read.clear(); revisions.clear(); mutationLocks.clear(); me.value = null; unread.value = emptySet() }; withContext(Dispatchers.IO) { diskLock.withLock { vault.clear() } } }
}
