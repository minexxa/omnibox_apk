package com.omnibox.app.data

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片解码：远程 URL 走内核 image()（自动带豆瓣 Referer / 拦内网），
 * data URL（base64）直接本地解码。结果做 L1 内存缓存，避免重复 JNI 调用。
 */
object ImageLoader {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > 300
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        val bytes = if (url.startsWith("data:image", ignoreCase = true)) {
            runCatching {
                val comma = url.indexOf(',')
                if (comma > 0) android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT) else null
            }.getOrNull()
        } else {
            Core.image(url)?.second
        } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: return null
        val image = bitmap.asImageBitmap()
        cache[url] = image
        return image
    }
}

/** Compose 取图：返回可为空的 ImageBitmap，加载在协程内完成。 */
@Composable
fun rememberImage(url: String): ImageBitmap? {
    val state = remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        state.value = null
        state.value = ImageLoader.load(url)
    }
    return state.value
}
