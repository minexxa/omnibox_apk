package com.omnibox.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.data.Core
import com.omnibox.app.data.HistoryItem
import com.omnibox.app.data.Vod
import com.omnibox.app.data.parseHistory
import com.omnibox.app.ui.Toast
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.components.VodCard
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenDetail: (String, String, String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    fun refresh() {
        loading = true
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        val resp = Core.historyList()
        items = parseHistory(resp)
        error = resp.optString("error", "")
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("观看历史", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TopBarButton("清空历史") {
                scope.launch {
                    Core.historyClear()
                    items = emptyList()
                    Toast.show("已清空历史")
                }
            }
        }

        when {
            loading -> StateBox(loading = true, error = "")
            items.isEmpty() -> StateBox(loading = false, error = error.ifEmpty { "暂无观看历史" })
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().weight(1f),
            ) {
                itemsIndexed(items) { idx, h ->
                    VodCard(
                        vod = Vod(
                            vodId = h.vodId, name = h.name, pic = h.pic,
                            remarks = h.epName.ifEmpty { "第${h.epIndex + 1}集" },
                            source = h.src,
                        ),
                        autofocus = idx == 0,
                        badge = h.src,
                    ) { onOpenDetail(h.src, h.vodId, h.name, h.pic) }
                }
            }
        }
    }
}
