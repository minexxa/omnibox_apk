package com.omnibox.app.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 首页「豆瓣热播」数据源 —— 完全对齐 ubox 的 DoubanHot：
 * 直连 movie.douban.com/j/new_search_subjects 抓当年热播电影，按天缓存。
 * 豆瓣对 alm/OkHttp 默认 UA 返回 418，故必须用桌面 UA。
 * 抓取失败返回空列表，由首页展示错误态。
 */
object DoubanHot {
    private const val DAY_KEY = "douban_hot_day"
    private const val JSON_KEY = "douban_hot_json"
    private const val MAX_ITEMS = 25

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** 首页分类（label -> 豆瓣 tags），与 ubox 首页 热播电影/热播剧集/热播综艺 一致。 */
    val categories: List<Pair<String, String>> = listOf(
        "热播电影" to "电影",
        "热播剧集" to "电视剧",
        "热播综艺" to "综艺",
        "热播动漫" to "动画",
    )

    private fun url(tag: String): String {
        val y = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val t = java.net.URLEncoder.encode(tag, "UTF-8")
        return "https://movie.douban.com/j/new_search_subjects" +
            "?sort=U&range=0,10&tags=$t&playable=1&start=0&year_range=$y,$y"
    }

    /** 取热播列表：先按天缓存，再走网络。返回空列表表示失败。 */
    suspend fun fetch(ctx: Context, tag: String = "电影"): List<Vod> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sp = ctx.getSharedPreferences("douban_hot", Context.MODE_PRIVATE)
        val today = today()
        if (sp.getString(DAY_KEY + tag, "") == today) {
            val cached = sp.getString(JSON_KEY + tag, "") ?: ""
            val items = parse(cached)
            if (items.isNotEmpty()) return@withContext items
        }
        val body = runCatching {
            val req = Request.Builder().url(url(tag))
                .header("User-Agent", UA)
                .header("Referer", "https://movie.douban.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")

        val items = parse(body)
        if (items.isNotEmpty()) {
            sp.edit().putString(DAY_KEY + tag, today).putString(JSON_KEY + tag, body).apply()
        }
        items
    }

    /** 豆瓣 {"data":[{"title","rate","cover",...}]} -> Vod（无 vod_id，点击走搜索）。 */
    private fun parse(body: String): List<Vod> {
        if (body.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return emptyList()
            val result = mutableListOf<Vod>()
            for (i in 0 until minOf(data.length(), MAX_ITEMS)) {
                val o = data.optJSONObject(i) ?: continue
                val title = o.optString("title", "")
                if (title.isBlank()) continue
                val rate = o.optString("rate", "").trim()
                val score = if (rate.isNotEmpty() && rate != "0") rate else ""
                result.add(
                    Vod(
                        vodId = "",                 // 豆瓣热播无 vod_id，按片名搜索
                        name = title,
                        pic = o.optString("cover", ""),
                        remarks = if (score.isNotEmpty()) "$score 分" else "",
                        source = "douban",
                        score = score,
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }
}
