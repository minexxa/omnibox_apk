package com.omnibox.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.theme.AppColors

private val HOT_WORDS = listOf(
    "热播电影", "最新电视剧", "动漫", "悬疑", "喜剧", "科幻", "综艺", "纪录片"
)
private val KEYS = listOf(
    "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
    "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
    "A", "S", "D", "F", "G", "H", "J", "K", "L",
    "Z", "X", "C", "V", "B", "N", "M"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    autoSearch: Boolean = false,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val prefs = remember { OmniBoxApp.prefs() }
    var text by remember { mutableStateOf(initialQuery) }
    var history by remember { mutableStateOf(prefs.searchHistory()) }

    // 首屏自动搜索（由首页热播条目跳转触发）
    LaunchedEffect(autoSearch) {
        if (autoSearch && initialQuery.isNotBlank()) {
            prefs.addSearchHistory(initialQuery)
            onSubmit(initialQuery)
        }
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        prefs.addSearchHistory(q)
        history = prefs.searchHistory()
        onSubmit(q.trim())
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        // 顶部输入框
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TopBarButton("返回") { onBack() }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("输入片名搜索", color = Color.Gray) },
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1C2024),
                    unfocusedContainerColor = Color(0xFF1C2024),
                    focusedIndicatorColor = AppColors.Accent,
                    cursorColor = AppColors.Accent,
                ),
            )
            TopBarButton("搜索") { submit(text) }
        }

        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // 左：屏显键盘
            Column(
                Modifier.fillMaxHeight().weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                KEYS.chunked(10).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { k -> KeyButton(k, Modifier.weight(1f)) { text += k } }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyButton("删除", Modifier.weight(1f)) { if (text.isNotEmpty()) text = text.dropLast(1) }
                    KeyButton("清空", Modifier.weight(1f)) { text = "" }
                    KeyButton("搜索", Modifier.weight(1f)) { submit(text) }
                }
            }

            // 中：热门 / 历史词
            Column(
                Modifier.fillMaxHeight().weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
            ) {
                Text("热门搜索", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HOT_WORDS.forEach { w -> WordPill(w) { submit(w) } }
                }
                Text("搜索历史", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                if (history.isEmpty()) {
                    Text("暂无历史", color = Color.Gray, fontSize = 14.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { w -> WordPill(w) { submit(w) } }
                    }
                    TopBarButton("清空历史") {
                        prefs.clearSearchHistory()
                        history = emptyList()
                    }
                }
            }

            // 右：搜索范围说明（内核 search 支持 src=all 聚合所有启用脚本）
            Column(
                Modifier.fillMaxHeight().weight(0.7f).padding(start = 16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                Text("搜索说明", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "聚合搜索会并发查询所有已启用的影视脚本。\n\n" +
                        "如结果为空，请到 设置 → 脚本管理 导入 JS 文件并启用。",
                    color = Color(0xFFB0BEC5), fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    com.omnibox.app.ui.components.TvFocusable(modifier = modifier, onClick = onClick) {
        Text(
            label, color = Color.White, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun WordPill(word: String, onClick: () -> Unit) {
    com.omnibox.app.ui.components.TvFocusable(onClick = onClick) {
        Text(
            word, color = Color.White, fontSize = 14.sp,
            modifier = Modifier
                .background(Color(0xFF263238), androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
