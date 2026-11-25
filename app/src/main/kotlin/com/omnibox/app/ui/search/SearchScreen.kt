package com.omnibox.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.ui.components.TvFocusable
import com.omnibox.app.ui.theme.AppColors

/**
 * TV 搜索页（忠实对齐 ubox / 原版 activity_search 三栏布局）：
 *   左栏 = 指定搜索源 + 只读输入框 + 搜索/清空 + 屏显键盘（远程搜索/删除 + A-Z + 0-9）
 *   中栏 = 热门 / 猜你想搜（输入首字母后优先显示本地历史前缀匹配）
 *   右栏 = 历史（标题 + 清空 + 3 列胶囊网格）
 * 遥控器下键即可从输入框移到键盘按键；所有操作均可用 DPAD 中心键触发。
 */
private val HOT_WORDS = listOf(
    "热播电影", "最新电视剧", "动漫", "悬疑", "喜剧", "科幻", "综艺", "纪录片"
)

@Composable
fun SearchScreen(
    initialQuery: String = "",
    autoSearch: Boolean = false,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val prefs = remember { OmniBoxApp.prefs() }
    var input by remember { mutableStateOf(initialQuery) }
    var history by remember { mutableStateOf(prefs.searchHistory()) }
    val inputFocus = remember { FocusRequester() }

    // 首页热播条目跳转：初始词立即搜索进结果页
    LaunchedEffect(autoSearch) {
        if (autoSearch && initialQuery.isNotBlank()) {
            prefs.addSearchHistory(initialQuery)
            onSubmit(initialQuery.trim())
            // 注意：不 finish 本页，返回时路由会回到这里，输入词保留（对照原版 etSearch）
        }
    }

    fun submit(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        prefs.addSearchHistory(t)
        history = prefs.searchHistory()
        onSubmit(t)
    }

    // 输入首字母后，中栏由「热门」切换为本地历史前缀匹配的「猜你想搜」
    val suggestions = remember(input, history) {
        if (input.isBlank()) emptyList()
        else history.filter { it.contains(input, ignoreCase = true) }.take(20)
    }

    LaunchedEffect(Unit) {
        runCatching { inputFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // ===== 左栏：源 + 输入框 + 搜索/清空 + 屏显键盘 =====
        Box(Modifier.width(430.dp)) {
            TvLeftPanel(
                input = input,
                inputFocus = inputFocus,
                onInputChange = { input = it },
                onSearch = { submit(input) },
                onClear = { input = "" },
                onBack = onBack,
            )
        }
        VerticalDivider()
        // ===== 中栏：热门 / 猜你想搜 =====
        Box(Modifier.width(260.dp)) {
            TvWordPanel(
                title = if (suggestions.isNotEmpty()) "猜你想搜" else "热  门",
                words = if (suggestions.isNotEmpty()) suggestions else HOT_WORDS,
                onPick = { submit(it) },
            )
        }
        VerticalDivider()
        // ===== 右栏：历史（3 列胶囊网格）=====
        Box(Modifier.weight(1f)) {
            TvHistoryPanel(
                history = history,
                onPick = { submit(it) },
                onClear = { prefs.clearSearchHistory(); history = emptyList() },
            )
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Color(0x33FFFFFF)),
    )
}

/** 屏显键盘按键：首行 远程搜索/删除各半行，其后每行 6 个字符（ubox keyboardKeys）。 */
private val keyboardChars: List<String> = buildList {
    addAll(('A'..'Z').map { it.toString() })
    addAll((0..9).map { it.toString() })
}

@Composable
private fun TvLeftPanel(
    input: String,
    inputFocus: FocusRequester,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var inputFocused by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        item {
            // 搜索范围说明（内核 search 走 src=all 聚合全部已启用脚本）
            Text("全部源站", color = Color(0xFFBBBBBB), fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            // 只读输入框：焦点态双层描边（外 3dp 半透明黑 + 内 2dp 白），输入全靠下方键盘
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .fillMaxWidth()
                    .border(
                        width = 3.dp,
                        color = if (inputFocused) Color(0x66000000) else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                    )
                    .border(
                        width = 2.dp,
                        color = if (inputFocused) Color.White else Color.Gray.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .focusRequester(inputFocus)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    readOnly = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 18.sp, color = Color.White,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { inputFocused = it.isFocused },
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                "请输入要搜索的内容",
                                color = Color.Gray,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TvButton("搜索", height = 56.dp, fontSize = 18, selected = true,
                    modifier = Modifier.weight(1f), onTap = onSearch)
                Spacer(Modifier.width(10.dp))
                TvButton("清空", height = 56.dp, fontSize = 18,
                    modifier = Modifier.weight(1f), onTap = onClear)
            }
            Spacer(Modifier.height(12.dp))
        }
        // 首行：远程搜索 / 删除
        item {
            Row(Modifier.fillMaxWidth()) {
                KeyBtn("远程搜索", wide = true, modifier = Modifier.weight(1f), onTap = onSearch)
                KeyBtn("删除", wide = true, modifier = Modifier.weight(1f),
                    onTap = { if (input.isNotEmpty()) onInputChange(input.dropLast(1)) })
            }
        }
        // A-Z 0-9，每行 6 键
        items(keyboardChars.chunked(6)) { rowKeys ->
            Row(Modifier.fillMaxWidth()) {
                for (k in rowKeys) {
                    KeyBtn(k, wide = false, modifier = Modifier.weight(1f),
                        onTap = { onInputChange(input + k) })
                }
                repeat(6 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KeyBtn(
    key: String,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Box(modifier.padding(3.dp)) {
        TvFocusable(radius = 6.dp, modifier = Modifier.fillMaxWidth(), onClick = onTap) {
            Box(
                Modifier
                    .height(56.dp)
                    .fillMaxWidth()
                    .background(Color(0x6C3D3D3D), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    key,
                    fontSize = if (wide) 16.sp else 20.sp,
                    fontWeight = if (wide) FontWeight.W600 else FontWeight.Normal,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun TvWordPanel(title: String, words: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
        if (words.isEmpty()) {
            Text("暂无词语", color = Color.Gray, modifier = Modifier.padding(12.dp))
            return
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            items(words) { w ->
                Box(Modifier.padding(vertical = 3.dp)) {
                    WordPill(w, onPick = { onPick(w) })
                }
            }
        }
    }
}

@Composable
private fun TvHistoryPanel(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("历  史", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TvButton("清空", height = 40.dp, fontSize = 14, onTap = onClear)
            }
        }
        if (history.isEmpty()) {
            Text("暂无搜索历史", color = Color.Gray, modifier = Modifier.padding(12.dp))
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(history) { w ->
                WordPill(w, height = 46.dp, fontSize = 15, onPick = { onPick(w) })
            }
        }
    }
}

@Composable
private fun WordPill(
    word: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 50.dp,
    fontSize: Int = 16,
) {
    TvFocusable(radius = 20.dp, modifier = modifier, onClick = onPick) {
        Box(
            Modifier
                .height(height)
                .fillMaxWidth()
                .background(Color(0x6C3D3D3D), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                word, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color.White, fontSize = fontSize.sp,
            )
        }
    }
}

/** ubox 风格 TV 按钮：默认灰底，获得焦点或 selected 时高亮。 */
@Composable
private fun TvButton(
    label: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 48.dp,
    fontSize: Int = 16,
    selected: Boolean = false,
    onTap: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    TvFocusable(
        modifier = modifier.height(height),
        radius = 8.dp,
        scale = 1f,
        onFocusChanged = { focused = it },
        onClick = onTap,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected || focused) AppColors.Accent else Color(0x6C3D3D3D)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = fontSize.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center)
        }
    }
}
