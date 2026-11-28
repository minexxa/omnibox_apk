package com.omnibox.app

import android.app.Application
import android.content.Context
import com.omnibox.app.data.Prefs

class OmniBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        // 内核数据目录 = filesDir（脚本、历史由内核写入 filesDir/spiders/）
        val ok = OmniBoxNative.init(filesDir.absolutePath, "")
        coreReady = ok
    }

    companion object {
        lateinit var ctx: Context
            private set
        var coreReady: Boolean = false
            private set

        fun prefs() = Prefs(ctx)
    }
}
