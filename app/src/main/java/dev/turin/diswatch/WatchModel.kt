package dev.turin.diswatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.turin.diswatch.data.*
import dev.turin.diswatch.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WatchModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DisWatchApp
    private val vault = SecureStore(app)
    @Volatile private var token: String? = null
    val api = DiscordApi(app.client) { token }
    val repository = Repository(api, vault)
    val images get() = app.images
    val authenticated = MutableStateFlow(false)
    val initialized = MutableStateFlow(false)
    val active = MutableStateFlow(false)
    val qr = MutableStateFlow<String?>(null)
    val loginStatus = MutableStateFlow("QR 로그인 준비")
    val connection = MutableStateFlow("오프라인")
    val error = MutableStateFlow<String?>(null)
    val guilds = MutableStateFlow<List<Guild>>(emptyList())
    val channels = MutableStateFlow<List<dev.turin.diswatch.data.Channel>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val busy = MutableStateFlow(false)
    val more = MutableStateFlow(true)
    val readBoundary = MutableStateFlow<String?>(null)
    var selectedGuild: Guild? = null; private set
    var selectedChannel: dev.turin.diswatch.data.Channel? = null; private set
    private var foregroundScope: CoroutineScope? = null
    private var qrJob: Job? = null
    private var channelJob: Job? = null
    private var loadJob: Job? = null
    private val gateway = Gateway(app.client, { token }, { type, data ->
        repository.event(type, data)
        if (type == "READY") selectedChannel?.let { channel -> runCatching { repository.refresh(channel.id) } }
    }, { connection.value = it })
    init {
        repository.attach(viewModelScope)
        viewModelScope.launch {
            token = withContext(Dispatchers.IO) { vault.read("token")?.toString(Charsets.UTF_8) }
            if (token != null) repository.restore()
            authenticated.value = token != null
            initialized.value = true
            if (active.value) resumeNetwork()
        }
    }
    fun foreground() {
        if (active.value) return
        active.value = true; repository.foreground = true
        foregroundScope = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
        if (initialized.value) resumeNetwork()
    }
    private fun resumeNetwork() {
        val scope = foregroundScope ?: return
        if (authenticated.value) {
            gateway.start(scope)
            run { repository.identify(); guilds.value = api.guilds() }
            selectedChannel?.let { refresh() }
        }
    }
    fun background() {
        active.value = false; repository.foreground = false
        gateway.stop(); qrJob?.cancel(); qrJob = null; qr.value = null
        foregroundScope?.cancel(); foregroundScope = null; busy.value = false
        app.client.dispatcher.cancelAll(); images.closeViewer()
        connection.value = "일시 정지"
        if (authenticated.value) viewModelScope.launch { runCatching { repository.persist() } }
    }
    fun login() {
        if (!active.value || qrJob?.isActive == true) return
        qrJob = foregroundScope?.launch {
            error.value = null
            try {
                loginStatus.value = "QR 생성 중…"
                val received = RemoteAuth(api).login({ qr.value = it }, { loginStatus.value = it })
                loginStatus.value = "Discord 계정 확인 중…"
                token = received
                try { repository.identify() } catch (e: Exception) { token = null; throw e }
                loginStatus.value = "로그인 정보를 안전하게 저장 중…"
                withContext(Dispatchers.IO) { vault.write("token", received.toByteArray()) }
                authenticated.value = true
                loginStatus.value = "로그인 완료"
                resumeNetwork()
            } catch (e: CancellationException) { throw e }
            catch (e: ApiFailure) {
                loginStatus.value = when {
                    loginStatus.value.startsWith("승인 완료") -> "승인 후 티켓 교환 거부 (HTTP ${e.status})"
                    loginStatus.value.startsWith("Discord 계정 확인") -> "인증 뒤 계정 확인 거부 (HTTP ${e.status})"
                    else -> "QR 로그인 요청 거부 (HTTP ${e.status})"
                }
            }
            catch (_: java.net.SocketTimeoutException) {
                loginStatus.value = when {
                    loginStatus.value.startsWith("휴대폰 QR 연결") -> "최종 승인 응답 시간 초과 · QR을 다시 생성하세요"
                    loginStatus.value.startsWith("승인 완료") -> "승인 뒤 티켓 교환 시간 초과"
                    loginStatus.value.startsWith("Discord 계정 확인") -> "인증 뒤 계정 확인 시간 초과"
                    else -> "로그인 서버 응답 시간 초과 · 다시 시도하세요"
                }
            }
            catch (_: java.io.IOException) {
                loginStatus.value = when {
                    loginStatus.value.startsWith("휴대폰 QR 연결") -> "최종 승인 대기 중 연결이 종료됨 · 다시 시도하세요"
                    loginStatus.value.startsWith("승인 완료") -> "승인 뒤 티켓 교환 중 네트워크 오류"
                    loginStatus.value.startsWith("티켓 수신") -> "인증 정보 해독 실패 · QR을 다시 생성하세요"
                    loginStatus.value.startsWith("Discord 계정 확인") -> "인증 뒤 계정 확인 중 네트워크 오류"
                    loginStatus.value.startsWith("로그인 정보를") -> "로그인 정보 저장 실패 · 다시 로그인하세요"
                    else -> "QR 로그인 연결 오류 · 다시 시도하세요"
                }
            }
            catch (e: Exception) {
                loginStatus.value = "로그인 처리 오류 (${e::class.simpleName ?: "예외"})"
            }
        }
    }
    fun selectGuild(guild: Guild) {
        selectedGuild = guild; selectedChannel = null; repository.activeChannel = null
        channels.value = emptyList()
        loadJob?.cancel()
        loadJob = run { val result = api.channels(guild.id); channels.value = result; repository.updateUnread(result) }
    }
    fun selectChannel(channel: dev.turin.diswatch.data.Channel) {
        selectedChannel = channel; messages.value = emptyList(); more.value = true; busy.value = false; readBoundary.value = null
        channelJob?.cancel(); loadJob?.cancel()
        channelJob = viewModelScope.launch {
            readBoundary.value = repository.readCursor(channel.id)
            val state = repository.open(channel.id)
            messages.value = state.value
            launch { state.collect { messages.value = it } }
            refresh()
        }
    }
    fun leaveChat() { selectedChannel = null; repository.activeChannel = null; channelJob?.cancel(); loadJob?.cancel(); messages.value = emptyList() }
    fun refresh(older: Boolean = false) {
        val channel = selectedChannel ?: return
        if (busy.value) return
        loadJob = run {
            busy.value = true
            try { more.value = repository.refresh(channel.id, if (older) messages.value.firstOrNull { !it.pending && !it.failed }?.id else null) }
            finally { busy.value = false }
        }
    }
    fun markRead(message: Message) { if (active.value && !message.pending && !message.failed) run { repository.markRead(message.channelId, message.id) } }
    fun send(text: String, reply: Message?) { selectedChannel?.let { channel -> run { repository.send(channel.id, text, reply) } } }
    fun retry(message: Message) { run { repository.send(message.channelId, message.content, null, message) } }
    fun edit(message: Message, text: String) { run { repository.edit(message, text) } }
    fun delete(message: Message) { run { repository.delete(message) } }
    fun react(message: Message, emoji: Emoji) { run { repository.react(message, emoji) } }
    fun run(block: suspend () -> Unit): Job? = foregroundScope?.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiFailure) { error.value = when(e.status) { 401 -> "인증 만료: 설정에서 다시 로그인하세요"; 403 -> "이 채널에 접근하거나 작업할 권한이 없습니다"; 429 -> "요청이 많습니다. 잠시 뒤 다시 시도하세요"; else -> "Discord 요청 실패 (${e.status})" } }
        catch (_: Exception) { error.value = "연결 실패. 인터넷을 확인하고 다시 시도하세요." }
    }
    fun clearImages() { images.clear() }
    fun logout() {
        val previousScope = foregroundScope
        background(); gateway.clearSession()
        viewModelScope.launch {
            previousScope?.coroutineContext?.get(Job)?.join()
            channelJob?.cancelAndJoin(); loadJob?.cancelAndJoin()
            repository.clear(); token = null; authenticated.value = false
            selectedChannel = null; selectedGuild = null; messages.value = emptyList(); guilds.value = emptyList(); channels.value = emptyList()
            images.clear(); error.value = null; foreground()
        }
    }
    override fun onCleared() { gateway.stop(); app.client.dispatcher.cancelAll() }
}
