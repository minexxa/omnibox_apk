package com.omnibox.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 影片卡片（搜索/分类/首页列表通用）。 */
data class Vod(
    val vodId: String,
    val name: String,
    val pic: String,
    val remarks: String = "",
    val source: String = "",
    val year: String = "",
    val score: String = "",
    val subtitle: String = "",
    val typeName: String = "",
    val content: String = "",
    val area: String = "",
    val actor: String = "",
    val director: String = "",
)

/** 首页 Banner（来自内核 douban_home 的腾讯轮播）。 */
data class Banner(
    val title: String,
    val subtitle: String,
    val backgroundImage: String,
    val genre: String,
    val actors: String,
    val description: String,
)

/** 豆瓣分类入口。 */
data class ClassEntry(val typeId: String, val typeName: String)

/** 筛选器：key/name/init + 可选项列表。 */
data class Filter(val key: String, val name: String, val init: String, val values: List<Pair<String, String>>)

/** 列表分页结果。 */
data class ListResult(val list: List<Vod>, val page: Int, val pagecount: Int, val total: Int = 0)

/** 首页聚合数据。 */
data class HomeData(
    val banners: List<Banner>,
    val classes: List<ClassEntry>,
    val filters: Map<String, List<Filter>>,
    val movie: ListResult,
    val tv: ListResult,
)

/** 详情 + 归一化剧集。q 为内核 base64 后的播放参数，直接传给 play。 */
data class Detail(val vod: Vod, val episodes: List<Episode>, val source: String)
data class Episode(val name: String, val q: String)

/** 播放解析结果。 */
data class PlayResult(val url: String, val header: Map<String, String>, val parse: Int)

/** 播放历史（内核持久化）。 */
data class HistoryItem(
    val key: String,
    val src: String,
    val vodId: String,
    val name: String,
    val pic: String,
    val epIndex: Int,
    val epName: String,
    val playedAt: Long,
)

/** 脚本元信息（内核注册表）。 */
data class Spider(
    val id: String,
    val name: String,
    val origin: String,
    val url: String,
    val enabled: Boolean,
    val displayName: String,
    val lastError: String,
) {
    val label: String get() = displayName.ifEmpty { name.ifEmpty { id } }
}

// ---------- JSON 解析工具 ----------

private fun str(o: JSONObject, k: String): String = runCatching { o.optString(k, "") }.getOrDefault("")
private fun arr(o: JSONObject, k: String): JSONArray = o.optJSONArray(k) ?: JSONArray()

fun parseVod(o: JSONObject, defaultSource: String = ""): Vod = Vod(
    vodId = str(o, "vod_id"),
    name = str(o, "vod_name").ifEmpty { str(o, "title") },
    pic = str(o, "vod_pic"),
    remarks = str(o, "vod_remarks"),
    source = str(o, "source").ifEmpty { defaultSource },
    year = str(o, "vod_year"),
    score = str(o, "vod_douban_score"),
    subtitle = str(o, "vod_subtitle"),
    typeName = str(o, "type_name"),
    content = str(o, "vod_content"),
    area = str(o, "vod_area"),
    actor = str(o, "vod_actor"),
    director = str(o, "vod_director"),
)

fun parseListResult(o: JSONObject): ListResult {
    val list = arr(o, "list")
    val items = (0 until list.length()).mapNotNull { runCatching { list.getJSONObject(it) }.getOrNull() }
        .map { parseVod(it) }
    return ListResult(
        list = items,
        page = o.optInt("page", 1),
        pagecount = o.optInt("pagecount", 1).coerceAtLeast(1),
        total = o.optInt("total", items.size),
    )
}

fun parseHome(o: JSONObject): HomeData {
    val banners = arr(o, "banners").let { a ->
        (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }.map {
            Banner(
                title = str(it, "title"), subtitle = str(it, "subtitle"),
                backgroundImage = str(it, "backgroundImage"), genre = str(it, "genre"),
                actors = str(it, "actors"), description = str(it, "description"),
            )
        }
    }
    val classes = arr(o, "classes").let { a ->
        (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }.map {
            ClassEntry(str(it, "type_id"), str(it, "type_name"))
        }
    }
    val filters = mutableMapOf<String, List<Filter>>()
    runCatching {
        val fo = o.optJSONObject("filters") ?: JSONObject()
        fo.keys().forEach { key ->
            val fa = fo.optJSONArray(key) ?: return@forEach
            filters[key] = (0 until fa.length()).mapNotNull { runCatching { fa.getJSONObject(it) }.getOrNull() }
                .map { f ->
                    val vs = arr(f, "value").let { va ->
                        (0 until va.length()).mapNotNull { runCatching { va.getJSONObject(it) }.getOrNull() }
                            .map { str(it, "name") to str(it, "value") }
                    }
                    Filter(str(f, "key"), str(f, "name"), str(f, "init"), vs)
                }
        }
    }
    val movie = runCatching { o.optJSONObject("movie")?.let { parseListResult(it) } }.getOrNull() ?: ListResult(emptyList(), 1, 1)
    val tv = runCatching { o.optJSONObject("tv")?.let { parseListResult(it) } }.getOrNull() ?: ListResult(emptyList(), 1, 1)
    return HomeData(banners, classes, filters, movie, tv)
}

fun parseDetail(o: JSONObject): Detail {
    val vodObj = o.optJSONObject("vod") ?: JSONObject()
    val vod = parseVod(vodObj)
    val episodes = arr(o, "episodes").let { a ->
        (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }
            .map { Episode(str(it, "name"), str(it, "q")) }
    }
    return Detail(vod, episodes, str(o, "source"))
}

fun parsePlay(o: JSONObject): PlayResult {
    val header = mutableMapOf<String, String>()
    runCatching {
        val ho = o.optJSONObject("header") ?: JSONObject()
        ho.keys().forEach { header[it] = str(ho, it) }
    }
    return PlayResult(str(o, "url"), header, o.optInt("parse", 0))
}

fun parseHistory(o: JSONObject): List<HistoryItem> = arr(o, "list").let { a ->
    (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }.map {
        HistoryItem(
            key = str(it, "key"), src = str(it, "src"), vodId = str(it, "vod_id"),
            name = str(it, "name"), pic = str(it, "pic"),
            epIndex = it.optInt("ep_index", 0), epName = str(it, "ep_name"),
            playedAt = it.optLong("played_at", 0L),
        )
    }
}

fun parseSpiders(o: JSONObject): List<Spider> = arr(o, "list").let { a ->
    (0 until a.length()).mapNotNull { runCatching { a.getJSONObject(it) }.getOrNull() }.map {
        Spider(
            id = str(it, "id"), name = str(it, "name"), origin = str(it, "origin"),
            url = str(it, "url"), enabled = it.optBoolean("enabled", false),
            displayName = str(it, "display_name"), lastError = str(it, "last_error"),
        )
    }
}
