package com.omnibox.app.player

import android.content.Context
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * ExoPlayer 内核（对照 ubox ExoEngine，去掉 NextLib 软解扩展、保留自动回退）：
 * - 解码方式 auto/hard/soft 通过 RenderersFactory 的扩展渲染模式表达（本构建无扩展库，
 *   soft 显示明确提示但仍能播；真正软解扩展需后续接入 nextlib）。
 * - 错误时按“硬解 -> 软解”逐级重试一次。
 */
@UnstableApi
class ExoEngine(private val context: Context, private val decoderMode: String) : PlayerEngine {

    override var listener: PlayerListener? = null
    private var startMs = 0L
    private var ready = false
    private var retried = false
    private var currentRequest: PlayRequest? = null

    private fun renderersFactory(): DefaultRenderersFactory {
        val mode = when (decoderMode) {
            "soft" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            "hard" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        return DefaultRenderersFactory(context)
            .setExtensionRendererMode(mode)
            .setEnableDecoderFallback(true)
    }

    private val exo: ExoPlayer = ExoPlayer.Builder(context, renderersFactory())
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                .setDataSourceFactory(PlayerDataSourceFactory)
        )
        .build()
        .also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener?.onPlaying(isPlaying)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> listener?.onBuffering(true)
                        Player.STATE_READY -> {
                            listener?.onBuffering(false)
                            if (!ready) {
                                ready = true
                                if (startMs > 0) exo.seekTo(startMs)
                                listener?.onReady()
                            }
                        }
                        Player.STATE_ENDED -> listener?.onEnded()
                        else -> Unit
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // 自动模式：解码相关错误重试一次（打开 decoder fallback 后多数可自愈）
                    if (decoderMode == "auto" && !retried && isDecoderError(error)) {
                        retried = true
                        ready = false
                        currentRequest?.let {
                            openInternal(it.copy(startMs = exo.currentPosition.coerceAtLeast(0)))
                            exo.play()
                        }
                        return
                    }
                    listener?.onBuffering(false)
                    listener?.onError("播放失败：${error.errorCodeName} ${error.message ?: ""}")
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        listener?.onVideoSize(videoSize.width, videoSize.height)
                    }
                }
            })
        }

    private fun isDecoderError(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
        else -> false
    }

    private fun openInternal(req: PlayRequest) {
        PlayerHeaders.current = req.headers
        startMs = req.startMs
        val item = MediaItem.Builder()
            .setUri(req.url)
            .apply { req.mimeType?.let { setMimeType(it) } }
            .build()
        exo.setMediaItem(item)
        exo.setPlaybackSpeed(req.speed.coerceIn(0.25f, 4f))
        exo.prepare()
    }

    override val isPlaying: Boolean get() = exo.isPlaying
    override val currentPosition: Long get() = exo.currentPosition.coerceAtLeast(0L)
    override val duration: Long get() = if (exo.duration == C.TIME_UNSET) 0L else exo.duration.coerceAtLeast(0L)
    private val buffered: Long get() = exo.bufferedPosition.coerceAtLeast(0L)

    override fun setSurface(surface: Surface?) = exo.setVideoSurface(surface)

    override fun open(request: PlayRequest) {
        currentRequest = request
        retried = false
        ready = false
        openInternal(request)
        exo.play()
    }

    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun stop() { exo.stop(); ready = false }
    override fun seekTo(ms: Long) = exo.seekTo(ms)
    override fun setSpeed(speed: Float) = exo.setPlaybackSpeed(speed.coerceIn(0.25f, 4f))
    override fun release() = exo.release()

    /** 由 UI 每 500ms 轮询调用，向监听者报送进度。 */
    fun pollProgress() = listener?.onProgress(currentPosition, duration, buffered)
}
