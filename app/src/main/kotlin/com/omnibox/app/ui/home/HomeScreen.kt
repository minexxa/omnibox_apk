package com.omnibox.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.data.Core
import com.omnibox.app.data.DoubanHot
import com.omnibox.app.data.HomeData
import com.omnibox.app.data.Vod
import com.omnibox.app.data.parseHome
import com.omnibox.app.data.rememberImage
import com.omnibox.app.ui.components.DoubanPoster
import com.omnibox.app.ui.components.SectionTitle
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.theme.AppColors
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenDetail: (String, String, String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 首页影视数据来自 ubox 豆瓣源（直连 movie.douban.com），Banner/分类仍用内核
    var data by remember { mutableStateOf<HomeData?>(null) }
    var hot by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        hot = DoubanHot.fetch(ctx)
        val resp = Core.home()
        if (resp.optBoolean("ok", true)) {
            data = parseHome(resp)
        }
        // 豆瓣热播为空且内核也无数据才报错；豆瓣失败但内核有数据时仍展示分类
        if (hot.isEmpty() && data == null) {
            error = "豆瓣热播加载失败，请检查网络"
        }
        loading = false
    }

    if (loading) { StateBox(loading = true, error = ""); return }
    if (data == null && hot.isEmpty()) {
        StateBox(loading = false, error = error, onRetry = null)
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // 顶部导航
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OmniBox", color = AppColors.Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(32.dp))
            TopBarButton("搜索") { onOpenSearch() }
            TopBarButton("历史") { onOpenHistory() }
            TopBarButton("收藏") { onOpenFavorites() }
            Spacer(Modifier.weight(1f))
            TopBarButton("设置") { onOpenSettings() }
        }

        LazyRow(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) {
            // Banner 轮播（占满一列宽度，内核数据，失败则显示占位）
            item {
                BannerCarousel(data?.banners ?: emptyList())
                Spacer(Modifier.width(24.dp))
            }
            // 分类入口
            item {
                Column(Modifier.width(200.dp)) {
                    SectionTitle("分类浏览")
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (data?.classes ?: emptyList()).take(6).forEach { c ->
                            TopBarButton(c.typeName) { onOpenBrowse(c.typeId, c.typeName) }
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
            }
            // 豆瓣热播（ubox 豆瓣源 + ubox 卡片样式）
            if (hot.isNotEmpty()) {
                item {
                    Column(Modifier.width(1100.dp)) {
                        SectionTitle("豆瓣热播")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            itemsIndexed(hot) { idx, vod ->
                                DoubanPoster(vod = vod, autofocus = idx == 0) {
                                    onOpenDetail("douban", "", vod.name, vod.pic)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BannerCarousel(banners: List<com.omnibox.app.data.Banner>) {
    if (banners.isEmpty()) {
        Box(
            Modifier.size(720.dp, 300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C2024)),
            contentAlignment = Alignment.Center,
        ) { Text("豆瓣热播", color = Color.Gray, fontSize = 20.sp) }
        return
    }
    val state = rememberPagerState(pageCount = { banners.size })
    LaunchedEffect(Unit) {
        while (true) {
            delay(6000)
            state.animateScrollToPage((state.currentPage + 1) % banners.size)
        }
    }
    Box(Modifier.size(720.dp, 300.dp).clip(RoundedCornerShape(12.dp))) {
        HorizontalPager(state = state) { page ->
            val b = banners[page]
            Box(Modifier.fillMaxSize()) {
                val img = rememberImage(b.backgroundImage)
                if (img != null) {
                    Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF263238)))
                }
                Box(
                    Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC000000))
                        )
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                    Text(b.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (b.actors.isNotEmpty()) {
                        Text(b.actors, color = Color(0xFFDDDDDD), fontSize = 13.sp, maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (b.description.isNotEmpty()) {
                        Text(b.description, color = Color(0xFFBBBBBB), fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) {
            repeat(banners.size) { i ->
                Box(
                    Modifier.padding(3.dp).size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (state.currentPage == i) AppColors.Accent else Color(0x88FFFFFF))
                )
            }
        }
    }
}

