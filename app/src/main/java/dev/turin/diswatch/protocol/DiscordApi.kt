package dev.turin.diswatch.protocol

import dev.turin.diswatch.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(
    val status: Int,
    val discordCode: Long? = null,
    val captchaRequired: Boolean = false
) : IOException("Discord 요청 실패 ($status)")
class DiscordApi(val client: OkHttpClient, private val token: () -> String?) {
    private val gate = Mutex()
    private var nextRequestAt = 0L
    suspend fun guilds(): List<Guild> {
        val result = mutableListOf<Guild>()
        var after = ""
        do {
            val page: List<Guild> = get("/users/@me/guilds?limit=100" + if (after.isNotEmpty()) "&after=$after" else "")
            result += page
            after = page.lastOrNull()?.id ?: break
        } while (page.size == 100 && result.size < 1000)
        return result
    }
    suspend fun me(): Person = get("/users/@me")
    suspend fun channels(guild: String): List<Channel> = get<List<Channel>>("/guilds/$guild/channels")
        .filter { it.type == 0 || it.type == 5 }.sortedBy { it.position }
    suspend fun messages(channel: String, before: String? = null): List<WireMessage> =
        get("/channels/$channel/messages?limit=25" + (before?.let { "&before=$it" } ?: ""))
    suspend fun send(channel: String, content: String, nonce: String, reply: String?): WireMessage =
        request("POST", "/channels/$channel/messages", wireJson.encodeToString(SendBody(content, nonce,
            message_reference = reply?.let { Reference(it, channel) })))
    suspend fun edit(channel: String, id: String, content: String): WireMessage =
        request("PATCH", "/channels/$channel/messages/$id", wireJson.encodeToString(EditBody(content)))
    suspend fun delete(channel: String, id: String) { raw("DELETE", "/channels/$channel/messages/$id").close() }
    suspend fun react(channel: String, id: String, emoji: Emoji, add: Boolean) {
        val encoded = java.net.URLEncoder.encode(emoji.route, "UTF-8").replace("+", "%20")
        raw(if (add) "PUT" else "DELETE", "/channels/$channel/messages/$id/reactions/$encoded/@me").close()
    }
    suspend fun ticket(ticket: String, fingerprint: String): TokenReply = request(
        "POST", "/users/@me/remote-auth/login", wireJson.encodeToString(TicketBody(ticket)),
        authenticate = false, apiVersion = 9, fingerprint = fingerprint)
    private suspend inline fun <reified T> get(path: String): T = request("GET", path)
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> request(method: String, path: String, body: String? = null,
        authenticate: Boolean = true, apiVersion: Int = 10, fingerprint: String? = null): T = withContext(Dispatchers.IO) {
        raw(method, path, body, authenticate, apiVersion, fingerprint).use { response ->
            wireJson.decodeFromStream<T>(requireNotNull(response.body).byteStream())
        }
    }
    /** Serialize REST calls and respect server Retry-After; never automatically retry ambiguous POST failures. */
    private suspend fun raw(method: String, path: String, body: String? = null, authenticate: Boolean = true,
        apiVersion: Int = 10, fingerprint: String? = null): Response = withContext(Dispatchers.IO) { gate.withLock {
        repeat(4) {
            delay((nextRequestAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            val request = Request.Builder().url("https://discord.com/api/v$apiVersion$path")
                .header("User-Agent", "DisWatch/0.1 (Wear OS; unofficial)")
            fingerprint?.let { request.header("X-Fingerprint", it) }
            if (authenticate) request.header("Authorization", token() ?: throw ApiFailure(401))
            request.method(method, if (method in listOf("POST", "PUT", "PATCH"))
                (body ?: "").toRequestBody("application/json".toMediaType()) else null)
            val response = client.newCall(request.build()).await()
            if (response.code == 429) {
                val wait = response.use { r ->
                    runCatching { wireJson.decodeFromString<RateLimit>(r.body?.string().orEmpty()).retry_after }.getOrDefault(2.0)
                }
                nextRequestAt = android.os.SystemClock.elapsedRealtime() + (wait * 1000).toLong().coerceIn(250, 120000)
            } else {
                if (response.header("X-RateLimit-Remaining") == "0") nextRequestAt =
                    android.os.SystemClock.elapsedRealtime() + ((response.header("X-RateLimit-Reset-After")?.toDoubleOrNull() ?: 1.0) * 1000).toLong()
                if (!response.isSuccessful) {
                    val status = response.code
                    val errorDetails = runCatching {
                        response.peekBody(8192).use { body ->
                            val json = wireJson.parseToJsonElement(body.string()).jsonObject
                            val code = json["code"]?.jsonPrimitive?.content?.toLongOrNull()
                            val captcha = listOf("captcha_key", "captcha_sitekey", "captcha_rqtoken")
                                .any(json::containsKey)
                            code to captcha
                        }
                    }.getOrNull()
                    response.close()
                    throw ApiFailure(status, errorDetails?.first, errorDetails?.second ?: false)
                }
                return@withLock response
            }
        }
        throw ApiFailure(429)
    } }
    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
    }
}
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
