package com.omnibox.app.player

import android.view.Surface

/** 播放器对外事件。 */
interface PlayerListener {
    fun onReady() {}
    fun onBuffering(on: Boolean) {}
    fun onPlaying(on: Boolean) {}
    fun onEnded() {}
    fun onError(msg: String) {}
    fun onProgress(position: Long, duration: Long, buffered: Long) {}
    fun onVideoSize(w: Int, h: Int) {}
}

data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val startMs: Long = 0L,
    val mimeType: String? = null,
    val speed: Float = 1f,
)

interface PlayerEngine {
    var listener: PlayerListener?
    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long
    fun setSurface(surface: Surface?)
    fun open(request: PlayRequest)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun release()
}
