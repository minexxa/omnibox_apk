package com.omnibox.app.ui.browse

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
import com.omnibox.app.data.Core
import com.omnibox.app.data.ListResult
import com.omnibox.app.data.parseListResult
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.components.VodCard
import com.omnibox.app.ui.theme.AppColors

/**
 * 分类浏览页。内核 douban_category 目前忽略 filters（Rust 侧硬编码空对象），
 * 故筛选 UI 仅展示、不改变结果；猫腻修复需改内核（用户编译 so 时生效）。
 */
@Composable
fun BrowseScreen(
    cat: String,
    title: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String, String, String) -> Unit,
) {
    var result by remember { mutableStateOf<ListResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(1) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(cat, page) {
        loading = true
        val resp = Core.category(cat, page)
        if (resp.has("list")) {
            result = parseListResult(resp)
            error = ""
        } else {
            error = resp.optString("error", "加载失败")
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarButton("返回") { onBack() }
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp))
        }

        when {
            loading && result == null -> StateBox(loading = true, error = "")
            result == null -> StateBox(loading = false, error = error)
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().weight(1f),
                ) {
                    itemsIndexed(result!!.list) { idx, vod ->
                        VodCard(vod, autofocus = idx == 0) {
                            onOpenDetail("douban", vod.vodId, vod.name, vod.pic)
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
