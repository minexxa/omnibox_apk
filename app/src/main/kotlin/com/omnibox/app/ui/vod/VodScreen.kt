package com.omnibox.app.ui.vod

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.layout.aspectRatio
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.data.Core
import com.omnibox.app.data.Detail
import com.omnibox.app.data.Episode
import com.omnibox.app.data.PlayResult
import com.omnibox.app.data.rememberImage
import com.omnibox.app.data.parseDetail
import com.omnibox.app.data.parsePlay
import com.omnibox.app.player.ExoEngine
import com.omnibox.app.player.PlayRequest
import com.omnibox.app.player.PlayerListener
import com.omnibox.app.player.inferMimeType
import com.omnibox.app.ui.Toast
import com.omnibox.app.ui.theme.AppColors

@OptIn(UnstableApi::class)
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
    var showDesc by remember { mutableStateOf(false) }

    // 播放器状态
    var playResult by remember { mutableStateOf<PlayResult?>(null) }
    var playError by remember { mutableStateOf("") }
    var playPosition by remember { mutableStateOf(0L) }
    var playDuration by remember { mutableStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    // 视频原始宽高比（宽/高），未拿到尺寸前用 16:9 占位
    var videoAspect by remember { mutableStateOf(16f / 9f) }

    val engine = remember {
        ExoEngine(OmniBoxApp.ctx, prefs.decoder).apply {
            listener = object : PlayerListener {
                override fun onReady() { buffering = false }
                override fun onBuffering(on: Boolean) { buffering = on }
                override fun onProgress(position: Long, duration: Long, buffered: Long) {
                    playPosition = position; playDuration = duration
                }
                override fun onError(msg: String) { playError = msg; buffering = false }
                override fun onVideoSize(w: Int, h: Int) {
                    if (w > 0 && h > 0) videoAspect = w.toFloat() / h.toFloat()
                }
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
            playResult = pr
            engine.open(
                PlayRequest(
                    url = pr.url,
                    headers = pr.header,
                    mimeType = inferMimeType(pr.url),
                    startMs = 0L,
                    speed = prefs.speed,
                )
            )
            // 写内核历史
            Core.historyAdd(src, vodId, d.vod.name, d.vod.pic, selectedEp, ep.name)
            // 记忆“选集”编码到位置 key（pos_ 值 = 集数*1e6 + ms，简化：这里只存集数）
            prefs.savePosition(keyOf(src, vodId), (selectedEp * 1000000L))
        } else {
            playError = resp.optString("error", "播放解析失败")
        }
    }

    // 进度轮询
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            engine.pollProgress()
        }
    }

    if (detail == null) {
        com.omnibox.app.ui.components.StateBox(loading = error.isEmpty(), error = error)
        return
    }

    val d = detail!!
    if (fullscreen) {
        FullscreenPlayer(engine, d.vod.name, selectedEp, d.episodes.getOrNull(selectedEp)?.name ?: "",
            playPosition, playDuration, buffering, playError, videoAspect, onExit = { fullscreen = false })
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // 顶部
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            com.omnibox.app.ui.components.TopBarButton("返回") { onBack() }
            Text(d.vod.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            com.omnibox.app.ui.components.TopBarButton(
                if (prefs.isFavorite(src, vodId)) "已收藏" else "收藏"
            ) {
                val added = prefs.toggleFavorite(src, vodId)
                if (added) {
                    prefs.saveFavoriteMeta("$src|$vodId", d.vod.name, d.vod.pic, src, vodId)
                    Toast.show("已加入收藏")
                } else {
                    prefs.removeFavoriteMeta("$src|$vodId")
                    Toast.show("已取消收藏")
                }
            }
        }

        Row(Modifier.fillMaxSize().weight(1f)) {
            // 左：播放器（58%）
            Column(Modifier.fillMaxHeight().weight(0.58f).padding(12.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(videoAspect).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayerSurface(engine)
                    if (buffering) {
                        androidx.compose.material3.CircularProgressIndicator(color = AppColors.Accent)
                    }
                    if (playError.isNotEmpty()) {
                        Text(playError, color = Color(0xFFCF6679), fontSize = 13.sp,
                            modifier = Modifier.background(Color(0xAA000000)).padding(12.dp),
                            textAlign = TextAlign.Center)
                    }
                    // 点播放器进全屏（透明可聚焦层 + 右下角“全屏”按钮）
                    com.omnibox.app.ui.components.TvFocusable(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        onClick = { fullscreen = true },
                    ) { Text("全屏", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(8.dp)) }
                }
                // 控制条
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.omnibox.app.ui.components.TopBarButton(if (engine.isPlaying) "暂停" else "播放") {
                        if (engine.isPlaying) engine.pause() else engine.play()
                    }
                    com.omnibox.app.ui.components.TopBarButton("快退") { engine.seekTo((playPosition - 10000).coerceAtLeast(0)) }
                    com.omnibox.app.ui.components.TopBarButton("快进") { engine.seekTo(playPosition + 10000) }
                    Text(formatTime(playPosition) + " / " + formatTime(playDuration), color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    com.omnibox.app.ui.components.TopBarButton(if (prefs.speed != 1f) "倍速 ${prefs.speed}x" else "倍速") {
                        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                        val next = speeds[(speeds.indexOf(prefs.speed) + 1) % speeds.size]
                        prefs.speed = next
                        engine.setSpeed(next)
                    }
                }

                // 简介
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("简介：", color = Color.Gray, fontSize = 13.sp)
                    Text(d.vod.content.ifEmpty { "暂无简介" }, color = Color(0xFFB0BEC5), fontSize = 13.sp,
                        maxLines = if (showDesc) 8 else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    com.omnibox.app.ui.components.TopBarButton(if (showDesc) "收起" else "展开") { showDesc = !showDesc }
                }
                Text(
                    listOf(d.vod.year, d.vod.area, d.vod.actor, d.vod.director).filter { it.isNotEmpty() }.joinToString("  "),
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            // 右：剧集网格（42%）
            Column(Modifier.fillMaxHeight().weight(0.42f).padding(12.dp)) {
                Text("选集（${d.episodes.size}）", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(90.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().weight(1f),
                ) {
                    itemsIndexed(d.episodes) { idx, ep ->
                        EpisodeCell(ep, idx == selectedEp, idx == 0) { selectedEp = idx }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCell(ep: Episode, selected: Boolean, autofocus: Boolean, onClick: () -> Unit) {
    com.omnibox.app.ui.components.TvFocusable(autofocus = autofocus, onClick = onClick) {
        Text(
            ep.name, color = if (selected) AppColors.Accent else Color.White, fontSize = 14.sp,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) Color(0x331890FF) else Color(0xFF1C2024), RoundedCornerShape(6.dp))
                .then(if (selected) Modifier.border(1.dp, AppColors.Accent, RoundedCornerShape(6.dp)) else Modifier)
                .padding(vertical = 12.dp, horizontal = 6.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(
    engine: ExoEngine,
    name: String,
    epIndex: Int,
    epName: String,
    position: Long,
    duration: Long,
    buffering: Boolean,
    error: String,
    videoAspect: Float,
    onExit: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 按视频原始比例居中铺满（超宽/超高自动留黑边），不用强行拉伸
        BoxWithConstraints(Modifier.align(Alignment.Center).fillMaxSize()) {
            // 在可用区域内取能完整容纳原始比例的最大尺寸（contain 策略）
            val fitWidth = maxHeight * videoAspect <= maxWidth
            val vw = if (fitWidth) maxHeight * videoAspect else maxWidth
            val vh = if (fitWidth) maxHeight else maxWidth / videoAspect
            Box(Modifier.align(Alignment.Center).size(vw, vh)) {
                PlayerSurface(engine)
            }
        }

        if (buffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = AppColors.Accent, modifier = Modifier.align(Alignment.Center))
        }
        if (error.isNotEmpty()) {
            Text(error, color = Color(0xFFCF6679), fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000)).padding(16.dp))
        }

        // 顶部信息条
        Row(Modifier.align(Alignment.TopStart).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            com.omnibox.app.ui.components.TvFocusable(onClick = onExit) {
                Text("退出全屏", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(12.dp))
            }
            Text("$name · $epName", color = Color.White, fontSize = 18.sp,
                modifier = Modifier.padding(start = 16.dp))
        }

        // 底部控制条
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
            Text(formatTime(position) + " / " + formatTime(duration), color = Color.White, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.omnibox.app.ui.components.TopBarButton("暂停/播放") {
                    if (engine.isPlaying) engine.pause() else engine.play()
                }
                com.omnibox.app.ui.components.TopBarButton("快退10s") { engine.seekTo((position - 10000).coerceAtLeast(0)) }
                com.omnibox.app.ui.components.TopBarButton("快进10s") { engine.seekTo(position + 10000) }
            }
        }
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
