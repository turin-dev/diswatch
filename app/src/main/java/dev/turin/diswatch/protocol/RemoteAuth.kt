package dev.turin.diswatch.protocol

import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

@Serializable private data class AuthEvent(val op: String, val heartbeat_interval: Long = 40000,
    val timeout_ms: Long = 120000, val encrypted_nonce: String? = null, val fingerprint: String? = null,
    val ticket: String? = null)

/** Ephemeral RSA pair never leaves this foreground authentication attempt. */
class RemoteAuth(private val api: DiscordApi) {
    suspend fun login(onQr: (String?) -> Unit, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun decrypt(encoded: String): ByteArray = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
            init(Cipher.DECRYPT_MODE, pair.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            doFinal(Base64.decode(encoded, Base64.DEFAULT))
        }
        val events = Channel<String>(8)
        val ws = api.client.newWebSocket(Request.Builder().url("wss://remote-auth-gateway.discord.gg/?v=2")
            .header("Origin", "https://discord.com").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 16384 || events.trySend(text).isFailure) { events.close(); webSocket.cancel() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { events.close(); webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close() }
        })
        var heartbeat: Job? = null
        var expiry: Job? = null
        var sessionFingerprint: String? = null
        try {
            withTimeout(180000) {
                for (text in events) {
                    val event = wireJson.decodeFromString<AuthEvent>(text)
                    when (event.op) {
                        "hello" -> {
                            ws.send(buildJsonObject { put("op", "init"); put("encoded_public_key", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)) }.toString())
                            heartbeat = launch { while (isActive) { delay(event.heartbeat_interval.coerceAtLeast(1000)); ws.send("{\"op\":\"heartbeat\"}") } }
                            expiry = launch { delay(event.timeout_ms.coerceIn(1000, 180000)); events.close() }
                        }
                        "nonce_proof" -> {
                            val nonce = Base64.encodeToString(decrypt(requireNotNull(event.encrypted_nonce)),
                                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                            ws.send(buildJsonObject { put("op", "nonce_proof"); put("nonce", nonce) }.toString())
                        }
                        "pending_remote_init" -> {
                            val fingerprint = requireNotNull(event.fingerprint)
                            val expected = Base64.encodeToString(
                                MessageDigest.getInstance("SHA-256").digest(pair.public.encoded),
                                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                            if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), fingerprint.toByteArray(Charsets.US_ASCII))) {
                                throw java.io.IOException("Remote auth fingerprint mismatch")
                            }
                            sessionFingerprint = fingerprint
                            onQr("https://discord.com/ra/$fingerprint")
                            onStatus("Discord 앱으로 스캔하고 승인하세요")
                        }
                        "pending_ticket" -> { onQr(null); onStatus("휴대폰 QR 연결 확인 · 최종 승인을 기다리는 중…") }
                        "pending_login" -> {
                            onQr(null)
                            onStatus("승인 완료 · Discord 티켓을 교환하는 중…")
                            val result = api.ticket(requireNotNull(event.ticket), requireNotNull(sessionFingerprint))
                            onStatus("티켓 수신 · 인증 정보를 해독하는 중…")
                            return@withTimeout decrypt(result.encrypted_token).toString(Charsets.UTF_8)
                        }
                        "cancel" -> throw java.io.IOException("로그인이 취소되었습니다")
                    }
                }
                throw java.io.IOException("QR이 만료되었습니다. 다시 시도하세요")
            }
        } finally { onQr(null); heartbeat?.cancel(); expiry?.cancel(); ws.cancel(); events.cancel() }
    }
}
