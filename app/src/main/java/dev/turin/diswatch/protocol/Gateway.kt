package dev.turin.diswatch.protocol

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.StringReader
import kotlin.random.Random

internal data class Frame(val op: Int, val sequence: Long?, val event: String?, val data: JsonElement)
/** Skip guild/member/presence blobs in READY without building a large JSON tree. */
internal object GatewayCodec {
    private val fields = setOf("id", "channel_id", "author", "content", "timestamp", "edited_timestamp",
        "attachments", "reactions", "referenced_message", "nonce", "heartbeat_interval", "session_id",
        "resume_gateway_url", "user", "user_id", "message_id", "emoji", "name", "username", "global_name",
        "url", "proxy_url", "filename", "content_type", "width", "height", "count", "me")
    fun parse(text: String): Frame {
        JsonReader(StringReader(text)).use { reader ->
            var op = -1; var sequence: Long? = null; var event: String? = null; var data: JsonElement = JsonNull
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "op" -> op = reader.nextInt()
                "s" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else sequence = reader.nextLong()
                "t" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else event = reader.nextString()
                "d" -> data = read(reader, 0)
                else -> reader.skipValue()
            }
            reader.endObject()
            return Frame(op, sequence, event, data)
        }
    }
    private fun read(r: JsonReader, depth: Int): JsonElement {
        if (depth > 6) { r.skipValue(); return JsonNull }
        return when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> buildJsonObject {
                r.beginObject()
                while (r.hasNext()) { val name = r.nextName(); if (name in fields) put(name, read(r, depth + 1)) else r.skipValue() }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> buildJsonArray {
                r.beginArray(); var count = 0
                while (r.hasNext()) { if (count++ < 100) add(read(r, depth + 1)) else r.skipValue() }; r.endArray()
            }
            JsonToken.NULL -> { r.nextNull(); JsonNull }
            JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
            JsonToken.NUMBER -> JsonPrimitive(r.nextString().toLong())
            else -> JsonPrimitive(r.nextString())
        }
    }
}

class Gateway(private val client: OkHttpClient, private val token: () -> String?,
    private val dispatch: suspend (String, WireMessage) -> Unit, private val status: (String) -> Unit) {
    private var job: Job? = null
    private var socket: WebSocket? = null
    private var session: String? = null
    private var resumeUrl: String? = null
    private var sequence: Long? = null
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true || token() == null) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                try { connect(); failures = 0 }
                catch (e: CancellationException) { throw e }
                catch (_: FatalSession) { status("인증이 만료되었습니다. 설정에서 다시 로그인하세요."); return@launch }
                catch (_: Exception) { status("연결 복구 중…"); failures++ }
                delay((1000L shl failures.coerceAtMost(5)) + Random.nextLong(0, 1000))
            }
        }
    }
    fun stop() { job?.cancel(); job = null; socket?.cancel(); socket = null }
    fun clearSession() { stop(); session = null; sequence = null; resumeUrl = null }
    private class FatalSession : Exception()
    private suspend fun connect() = coroutineScope {
        status("연결 중…")
        val events = Channel<String>(2)
        val closed = CompletableDeferred<Int>()
        val base = resumeUrl?.takeIf { it.startsWith("wss://") &&
            runCatching { java.net.URI(it).host.endsWith(".discord.gg") }.getOrDefault(false) } ?: "wss://gateway.discord.gg"
        val ws = client.newWebSocket(Request.Builder().url("${base.trimEnd('/')}?v=10&encoding=json").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 2_000_000 || events.trySend(text).isFailure) { closed.complete(1006); webSocket.cancel(); events.close() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(1006); events.close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); closed.complete(code); events.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(code); events.close() }
        })
        socket = ws
        var heartbeat: Job? = null
        var acknowledged = true
        fun beat() { ws.send(buildJsonObject { put("op", 1); put("d", sequence?.let(::JsonPrimitive) ?: JsonNull) }.toString()); acknowledged = false }
        try {
            for (text in events) {
                val frame = GatewayCodec.parse(text)
                frame.sequence?.let { sequence = it }
                when (frame.op) {
                    10 -> {
                        val interval = wireJson.decodeFromJsonElement<WireMessage>(frame.data).heartbeat_interval.coerceAtLeast(1000)
                        heartbeat?.cancel()
                        heartbeat = launch {
                            delay(Random.nextLong(interval))
                            while (isActive) {
                                if (!acknowledged) { ws.cancel(); events.close(); break }
                                beat(); delay(interval)
                            }
                        }
                        ws.send(buildJsonObject {
                            put("op", if (session == null) 2 else 6)
                            putJsonObject("d") {
                                put("token", token())
                                if (session != null) { put("session_id", session); put("seq", sequence) }
                                else { put("compress", false); put("large_threshold", 50)
                                    putJsonObject("properties") { put("os", "Android"); put("browser", "DisWatch"); put("device", "DisWatch") }
                                    putJsonObject("presence") { put("status", "online"); put("since", JsonNull); put("afk", false); putJsonArray("activities") {} }
                                }
                            }
                        }.toString())
                    }
                    11 -> acknowledged = true
                    1 -> beat()
                    7 -> break
                    9 -> { if ((frame.data as? JsonPrimitive)?.booleanOrNull != true) { session = null; sequence = null; resumeUrl = null }; delay(Random.nextLong(1000, 5000)); break }
                    0 -> {
                        val event = frame.event ?: continue
                        if (event == "RESUMED") { status("연결됨"); dispatch(event, WireMessage()); continue }
                        if (event !in setOf("READY", "MESSAGE_CREATE", "MESSAGE_UPDATE", "MESSAGE_DELETE", "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE")) continue
                        val data = wireJson.decodeFromJsonElement<WireMessage>(frame.data)
                        if (event == "READY") { session = data.session_id; resumeUrl = data.resume_gateway_url; status("연결됨") }
                        dispatch(event, data)
                    }
                }
            }
            val code = if (closed.isCompleted) closed.await() else 1000
            if (code in setOf(4004, 4010, 4011, 4012, 4013, 4014)) throw FatalSession()
            if (code in setOf(4007, 4009)) { session = null; sequence = null; resumeUrl = null }
            if (code == 1006) throw java.io.IOException("Gateway disconnected")
        } finally { heartbeat?.cancel(); ws.cancel(); events.cancel(); if (socket === ws) socket = null }
    }
}
