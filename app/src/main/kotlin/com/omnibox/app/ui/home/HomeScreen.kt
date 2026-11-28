@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.omnibox.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.data.DoubanHot
import com.omnibox.app.data.Vod
import com.omnibox.app.ui.components.DoubanPoster
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.OmniButton
import com.omnibox.app.ui.theme.AppColors

/**
 * 首页 —— 忠实对齐 ubox HomeScreen 的 TV 布局：
 *   顶部按钮栏（站源/搜索/收藏/历史/设置）
 *   横向分类 Tab 条（豆瓣热播主页 + 内核分类入口）
 *   主体 LazyVerticalGrid 海报网格（GridCells.Adaptive 190dp，VodPoster 卡片）
 * 影视数据来自 ubox 豆瓣源（DoubanHot），卡片左上角紫色"豆瓣热播"角标、底部评分角标。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDetail: (String, String, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val cats = DoubanHot.categories
    var selectedTab by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 切换分类 / 重试：就地重新取数，不跳转新页面
    LaunchedEffect(selectedTab, reload) {
        loading = true
        items = DoubanHot.fetch(ctx, cats[selectedTab].second)
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // 顶部按钮栏（对齐 ubox HomeTopBar TV：站源名 + 搜索/收藏/历史/设置 同排）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252B33))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("OmniBox · 豆瓣", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.W600)
            }
            Spacer(Modifier.weight(1f))
            TopBar("搜索") { onOpenSearch() }
            Spacer(Modifier.width(6.dp))
            TopBar("收藏") { onOpenFavorites() }
            Spacer(Modifier.width(6.dp))
            TopBar("历史") { onOpenHistory() }
            Spacer(Modifier.width(6.dp))
            TopBar("设置") { onOpenSettings() }
        }

        // 分类 Tab 条（tv-material TabRow：聚焦移动药丸指示器；点击进入对应分类）
        val tabLabels = cats.map { it.first }
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            separator = { Spacer(Modifier.width(8.dp)) },
        ) {
            tabLabels.forEachIndexed { i, label ->
                Tab(
                    selected = selectedTab == i,
                    onFocus = { selectedTab = i },
                    onClick = { selectedTab = i },
                ) {
                    Text(
                        label,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // 主体海报网格（对齐 ubox HomeGrid：Adaptive 190dp + 16dp 间距）
        if (loading || items.isEmpty()) {
            StateBox(loading = loading, error = if (loading) "" else "豆瓣数据加载失败，请检查网络", onRetry = { reload++ })
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 190.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items) { idx, vod ->
                    DoubanPoster(
                        vod = vod,
                        autofocus = idx == 0,
                        badge = cats[selectedTab].first,
                        badgeBg = Color(0xBD0CADE2), // ubox 首页角标紫色
                        note = vod.remarks.ifBlank { null },
                    ) {
                        onOpenDetail("douban", "", vod.name, vod.pic)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(label: String, onClick: () -> Unit) {
    OmniButton(
        label = label, onClick = onClick,
        height = 44.dp, fontSize = 17.sp,
        containerColor = Color(0xFF252B33), scale = 1.05f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    )
}
