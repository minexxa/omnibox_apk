package com.omnibox.app.data

import android.content.Context
import com.omnibox.app.OmniBoxNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 内核 RPC 统一封装。JNI call 是同步阻塞（内核侧 tokio block_on），
 * 必须切到 IO 线程调用，避免卡住主线程。
 */
object Core {

    /** 通用调用：返回 JSONObject；解析失败时返回 {"ok":false,"error":"..."}。 */
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        runCatching {
            val raw = OmniBoxNative.call(method, params.toString())
            JSONObject(raw)
        }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "内核调用失败") }
    }

    suspend fun isOk(o: JSONObject): Boolean = o.optBoolean("ok", true) && !o.has("error")

    suspend fun home(): JSONObject = call("douban_home")

    suspend fun category(cat: String, page: Int, filters: JSONObject = JSONObject()): JSONObject =
        call("douban_category", JSONObject().put("cat", cat).put("page", page).put("filters", filters))

    suspend fun search(src: String, wd: String, page: Int): JSONObject =
        call("search", JSONObject().put("src", src).put("wd", wd).put("page", page))

    suspend fun detail(src: String, id: String): JSONObject =
        call("detail", JSONObject().put("src", src).put("id", id))

    suspend fun play(src: String, q: String): JSONObject =
        call("play", JSONObject().put("src", src).put("q", q))

    suspend fun spidersList(): JSONObject = call("spiders_list")

    suspend fun historyList(): JSONObject = call("history_list")

    suspend fun historyAdd(
        src: String, id: String, name: String, pic: String, epIndex: Int, epName: String
    ): JSONObject = call("history_add", JSONObject()
        .put("src", src).put("id", id).put("name", name).put("pic", pic)
        .put("ep", epIndex).put("epname", epName))

    suspend fun historyRemove(key: String): JSONObject = call("history_remove", JSONObject().put("key", key))

    suspend fun historyClear(): JSONObject = call("history_clear")

    /** 取图片字节，走 IO 线程。返回 [mime, bytes]，失败返回 null。 */
    suspend fun image(url: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val arr = OmniBoxNative.image(url) ?: return@withContext null
            val mime = arr[0] as? String ?: "image/jpeg"
            val bytes = arr[1] as? ByteArray ?: return@withContext null
            mime to bytes
        }.getOrNull()
    }
}

/** 轻量全局设置（搜索历史、收藏、播放器/TV 模式偏好）。与内核历史分离，存在 SharedPreferences。 */
class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("omnibox_tv", Context.MODE_PRIVATE)

    // ---- 搜索历史 ----
    fun searchHistory(): List<String> = sp.getStringSet("search_history", emptySet())?.toList().orEmpty()
    fun addSearchHistory(q: String) {
        if (q.isBlank()) return
        val set = searchHistory().toMutableList().apply { remove(q); add(0, q) }.take(20).toSet()
        sp.edit().putStringSet("search_history", set).apply()
    }
    fun clearSearchHistory() = sp.edit().remove("search_history").apply()

    // ---- 收藏：key = src|vodId ----
    fun favorites(): Set<String> = sp.getStringSet("favorites", emptySet()) ?: emptySet()
    fun isFavorite(src: String, id: String) = favorites().contains("$src|$id")
    fun toggleFavorite(src: String, id: String): Boolean {
        val key = "$src|$id"
        val set = favorites().toMutableSet()
        val added = if (set.contains(key)) { set.remove(key); false } else { set.add(key); true }
        sp.edit().putStringSet("favorites", set).apply()
        return added
    }
    /** 按 key 直接移除收藏（用于清空）。 */
    fun toggleFavoriteByKey(key: String) {
        val set = favorites().toMutableSet()
        set.remove(key)
        sp.edit().putStringSet("favorites", set).apply()
    }

    /** 收藏额外保存名称/海报，便于收藏页展示（key -> "name\n pic\n src\n id"）。 */
    fun saveFavoriteMeta(key: String, name: String, pic: String, src: String, id: String) {
        sp.edit().putString("fav_meta_$key", listOf(name, pic, src, id).joinToString("\n")).apply()
    }
    fun favoriteMeta(key: String): List<String> = sp.getString("fav_meta_$key", "")?.split("\n") ?: emptyList()
    fun removeFavoriteMeta(key: String) = sp.edit().remove("fav_meta_$key").apply()

    // ---- 播放器 ----
    /** 解码方式：auto / hard / soft。 */
    var decoder: String
        get() = sp.getString("decoder", "auto") ?: "auto"
        set(value) = sp.edit().putString("decoder", value).apply()

    /** 倍速（默认 1.0）。 */
    var speed: Float
        get() = sp.getFloat("speed", 1.0f)
        set(value) = sp.edit().putFloat("speed", value).apply()

    /** 自动续播下一集。 */
    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(value) = sp.edit().putBoolean("auto_next", value).apply()

    /** 起播位置记忆（key -> ms）。 */
    fun position(key: String): Long = sp.getLong("pos_$key", 0L)
    fun savePosition(key: String, ms: Long) = sp.edit().putLong("pos_$key", ms).apply()

    // ---- TV 模式覆盖：auto / on / off ----
    var tvMode: String
        get() = sp.getString("tv_mode", "auto") ?: "auto"
        set(value) = sp.edit().putString("tv_mode", value).apply()

    /** 首页默认图片源（内核 douban_home 直连，无需配置）。 */
    var lastSource: String
        get() = sp.getString("last_source", "all") ?: "all"
        set(value) = sp.edit().putString("last_source", value).apply()
}
