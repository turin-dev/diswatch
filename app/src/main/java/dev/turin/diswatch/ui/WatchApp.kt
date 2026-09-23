@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.turin.diswatch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.turin.diswatch.WatchModel
import dev.turin.diswatch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Accent = Color(0xFFA6ACFF)
private val Muted = Color(0xFFB4B4C0)
private enum class Screen { Guilds, Channels, Chat, Settings }

@Composable fun WatchApp(model: WatchModel) {
    val authenticated by model.authenticated.collectAsStateWithLifecycle()
    val initialized by model.initialized.collectAsStateWithLifecycle()
    val active by model.active.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Guilds) }
    var action by remember { mutableStateOf<Message?>(null) }
    var photo by remember { mutableStateOf<Photo?>(null) }
    var compose by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var reply by remember { mutableStateOf<Message?>(null) }
    var reacting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val back: () -> Unit = {
        when {
            photo != null -> { photo = null; model.images.closeViewer() }
            compose -> { compose = false; editing = null; reply = null }
            action != null -> { action = null; reacting = false; deleting = false }
            screen == Screen.Chat -> { model.leaveChat(); screen = Screen.Channels }
            screen != Screen.Guilds -> screen = Screen.Guilds
        }
    }
    BackHandler(photo != null || compose || action != null || screen != Screen.Guilds) { back() }
    LaunchedEffect(authenticated) { if (!authenticated) { screen = Screen.Guilds; photo = null; compose = false; action = null; reply = null; editing = null } }
    MaterialTheme(colors = Colors(primary = Accent, background = Color.Black, surface = Color(0xFF191A22))) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                !initialized -> WearList("DisWatch") { item { Skeleton() } }
                !authenticated -> Login(model, active)
                photo != null -> PhotoViewer(photo!!, model.images, active) { back() }
                compose -> Editor(editing?.content.orEmpty(), editing != null, reply) { text ->
                    if (editing != null) model.edit(editing!!, text) else model.send(text, reply)
                    compose = false; editing = null; reply = null
                }
                action != null -> {
                    val selected = action!!
                    val me by model.repository.me.collectAsStateWithLifecycle()
                    WearList(if (deleting) "메시지 삭제?" else if (reacting) "반응" else selected.author.label) {
                        if (deleting) {
                            item { Label("삭제하면 되돌릴 수 없습니다") }
                            item { Action("삭제", danger = true) { model.delete(selected); action = null; deleting = false } }
                        } else if (reacting) {
                            items((selected.reactions.map { it.emoji } + listOf("👍", "❤️", "😂", "🎉", "👀", "✅").map { Emoji(name = it) }).distinct(), key = { it.route }) { emoji ->
                                val existing = selected.reactions.firstOrNull { it.emoji == emoji }
                                Action("${emoji.name ?: "이모지"} ${if (existing?.me == true) "제거" else "추가"}") {
                                    model.react(selected, emoji); action = null; reacting = false
                                }
                            }
                        } else {
                            if (selected.failed) item { Action("전송 다시 시도") { model.retry(selected); action = null } }
                            if (!selected.pending && !selected.failed) {
                                item { Action("반응") { reacting = true } }
                                item { Action("답장") { reply = selected; compose = true; action = null } }
                                if (selected.author.id == me?.id) {
                                    item { Action("수정") { editing = selected; compose = true; action = null } }
                                    item { Action("삭제", danger = true) { deleting = true } }
                                }
                            }
                            item { Action("복사") {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DisWatch", selected.content))
                                action = null
                            } }
                        }
                        item { Action("뒤로") { action = null; reacting = false; deleting = false } }
                    }
                }
                screen == Screen.Settings -> WearList("설정") {
                    item { Label("DisWatch 0.1 α\n워치 직접 연결") }
                    item { Label("텍스트 4채널 × 50개\n이미지 캐시 최대 6 MiB\n백그라운드 연결 없음") }
                    item { Action("이미지 캐시 비우기") { model.clearImages() } }
                    item { Action("로그아웃", danger = true) { model.logout() } }
                    item { Action("뒤로") { back() } }
                }
                screen == Screen.Guilds -> {
                    val guilds by model.guilds.collectAsStateWithLifecycle()
                    WearList("DisWatch") {
                        item { Connection(model) }
                        if (guilds.isEmpty()) item { Skeleton() }
                        items(guilds, key = { it.id }) { guild -> Action(guild.name) { model.selectGuild(guild); screen = Screen.Channels } }
                        item { Action("새로고침") { model.run { model.guilds.value = model.api.guilds() } } }
                        item { Action("설정") { screen = Screen.Settings } }
                    }
                }
                screen == Screen.Channels -> {
                    val channels by model.channels.collectAsStateWithLifecycle()
                    val unread by model.repository.unread.collectAsStateWithLifecycle()
                    WearList(model.selectedGuild?.name ?: "채널") {
                        if (channels.isEmpty()) item { Label("채널을 불러오는 중…\n표시되는 채널은 텍스트 채널입니다") }
                        items(channels, key = { it.id }) { channel -> Action("${if (channel.id in unread) "● " else ""}# ${channel.name}") {
                            model.selectChannel(channel); screen = Screen.Chat
                        } }
                        item { Action("새로고침") { model.selectedGuild?.let(model::selectGuild) } }
                        item { Action("서버 목록") { screen = Screen.Guilds } }
                    }
                }
                else -> Chat(model, active, onAction = { action = it }, onPhoto = { photo = it }, onCompose = { compose = true })
            }
            if (error != null && authenticated) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp, vertical = 22.dp)
                .background(Color(0xFF552B35), RoundedCornerShape(12.dp)).clickable { model.error.value = null }.padding(10.dp)) {
                Text(error!! + " · 닫기", fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable private fun WearList(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LazyColumn(state = state, horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 34.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().onRotaryScrollEvent { event -> scope.launch { state.scroll { scrollBy(event.verticalScrollPixels) } }; true }.focusRequester(focus).focusable()) {
        item(key = "header") { Text(title, color = Accent, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) }
        content()
    }
}
@Composable private fun Action(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Chip(onClick = onClick, label = { Text(label, maxLines = 3, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ChipDefaults.chipColors(backgroundColor = if (danger) Color(0xFF552B35) else Color(0xFF232532)))
}
@Composable private fun Label(text: String) { Text(text, fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(4.dp)) }
@Composable private fun Skeleton() { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(3) { Box(Modifier.fillMaxWidth(if (it == 1) .72f else 1f).height(28.dp).background(Color(0xFF20212A), RoundedCornerShape(8.dp))) }
} }
@Composable private fun Connection(model: WatchModel) { val state by model.connection.collectAsStateWithLifecycle(); Label(state) }

@Composable private fun Login(model: WatchModel, active: Boolean) {
    val payload by model.qr.collectAsStateWithLifecycle()
    val status by model.loginStatus.collectAsStateWithLifecycle()
    val bitmap by produceState<Bitmap?>(null, payload, active) {
        value = null
        val content = payload
        if (content != null && active) value = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300, mapOf(EncodeHintType.MARGIN to 4))
            Bitmap.createBitmap(300, 300, Bitmap.Config.RGB_565).apply {
                val pixels = IntArray(90000) { index -> if (matrix[index % 300, index / 300]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                setPixels(pixels, 0, 300, 0, 0, 300, 300)
            }
        }
    }
    WearList("DisWatch 로그인") {
        bitmap?.let { qr -> item { Image(qr.asImageBitmap(), "로그인 QR 코드", Modifier.size(144.dp)) } }
        item { Label(status) }
        item { Action("QR 생성 / 다시 시도") { model.login() } }
        item { Label("최초 로그인은 Discord 모바일 앱의 스캔·승인이 필요합니다. 이후 워치만으로 사용할 수 있습니다.") }
    }
}

@Composable private fun Chat(model: WatchModel, active: Boolean, onAction: (Message) -> Unit, onPhoto: (Photo) -> Unit, onCompose: () -> Unit) {
    val messages by model.messages.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val more by model.more.collectAsStateWithLifecycle()
    val boundary by model.readBoundary.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    // Reverse layout keeps the newest edge anchored without scrolling users away from older messages.
    LaunchedEffect(model.selectedChannel?.id) { focus.requestFocus() }
    val latest = rememberUpdatedState(messages)
    LaunchedEffect(state, active) {
        if (active) snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }.distinctUntilChanged().collect { visible ->
            latest.value.lastOrNull()?.takeIf { it.id in visible }?.let(model::markRead)
        }
    }
    val currentBusy = rememberUpdatedState(busy)
    LaunchedEffect(state, more) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.key to latest.value.firstOrNull()?.id }.distinctUntilChanged().collect { (key, _) ->
            if (key == "older" && more && !currentBusy.value && latest.value.isNotEmpty()) model.refresh(older = true)
        }
    }
    LazyColumn(state = state, reverseLayout = true, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()
            .onRotaryScrollEvent { event -> scope.launch { state.scroll { scrollBy(event.verticalScrollPixels) } }; true }.focusRequester(focus).focusable()) {
        item(key = "compose") { Action("메시지 보내기", onClick = onCompose) }
        items(messages.asReversed(), key = { it.id }, contentType = { "message" }) { message ->
            MessageRow(message, model.images, active, onAction, onPhoto)
            if (message.id == boundary) Label("↓ 새 메시지")
        }
        if (messages.isEmpty()) item(key = "empty") { if (busy) Skeleton() else Label("메시지가 없습니다") }
        item(key = "older") { Action(if (busy) "불러오는 중…" else if (more) "이전 메시지" else "이전 메시지 없음") { if (more) model.refresh(older = true) } }
        item(key = "refresh") { Action("최신 메시지") { model.refresh(); scope.launch { state.scrollToItem(0) } } }
        item(key = "channel") { Label("# ${model.selectedChannel?.name.orEmpty()}"); Connection(model) }
    }
}

@Composable private fun MessageRow(message: Message, images: Images, active: Boolean, onAction: (Message) -> Unit, onPhoto: (Photo) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF15161C), RoundedCornerShape(12.dp))
        .combinedClickable(onClick = { if (message.failed) onAction(message) }, onLongClick = { onAction(message) }).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message.author.label, color = Accent, fontSize = 11.sp)
        message.reply?.let { Text("↳ ${it.author}: ${it.content}", color = Muted, fontSize = 10.sp, maxLines = 3) }
        if (message.content.isNotEmpty()) Text(message.content, fontSize = 14.sp, lineHeight = 19.sp)
        message.photos.forEach { photo ->
            Thumbnail(photo, images, active, onPhoto)
        }
        if (message.reactions.isNotEmpty()) Text(message.reactions.joinToString("  ") { "${it.emoji.name ?: "이모지"} ${it.count}${if (it.me) "·" else ""}" }, fontSize = 12.sp, color = Accent)
        if (message.pending || message.failed || message.edited) Text(when { message.failed -> "전송 실패 · 눌러서 재시도"; message.pending -> "보내는 중…"; else -> "수정됨" }, color = Muted, fontSize = 10.sp)
    }
}
@Composable private fun Thumbnail(photo: Photo, images: Images, active: Boolean, onPhoto: (Photo) -> Unit) {
    val bitmap by imageState(photo.proxy_url, Images.Kind.Thumbnail, images, active)
    Box(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF292B38), RoundedCornerShape(8.dp)).clickable { onPhoto(photo) }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), photo.filename, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("사진 열기", fontSize = 12.sp)
    }
}
@Composable private fun imageState(url: String, kind: Images.Kind, images: Images, active: Boolean): State<Bitmap?> = produceState<Bitmap?>(null, url, kind, active) {
    value = null
    if (active) try { value = images.load(url, kind) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { value = null }
}
@Composable private fun PhotoViewer(photo: Photo, images: Images, active: Boolean, back: () -> Unit) {
    val bitmap by imageState(photo.proxy_url, Images.Kind.Full, images, active)
    DisposableEffect(Unit) { onDispose { images.closeViewer() } }
    WearList("사진") {
        item { if (bitmap != null) Image(bitmap!!.asImageBitmap(), photo.filename, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit) else Label("사진 불러오는 중…") }
        item { Label(photo.filename) }
        item { Action("닫기", onClick = back) }
    }
}
@Composable private fun Editor(initial: String, editing: Boolean, reply: Message?, send: (String) -> Unit) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    WearList(if (editing) "메시지 수정" else "메시지 보내기") {
        reply?.let { item { Label("↳ ${it.author.label}: ${it.content.take(80)}") } }
        item { BasicTextField(text, { if (it.length <= 2000) text = it }, textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp).background(Color(0xFF232532), RoundedCornerShape(12.dp)).padding(12.dp),
            decorationBox = { input -> if (text.isEmpty()) Text("여기를 눌러 입력", color = Muted, fontSize = 13.sp); input() }) }
        item { Label("${text.length} / 2000") }
        item { Action(if (editing) "수정 저장" else "보내기") { if (text.isNotBlank()) send(text) } }
    }
}
