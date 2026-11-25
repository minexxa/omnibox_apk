package com.omnibox.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.data.Core
import com.omnibox.app.data.ListResult
import com.omnibox.app.data.parseListResult
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.components.VodCard
import org.json.JSONObject

@Composable
fun SearchResultsScreen(
    query: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String, String, String) -> Unit,
) {
    val prefs = remember { OmniBoxApp.prefs() }
    var result by remember { mutableStateOf<ListResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    // 来源过滤：all 或脚本 id
    var sourceFilter by remember { mutableStateOf("all") }

    LaunchedEffect(query, page) {
        loading = true
        val resp = Core.search(prefs.lastSource, query, page)
        if (resp.has("list")) {
            result = parseListResult(resp)
            error = resp.optString("error", "")
            if (error.isEmpty()) {
                val errs = resp.optJSONArray("errors")
                if (errs != null && errs.length() > 0 && result!!.list.isEmpty()) {
                    error = (0 until errs.length()).joinToString("\n") { errs.optString(it) }
                }
            }
        } else {
            error = resp.optString("error", "搜索失败")
        }
        loading = false
    }

    val sources = remember(result) {
        result?.list?.map { it.source }?.filter { it.isNotBlank() }?.distinct().orEmpty()
    }
    val shown = remember(result, sourceFilter) {
        val list = result?.list.orEmpty()
        if (sourceFilter == "all") list else list.filter { it.source == sourceFilter }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TopBarButton("返回") { onBack() }
            Text("搜索：$query", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp))
        }

        // 来源过滤条
        if (sources.size > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("来源：", color = Color.Gray, fontSize = 14.sp)
                TopBarButton("全部") { sourceFilter = "all" }
                sources.forEach { s -> TopBarButton(s) { sourceFilter = s } }
            }
        }

        when {
            loading && result == null -> StateBox(loading = true, error = "")
            shown.isEmpty() -> StateBox(loading = false, error = error.ifEmpty { "没有找到相关资源" })
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().weight(1f),
                ) {
                    itemsIndexed(shown) { idx, vod ->
                        VodCard(vod, autofocus = idx == 0, badge = vod.source.ifBlank { null }) {
                            onOpenDetail(vod.source, vod.vodId, vod.name, vod.pic)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopBarButton("上一页") { if (page > 1) page-- }
                    Text(" $page / ${result!!.pagecount} ", color = Color.White, fontSize = 15.sp)
                    TopBarButton("下一页") { if (page < result!!.pagecount) page++ }
                }
            }
        }
    }
}
