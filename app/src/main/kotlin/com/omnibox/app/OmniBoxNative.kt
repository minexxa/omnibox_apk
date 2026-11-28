package com.omnibox.app

/**
 * 内核 JNI 桥（对应 omnibox_android_core/src/jni_bridge.rs）。
 * 包名与类名必须与 Rust 侧 Java_com_omnibox_app_OmniBoxNative_* 完全一致。
 */
object OmniBoxNative {
    init {
        System.loadLibrary("omnibox_core")
    }

    /** 初始化内核：dataDir 传 filesDir（内核在其下建 spiders/），adminToken 本机可留空。 */
    external fun init(dataDir: String, adminToken: String): Boolean

    /** 通用 RPC：method + params(JSON 字符串) -> JSON 字符串。 */
    external fun call(method: String, params: String): String

    /** 取图片字节：[mime: String, bytes: ByteArray]?（内核自动带豆瓣 Referer）。 */
    external fun image(url: String): Array<Any>?
}
