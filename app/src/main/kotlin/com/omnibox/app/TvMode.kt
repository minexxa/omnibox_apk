package com.omnibox.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TV 模式检测（对照 ubox TvMode，简化为 TV 布局 + 遥控器交互两态）：
 *   auto = 自动检测 leanback / UI_MODE_TYPE_TELEVISION
 *   on   = 强制 TV（横屏 + 遥控器焦点）
 *   off  = 普通布局
 */
object TvMode {
    private val _isTV = MutableStateFlow(false)
    val isTV: StateFlow<Boolean> = _isTV.asStateFlow()

    fun init() {
        val ctx = OmniBoxApp.ctx
        val mode = ctx.getSharedPreferences("omnibox_tv", Context.MODE_PRIVATE)
            .getString("tv_mode", "auto") ?: "auto"
        val detected = when (mode) {
            "on" -> true
            "off" -> false
            else -> detect(ctx)
        }
        _isTV.value = detected
    }

    fun detect(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (pm.hasSystemFeature("android.software.leanback")) return true
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun orientation(): Int =
        if (_isTV.value) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
