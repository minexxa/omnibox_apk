package com.omnibox.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.omnibox.app.ui.browse.BrowseScreen
import com.omnibox.app.ui.favorites.FavoritesScreen
import com.omnibox.app.ui.history.HistoryScreen
import com.omnibox.app.ui.home.HomeScreen
import com.omnibox.app.ui.search.SearchScreen
import com.omnibox.app.ui.search.SearchResultsScreen
import com.omnibox.app.ui.settings.SettingsScreen
import com.omnibox.app.ui.vod.VodScreen

/** 应用路由（自绘返回栈，对照 ubox Route）。 */
sealed class Route {
    data object Main : Route()
    data object Settings : Route()
    data class Browse(val cat: String, val title: String) : Route()
    data class Search(val initialQuery: String = "", val autoSearch: Boolean = false) : Route()
    data class SearchResults(val query: String) : Route()
    data class Vod(val src: String, val vodId: String, val name: String, val pic: String = "") : Route()
    data object History : Route()
    data object Favorites : Route()
}

/** 全局 Snackbar。 */
object Toast {
    private val _msg = mutableStateOf<String?>(null)
    val msg get() = _msg
    fun show(s: String) { _msg.value = s }
    fun consume() { _msg.value = null }
}

@Composable
fun OmniBoxTvApp() {
    val backStack = remember { mutableStateListOf<Route>(Route.Main) }
    val snackbar = remember { SnackbarHostState() }
    val toast by Toast.msg

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.size - 1)
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            Toast.consume()
        }
    }

    /**
     * 打开详情：豆瓣条目（src=douban / id 为空 / 以 msearch: 开头）按片名转搜索，
     * 影视源条目进播放详情页。
     */
    val openDetail: (String, String, String, String) -> Unit = { src, id, name, pic ->
        if (src == "douban" || id.isBlank() || id.startsWith("msearch:")) {
            backStack.add(Route.Search(initialQuery = name, autoSearch = name.isNotBlank()))
        } else {
            backStack.add(Route.Vod(src, id, name, pic))
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val route = backStack.last()) {
            Route.Main -> HomeScreen(
                onOpenDetail = openDetail,
                onOpenSearch = { backStack.add(Route.Search()) },
                onOpenBrowse = { cat, title -> backStack.add(Route.Browse(cat, title)) },
                onOpenHistory = { backStack.add(Route.History) },
                onOpenFavorites = { backStack.add(Route.Favorites) },
                onOpenSettings = { backStack.add(Route.Settings) },
            )

            Route.Settings -> SettingsScreen(onBack = { backStack.removeAt(backStack.size - 1) })

            is Route.Browse -> BrowseScreen(
                cat = route.cat, title = route.title,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )

            is Route.Search -> SearchScreen(
                initialQuery = route.initialQuery,
                autoSearch = route.autoSearch,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onSubmit = { q ->
                    val idx = backStack.indexOfFirst { it is Route.Search }
                    if (idx >= 0) backStack[idx] = Route.Search(initialQuery = q, autoSearch = false)
                    backStack.add(Route.SearchResults(q))
                },
            )

            is Route.SearchResults -> SearchResultsScreen(
                query = route.query,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )

            is Route.Vod -> VodScreen(
                src = route.src, vodId = route.vodId, name = route.name, pic = route.pic,
                onBack = { backStack.removeAt(backStack.size - 1) },
                onSwitchSource = { newSrc, newId, newName ->
                    backStack.removeAt(backStack.size - 1)
                    backStack.add(Route.Vod(newSrc, newId, newName))
                },
            )

            Route.History -> HistoryScreen(
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )

            Route.Favorites -> FavoritesScreen(
                onBack = { backStack.removeAt(backStack.size - 1) },
                onOpenDetail = openDetail,
            )
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
