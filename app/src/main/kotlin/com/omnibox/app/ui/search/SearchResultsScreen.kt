@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.omnibox.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.data.Core
import com.omnibox.app.data.ListResult
import com.omnibox.app.data.parseListResult
import com.omnibox.app.ui.components.DoubanPoster
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.OmniButton

/**
 * 搜索结果页（忠实对齐 ubox SearchResultsScreen 的 TV 布局）：
 *   顶部：标题 + 搜索进度条
 *   左侧（160dp）：源筛选栏 —— 「全部」+ 有结果的各来源站
 *   右侧：4 列固定宽度海报网格（ubox VodPoster 样式，左上角来源角标）
 *   底部：分页（上一页 / x / y / 下一页）
 */
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
    var sourceFilter by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        Core.refreshSpiderLabels()
    }

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

    // 筛选键仍是原始 source id（跳详情/去重需要），显示时用可读 label
    val sources = remember(result) {
        result?.list?.map { it.source }?.filter { it.isNotBlank() }?.distinct().orEmpty()
    }
    val shown = remember(result, sourceFilter) {
        val list = result?.list.orEmpty()
        if (sourceFilter == "all") list else list.filter { it.source == sourceFilter }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // 顶部标题 + 进度条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "搜索结果：$query", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = com.omnibox.app.ui.theme.AppColors.Accent)
        } else {
            Spacer(Modifier.height(3.dp))
        }

        when {
            loading && result == null -> StateBox(loading = true, error = "")
            shown.isEmpty() && !loading -> StateBox(loading = false, error = error.ifEmpty { "没有搜索结果" })
            else -> {
                Row(Modifier.fillMaxSize()) {
                    // 左侧源筛选栏
                    Box(Modifier.width(160.dp)) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 6.dp, top = 4.dp, end = 4.dp, bottom = 12.dp),
                        ) {
                            item {
                                SourceTile(
                                    name = "全部",
                                    selected = sourceFilter == "all",
                                    onClick = { sourceFilter = "all" },
                                )
                            }
                            items(sources) { s ->
                                SourceTile(
                                    name = Core.sourceLabel(s),
                                    selected = sourceFilter == s,
                                    onClick = { sourceFilter = s },
                                )
                            }
                        }
                    }
                    // 右侧 4 列海报网格
                    Box(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.fillMaxSize().weight(1f),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                itemsIndexed(shown) { idx, vod ->
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        DoubanPoster(
                                            vod = vod,
                                            autofocus = idx == 0,
                                            // 角标显示脚本可读名（如"瓜子APP"），跳转仍用原始 source id
                                            badge = Core.sourceLabel(vod.source).ifBlank { null },
                                            note = vod.remarks.ifBlank { null },
                                            width = 150.dp,
                                        ) {
                                            onOpenDetail(vod.source, vod.vodId, vod.name, vod.pic)
                                        }
                                    }
                                }
                            }
                            // 分页
                            Row(
                                Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TvPill("上一页", enabled = page > 1) { if (page > 1) page-- }
                                Text(" $page / ${result?.pagecount ?: 1} ", color = Color.White, fontSize = 15.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp))
                                TvPill("下一页", enabled = page < (result?.pagecount ?: 1)) {
                                    if (page < (result?.pagecount ?: 1)) page++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceTile(name: String, selected: Boolean, onClick: () -> Unit) {
    OmniButton(
        label = name, onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        selected = selected, height = 52.dp, radius = 12.dp, fontSize = 17.sp,
        fontWeight = if (selected) FontWeight.Bold else null,
        containerColor = Color.Transparent,
        selectedContainerColor = Color(0x33FFFFFF),
        focusedContainerColor = Color(0x55FFFFFF),
        selectedBorder = true,
        contentPadding = PaddingValues(horizontal = 8.dp),
    )
}

@Composable
private fun TvPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    OmniButton(
        label = label, onClick = onClick, enabled = enabled,
        height = 40.dp, radius = 18.dp, fontSize = 15.sp,
    )
}

