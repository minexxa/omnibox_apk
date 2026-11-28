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

    // ---- 脚本 id → 可读名称（搜索结果显示用；id 是脚本名 UTF-8 字节的 hex，直接显示会是一串数字）----
    @Volatile
    private var spiderLabels: Map<String, String> = emptyMap()

    /** 拉取脚本列表并刷新 id→label 缓存。进入搜索页前调用。 */
    suspend fun refreshSpiderLabels() {
        val resp = spidersList()
        val arr = runCatching { resp.optJSONArray("list") }.getOrNull() ?: return
        val map = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val label = o.optString("display_name", "").ifEmpty { o.optString("name", "") }
            if (id.isNotBlank() && label.isNotBlank()) map[id] = label
        }
        spiderLabels = map
    }

    /**
     * 把内核返回的来源 id 转成可读名称。
     * 1) 命中脚本 label 缓存 → 用缓存（如"瓜子APP"）；
     * 2) 否则尝试把 hex id 当 UTF-8 解码（如 e7-93-9c-e5-ad-90 → "瓜子"）；
     * 3) 都失败则原样返回。
     */
    fun sourceLabel(id: String): String {
        if (id.isBlank()) return id
        spiderLabels[id]?.let { if (it.isNotBlank()) return it }
        return hexUtf8(id) ?: id
    }

    /** "e7-93-9c-e5-ad-90" / "e7939ce5ad90" → UTF-8 文本；非 hex 或解码失败返回 null。 */
    private fun hexUtf8(s: String): String? = runCatching {
        val hex = s.replace("-", "")
        if (hex.length < 2 || hex.length % 2 != 0 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return null
        }
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        val text = String(bytes, Charsets.UTF_8)
        // 只接受解码后全是可读字符的结果，避免把普通 id 误判
        text.takeIf { it.isNotBlank() && it.all { c -> !c.isISOControl() } }
    }.getOrNull()

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
