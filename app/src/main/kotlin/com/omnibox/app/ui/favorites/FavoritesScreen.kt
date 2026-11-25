package com.omnibox.app.ui.favorites

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
import com.omnibox.app.data.Vod
import com.omnibox.app.ui.Toast
import com.omnibox.app.ui.components.StateBox
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.components.VodCard

@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenDetail: (String, String, String, String) -> Unit,
) {
    val prefs = remember { OmniBoxApp.prefs() }
    var keys by remember { mutableStateOf(prefs.favorites().toList().sorted()) }

    // key -> [name, pic, src, id]
    val favs = keys.mapNotNull { key ->
        val meta = prefs.favoriteMeta(key)
        if (meta.size >= 4) key to meta else null
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TopBarButton("返回") { onBack() }
            Text("我的收藏", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp))
            Spacer(Modifier.weight(1f))
            if (keys.isNotEmpty()) {
                TopBarButton("清空收藏") {
                    keys.forEach { prefs.toggleFavoriteByKey(it); prefs.removeFavoriteMeta(it) }
                    keys = emptyList()
                    Toast.show("已清空收藏")
                }
            }
        }

        if (favs.isEmpty()) {
            StateBox(loading = false, error = "暂无收藏，可在播放页点「收藏」添加")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().weight(1f),
            ) {
                itemsIndexed(favs) { idx, (_, meta) ->
                    val name = meta[0]; val pic = meta[1]; val src = meta[2]; val id = meta[3]
                    VodCard(
                        vod = Vod(vodId = id, name = name, pic = pic, source = src),
                        autofocus = idx == 0,
                        badge = src,
                    ) { onOpenDetail(src, id, name, pic) }
                }
            }
        }
    }
}
