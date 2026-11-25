package com.omnibox.app.ui.vod

import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.data.Core
import com.omnibox.app.data.Detail
import com.omnibox.app.data.Episode
import com.omnibox.app.data.PlayResult
import com.omnibox.app.data.parseDetail
import com.omnibox.app.data.parsePlay
import com.omnibox.app.player.ExoEngine
import com.omnibox.app.player.PlayRequest
import com.omnibox.app.player.PlayerListener
import com.omnibox.app.player.inferMimeType
import com.omnibox.app.ui.Toast
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.TvFocusable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val C_ORANGE = Color(0xFFFF5F00)
private val C_BLUE = Color(0xFF1890FF)
private val C_GREEN = Color(0xFF90CA09)
private val C_PINK = Color(0xFFFF0057)
private val CELL_BG = Color(0x6C3D3D3D)

/**
 * 点播页（TV，忠实对齐 ubox SmallLayout / FullscreenPlayer 布局）：
 *   小屏 = 顶部(左预览播放器 470:300 + 右影视信息/四色胶囊) + 正倒序分页 + 剧集网格(按集名自适应列数)
 *   全屏 = 播放器铺满 + 遥控器按键控制（OK 播放暂停 / 左右快进快退 / 下键控制层 / 返回退出）+ 3 秒自动隐藏控制层
 * 播放器实例跨小屏/全屏不变；内核 detail/play/历史/收藏数据流保持不变。
 */
@Composable
fun VodScreen(
    src: String,
    vodId: String,
    name: String,
    pic: String,
    onBack: () -> Unit,
    onSwitchSource: (String, String, String) -> Unit,
) {
    val prefs = remember { OmniBoxApp.prefs() }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var error by remember { mutableStateOf("") }
    var selectedEp by remember { mutableStateOf(-1) }
    var fullscreen by remember { mutableStateOf(false) }

    var playError by remember { mutableStateOf("") }
    var playPosition by remember { mutableStateOf(0L) }
    var playDuration by remember { mutableStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    var playing by remember { mutableStateOf(false) }

    var showDesc by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    val fav = remember(detail) { mutableStateOf(prefs.isFavorite(src, vodId)) }

    val engine = remember {
        ExoEngine(OmniBoxApp.ctx, prefs.decoder).apply {
            listener = object : PlayerListener {
                override fun onReady() { buffering = false }
                override fun onBuffering(on: Boolean) { buffering = on }
                override fun onPlaying(on: Boolean) { playing = on }
                override fun onProgress(position: Long, duration: Long, buffered: Long) {
                    playPosition = position; playDuration = duration
                }
                override fun onVideoSize(w: Int, h: Int) {
                    if (w > 0 && h > 0) videoAspect = w.toFloat() / h.toFloat()
                }
                override fun onError(msg: String) { playError = msg; buffering = false }
                override fun onEnded() {
                    if (prefs.autoNext && detail != null && selectedEp < detail!!.episodes.size - 1) {
                        selectedEp += 1
                    }
                }
            }
        }
    }
    DisposableEffect(engine) { onDispose { engine.release() } }

    // 拉详情
    LaunchedEffect(src, vodId) {
        val resp = Core.detail(src, vodId)
        if (resp.has("vod")) {
            val d = parseDetail(resp)
            detail = d
            error = ""
            val saved = prefs.position(keyOf(src, vodId))
            selectedEp = (saved / 1000000L).toInt().coerceIn(0, d.episodes.size - 1).coerceAtLeast(0)
        } else {
            error = resp.optString("error", "详情加载失败")
        }
    }

    // 选集 -> 解析播放 -> 起播 + 写历史
    LaunchedEffect(selectedEp, detail) {
        val d = detail ?: return@LaunchedEffect
        if (selectedEp !in d.episodes.indices) return@LaunchedEffect
        val ep = d.episodes[selectedEp]
        playError = ""
        val resp = Core.play(src, ep.q)
        if (resp.has("url") && !resp.optString("url").isNullOrBlank()) {
            val pr = parsePlay(resp)
            engine.open(
                PlayRequest(
                    url = pr.url, headers = pr.header,
                    mimeType = inferMimeType(pr.url), speed = prefs.speed,
                )
            )
            engine.play()
            Core.historyAdd(src, vodId, d.vod.name, d.vod.pic, selectedEp, ep.name)
            prefs.savePosition(keyOf(src, vodId), selectedEp * 1000000L)
        } else {
            playError = resp.optString("error", "播放解析失败")
        }
    }

    // 进度轮询
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            engine.pollProgress()
        }
    }

    if (detail == null) {
        StateBox(loading = error.isEmpty(), error = error)
        return
    }
    val d = detail!!
    val epName = d.episodes.getOrNull(selectedEp)?.name ?: ""

    if (fullscreen) {
        FullscreenPlayer(
            engine = engine, title = d.vod.name, epName = epName,
            total = d.episodes.size, epIndex = selectedEp,
            playing = playing, buffering = buffering, error = playError,
            positionMs = playPosition, durationMs = playDuration,
            videoAspect = videoAspect, speed = prefs.speed,
            onSelectEp = { selectedEp = it },
            onEpisodes = { showEpisodes = true },
            onExit = { fullscreen = false },
        )
        if (showEpisodes) {
            EpisodePicker(
                total = d.episodes.size, eps = d.episodes, current = selectedEp,
                onPick = { selectedEp = it; showEpisodes = false },
                onDismiss = { showEpisodes = false },
            )
        }
        return
    }

    SmallLayout(
        d = d, src = src, vodId = vodId, engine = engine,
        selectedEp = selectedEp, onSelectEp = { selectedEp = it },
        playing = playing, buffering = buffering, error = playError,
        positionMs = playPosition, durationMs = playDuration, videoAspect = videoAspect,
        fav = fav.value, onToggleFav = {
            val added = prefs.toggleFavorite(src, vodId)
            fav.value = added
            if (added) {
                prefs.saveFavoriteMeta("$src|$vodId", d.vod.name, d.vod.pic, src, vodId)
                Toast.show("已加入收藏")
            } else {
                prefs.removeFavoriteMeta("$src|$vodId")
                Toast.show("已取消收藏")
            }
        },
        prefsSpeed = prefs.speed,
        onSpeed = {
            val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
            val next = speeds[(speeds.indexOf(prefs.speed) + 1) % speeds.size]
            prefs.speed = next
            engine.setSpeed(next)
        },
        onFullscreen = { fullscreen = true },
        onBack = onBack,
        showDesc = showDesc, onToggleDesc = { showDesc = !showDesc },
    )
}

/** 小屏：顶部 58/42 分栏 + 正倒序分页 + 剧集网格。 */
@Composable
private fun SmallLayout(
    d: Detail, src: String, vodId: String, engine: ExoEngine,
    selectedEp: Int, onSelectEp: (Int) -> Unit,
    playing: Boolean, buffering: Boolean, error: String,
    positionMs: Long, durationMs: Long, videoAspect: Float,
    fav: Boolean, onToggleFav: () -> Unit,
    prefsSpeed: Float, onSpeed: () -> Unit,
    onFullscreen: () -> Unit, onBack: () -> Unit,
    showDesc: Boolean, onToggleDesc: () -> Unit,
) {
    val total = d.episodes.size
    var reverse by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    LaunchedEffect(src, vodId) { page = 0; reverse = false }
    val PAGE_SIZE = 20
    val paged = total > PAGE_SIZE
    val pageCount = if (total <= 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE
    val safePage = page.coerceIn(0, maxOf(0, pageCount - 1))
    val ordered = remember(total, reverse) {
        if (reverse) (0 until total).reversed().toList() else (0 until total).toList()
    }
    val pageItems = if (paged) ordered.drop(safePage * PAGE_SIZE).take(PAGE_SIZE) else ordered

    // 剧集网格列数：按最长集名自适应 2~6 列
    val maxChars = remember(d) { d.episodes.maxOfOrNull { it.name.length } ?: 0 }
    val cols = when {
        maxChars <= 5 -> 6
        maxChars <= 8 -> 5
        maxChars <= 12 -> 4
        maxChars <= 16 -> 3
        else -> 2
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(selectedEp, reverse, total) {
        if (paged) {
            val pos = ordered.indexOf(selectedEp)
            if (pos >= 0) page = pos / PAGE_SIZE
        }
    }
    LaunchedEffect(safePage, selectedEp, reverse, total, cols) {
        val idx = pageItems.indexOf(selectedEp)
        runCatching { gridState.scrollToItem(if (idx >= 0) maxOf(0, idx - cols) else 0) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // 顶部区固定高度（预览播放器按 470:300），最多占 46% 屏高
        val topH = minOf(maxWidth / 1280f * 300f, maxHeight * 0.46f)
        val playerW = topH * (470f / 300f)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(topH).padding(horizontal = 50.dp)) {
                // 左：圆角预览播放器（点击进全屏），内部按原始比例 contain
                TvFocusable(
                    onClick = onFullscreen,
                    modifier = Modifier.width(playerW).fillMaxHeight(),
                    radius = 12.dp, scale = 1f,
                ) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    ) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val fitW = maxHeight * videoAspect <= maxWidth
                            val vw = if (fitW) maxHeight * videoAspect else maxWidth
                            val vh = if (fitW) maxHeight else maxWidth / videoAspect
                            Box(Modifier.align(Alignment.Center).size(vw, vh)) {
                                PlayerSurface(engine)
                            }
                        }
                        if (buffering) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp,
                                modifier = Modifier.align(Alignment.Center).size(38.dp))
                        }
                        if (error.isNotEmpty()) {
                            Text(error, color = Color.White, fontSize = 12.sp, maxLines = 4,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(14.dp))
                        }
                        Text(
                            "${d.vod.name} · ${d.episodes.getOrNull(selectedEp)?.name.orEmpty()}",
                            color = Color.White, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                .background(Color(0x73000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                // 右：影视信息 + 四色胶囊按钮
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(d.vod.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val meta = buildList {
                        if (src.isNotBlank()) add("来源：$src")
                        if (d.vod.year.isNotEmpty()) add("年份：${d.vod.year}")
                        if (d.vod.area.isNotEmpty()) add("地区：${d.vod.area}")
                        if (d.vod.typeName.isNotEmpty()) add("类型：${d.vod.typeName}")
                    }
                    if (meta.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            meta.forEach { Text(it, color = Color(0xBBFFFFFF), fontSize = 18.sp, maxLines = 1) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (d.vod.actor.isNotEmpty()) {
                        Text("演员：${d.vod.actor}", color = Color(0xBBFFFFFF), fontSize = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (d.vod.director.isNotEmpty()) {
                        Text("导演：${d.vod.director}", color = Color(0xBBFFFFFF), fontSize = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (d.vod.content.isNotEmpty()) {
                        Text(
                            "简介：${d.vod.content}", color = Color(0xBBFFFFFF), fontSize = 18.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 四色胶囊按钮（对齐 NovaTV button_detail_*）：橙=全屏 / 蓝=快速搜索 / 绿=简介 / 粉=收藏
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NovaColorButton("全屏", C_ORANGE, onFullscreen)
                        NovaColorButton("快速搜索", C_BLUE, onBack)
                        NovaColorButton("简介", C_GREEN, onToggleDesc)
                        NovaColorButton(if (fav) "取消收藏" else "加入收藏", C_PINK, onToggleFav)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 播放控制 + 进度（小屏底部）
            Row(Modifier.fillMaxWidth().padding(horizontal = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                NovaChip(if (playing) "暂停" else "播放", false) {
                    if (engine.isPlaying) engine.pause() else engine.play()
                }
                Spacer(Modifier.width(10.dp))
                NovaChip("快退10s", false) { engine.seekTo((positionMs - 10000).coerceAtLeast(0)) }
                Spacer(Modifier.width(10.dp))
                NovaChip("快进10s", false) { engine.seekTo(positionMs + 10000) }
                Spacer(Modifier.width(10.dp))
                NovaChip(if (prefsSpeed != 1f) "倍速 ${prefsSpeed}x" else "倍速", false, onSpeed)
                Spacer(Modifier.width(16.dp))
                Text(formatTime(positionMs), color = Color.White, fontSize = 14.sp)
                LinearProgressIndicator(
                    progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
                    modifier = Modifier.weight(1f).height(4.dp).padding(horizontal = 12.dp),
                    color = C_BLUE,
                )
                Text(formatTime(durationMs), color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            // 正序/倒序 + 分页
            if (paged) {
                Row(Modifier.padding(horizontal = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                    NovaChip(if (reverse) "倒序" else "正序", false) { reverse = !reverse }
                    Spacer(Modifier.width(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed((0 until pageCount).toList()) { p, _ ->
                            val s = p * PAGE_SIZE + 1
                            val e = minOf((p + 1) * PAGE_SIZE, total)
                            NovaChip("$s-$e", p == safePage) { page = p }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // 剧集网格
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 50.dp, vertical = 6.dp),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(pageItems) { _, realIdx ->
                    val sel = realIdx == selectedEp
                    val epName = d.episodes.getOrNull(realIdx)?.name.orEmpty()
                    NovaSeriesCell(epName.ifEmpty { "第${realIdx + 1}集" }, sel, realIdx == 0) {
                        onSelectEp(realIdx)
                    }
                }
            }
        }
    }

    // 简介弹窗
    if (showDesc) {
        AlertDialog(
            onDismissRequest = onToggleDesc,
            title = { Text("简介", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(d.vod.content.ifEmpty { "暂无简介" }, color = Color.White, fontSize = 15.sp) },
            confirmButton = { NovaDialogButton("关闭", onToggleDesc) },
        )
    }
}

/** 全屏播放器：遥控器按键控制 + 3 秒自动隐藏控制层 + 顶栏/底栏。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullscreenPlayer(
    engine: ExoEngine, title: String, epName: String,
    total: Int, epIndex: Int, playing: Boolean, buffering: Boolean, error: String,
    positionMs: Long, durationMs: Long, videoAspect: Float, speed: Float,
    onSelectEp: (Int) -> Unit, onEpisodes: () -> Unit, onExit: () -> Unit,
) {
    var overlay by remember { mutableStateOf(false) }
    var overlayTick by remember { mutableStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    fun poke() { overlay = true; overlayTick++ }
    fun hide() { overlay = false }

    LaunchedEffect(Unit) { hide() }
    LaunchedEffect(overlay, overlayTick) {
        if (overlay) {
            delay(3000)
            hide()
        }
    }
    LaunchedEffect(overlay) {
        if (!overlay) {
            repeat(6) {
                delay(16)
                runCatching { rootFocus.requestFocus() }
            }
        }
    }

    // 遥控器累计快进：首次 10s，连按每次 20s
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var lastSeekAt by remember { mutableStateOf(0L) }
    fun startSeek(dir: Int) {
        if (durationMs <= 0) return
        val now = System.currentTimeMillis()
        val base = seekTarget ?: positionMs
        val step = if (seekTarget != null && now - lastSeekAt <= 800) 20_000L else 10_000L
        seekTarget = (base + dir * step).coerceIn(0L, durationMs)
        lastSeekAt = now
    }
    fun commitSeek() {
        seekTarget?.let { engine.seekTo(it) }
        seekTarget = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent.keyCode
                val down = ev.type == KeyEventType.KeyDown
                val up = ev.type == KeyEventType.KeyUp
                val first = down && ev.nativeKeyEvent.repeatCount == 0
                if (overlay) {
                    if (down) poke()
                    return@onPreviewKeyEvent false
                }
                when (k) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (engine.isPlaying) engine.pause() else engine.play() }; true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) startSeek(if (k == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                        else if (up) commitSeek()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (first) { if (engine.isPlaying) engine.pause() else engine.play() }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> { if (down) poke(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (first) poke(); true }
                    else -> false
                }
            },
    ) {
        // 视频按原始比例 contain 铺满
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fitW = maxHeight * videoAspect <= maxWidth
            val vw = if (fitW) maxHeight * videoAspect else maxWidth
            val vh = if (fitW) maxHeight else maxWidth / videoAspect
            Box(Modifier.align(Alignment.Center).size(vw, vh)) {
                PlayerSurface(engine)
            }
        }

        // 暂停指示
        if (!playing && !buffering && error.isEmpty() && !overlay) {
            Box(
                Modifier.align(Alignment.Center).size(72.dp)
                    .background(Color(0x73000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = Color.White, fontSize = 36.sp)
            }
        }
        if (buffering) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        if (error.isNotEmpty()) {
            Text(error, color = Color.White, fontSize = 13.sp, maxLines = 6,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(16.dp))
        }

        if (overlay) {
            // 顶栏
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color(0x8C000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$title · $epName", color = Color.White, fontSize = 18.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TvOsdButton("退出", onExit)
            }
            // 底栏：按钮行 + 进度条
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0x8C000000)).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (epIndex + 1 < total) TvOsdButton("下一集", { onSelectEp(epIndex + 1) })
                    if (epIndex > 0) TvOsdButton("上一集", { onSelectEp(epIndex - 1) })
                    TvOsdButton("选集", onEpisodes)
                    TvOsdButton(if (speed != 1f) "倍速 ${speed}x" else "倍速") {
                        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                        val next = speeds[(speeds.indexOf(speed) + 1) % speeds.size]
                        engine.setSpeed(next)
                    }
                    TvOsdButton("播放/暂停") { if (engine.isPlaying) engine.pause() else engine.play() }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(positionMs), color = Color.White, fontSize = 14.sp,
                        modifier = Modifier.width(64.dp))
                    LinearProgressIndicator(
                        progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
                        modifier = Modifier.weight(1f).height(6.dp), color = C_BLUE,
                    )
                    Text(formatTime(durationMs), color = Color.White, fontSize = 14.sp,
                        textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                }
            }
        }
    }
}

/** 全屏选集弹窗。 */
@Composable
private fun EpisodePicker(
    total: Int, eps: List<Episode>, current: Int,
    onPick: (Int) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选集（$total）", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.height(320.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(eps) { idx, ep ->
                    NovaSeriesCell(ep.name.ifEmpty { "第${idx + 1}集" }, idx == current, false) { onPick(idx) }
                }
            }
        },
        confirmButton = { NovaDialogButton("关闭", onDismiss) },
    )
}

// ---------------- 通用小组件（对齐 ubox NovaTV 风格）----------------

@Composable
private fun NovaColorButton(label: String, color: Color, onTap: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onClick = onTap, modifier = Modifier.height(40.dp), radius = 20.dp,
        borderColor = Color.White, showBorder = true, scale = 1f,
        onFocusChanged = { focused = it },
    ) {
        Box(
            Modifier.fillMaxHeight()
                .background(color.copy(alpha = if (focused) 1f else 0.85f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun NovaChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onClick = onClick, modifier = Modifier.height(40.dp), radius = 18.dp,
        borderColor = Color.White, showBorder = true, scale = 1f,
        onFocusChanged = { focused = it },
    ) {
        Box(
            Modifier.fillMaxHeight()
                .background(if (selected || focused) C_BLUE else CELL_BG, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NovaSeriesCell(name: String, selected: Boolean, autofocus: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onClick = onClick, modifier = Modifier.height(52.dp), autofocus = autofocus,
        radius = 8.dp, borderColor = Color.White, showBorder = true, scale = 1f,
        onFocusChanged = { focused = it },
    ) {
        Box(
            Modifier.fillMaxWidth().height(52.dp)
                .background(if (selected || focused) C_BLUE else CELL_BG, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(name, color = Color.White, fontSize = 17.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TvOsdButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        onClick = onClick, modifier = Modifier.height(48.dp), radius = 10.dp,
        borderColor = Color.White, showBorder = true, scale = 1f,
        onFocusChanged = { focused = it },
    ) {
        Box(
            Modifier.height(48.dp)
                .background(if (focused) Color(0xAAFFFFFF) else Color(0x6C3D3D3D), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (focused) Color.Black else Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun NovaDialogButton(label: String, onClick: () -> Unit) {
    TvFocusable(onClick = onClick, radius = 8.dp) {
        Text(label, color = Color.White, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(CELL_BG).padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

private fun keyOf(src: String, id: String) = "$src|$id"

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

/** 播放器 Surface：等 SurfaceView 真正创建后再挂给 ExoPlayer，避免黑屏。 */
@Composable
private fun PlayerSurface(engine: ExoEngine) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = engine.setSurface(holder.surface)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        engine.setSurface(holder.surface)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.setSurface(null)
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
