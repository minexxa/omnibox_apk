package com.omnibox.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.data.Vod
import com.omnibox.app.data.rememberImage
import com.omnibox.app.ui.theme.AppColors

/**
 * TV 可聚焦容器：焦点时描边（默认也轻微放大），遥控器 DPAD 中心键触发 onClick。
 * 参数对齐 ubox 的 TvFocusable，便于忠实还原其 TV 页面：
 * - radius/borderColor/showBorder 控制焦点描边；
 * - scale=1f 可关闭放大，只保留描边（ubox 详情页按钮即用此方式）；
 * - onFocusChanged 供按钮在获得焦点时改变自身配色。
 */
@Composable
fun TvFocusable(
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    enabled: Boolean = true,
    scale: Float = 1.05f,
    radius: androidx.compose.ui.unit.Dp = 10.dp,
    borderColor: Color = AppColors.FocusBorder,
    showBorder: Boolean = true,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val requester = remember { FocusRequester() }
    val anim by animateFloatAsState(if (focused && enabled) scale else 1f, label = "focus")
    LaunchedEffect(focused) { onFocusChanged?.invoke(focused) }
    LaunchedEffect(autofocus) {
        if (autofocus && enabled) {
            runCatching { requester.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(radius)
    Surface(
        modifier = modifier
            .scale(anim)
            .focusRequester(requester)
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { onClick() }
            .clip(shape)
            .then(
                if (focused && enabled && showBorder) Modifier.border(2.dp, borderColor, shape)
                else Modifier
            ),
        color = if (focused && enabled) AppColors.FocusFill else Color.Transparent,
        shape = shape,
    ) { content() }
}

/** 章节标题。 */
@Composable
fun SectionTitle(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        trailing?.invoke()
    }
}

/** 海报卡片（搜索结果 / 分类列表 / 历史 / 收藏通用）。 */
@Composable
fun VodCard(
    vod: Vod,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    TvFocusable(modifier = modifier.width(160.dp), autofocus = autofocus, onClick = onClick) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.CardBg),
                contentAlignment = Alignment.Center,
            ) {
                val img = rememberImage(vod.pic)
                if (img != null) {
                    Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(vod.name.take(6), color = Color.Gray, fontSize = 16.sp, textAlign = TextAlign.Center)
                }
                if (!badge.isNullOrEmpty()) {
                    Text(
                        badge, color = Color.White, fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color(0x99000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (vod.score.isNotEmpty()) {
                    Text(
                        vod.score, color = AppColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0x99000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                vod.name, color = Color.White, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
            if (vod.remarks.isNotEmpty()) {
                Text(vod.remarks, color = Color(0xFFB0BEC5), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * ubox 风格海报卡片 —— 精确对齐 ubox VodPoster：
 * 圆角海报(aspect 0.68) + 左上角角标(badge/badgeBg) + 底部备注角标(note) + 黑底白字片名栏。
 * - badge：左上角角标文字（首页传"豆瓣热播"，搜索结果页传来源站名）
 * - badgeBg：左上角角标底色（首页用 ubox 的紫色 0xBD0CADE2）
 * - note：片名栏上方的黑底备注（如 "8.7 分"）
 * - width：null = fillMaxWidth（网格模式，由 GridCells 决定列宽）；非 null = 固定宽度（搜索结果页 150dp）
 */
@Composable
fun DoubanPoster(
    vod: Vod,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    badge: String? = null,
    badgeBg: Color = Color(0x8C000000),
    note: String? = null,
    width: androidx.compose.ui.unit.Dp? = null,
    onClick: () -> Unit,
) {
    val sizeMod = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    TvFocusable(modifier = sizeMod, autofocus = autofocus, onClick = onClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.CardBg),
        ) {
            val img = rememberImage(vod.pic)
            if (img != null) {
                Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    vod.name.take(8), color = Color.Gray, fontSize = 15.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center),
                )
            }
            // 左上角角标（ubox Badge：圆角4dp、padding 5x2、14sp、可配底色）
            val topBadge = badge?.takeIf { it.isNotBlank() }
            if (topBadge != null) {
                Text(
                    topBadge, color = Color.White, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(badgeBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            // 底部：备注角标（黑底半透明）+ 黑底白字片名栏（均对齐 ubox）
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                val bottomNote = note?.takeIf { it.isNotBlank() }
                    ?: vod.remarks.takeIf { it.isNotEmpty() }
                if (bottomNote != null) {
                    Text(
                        bottomNote, color = Color.White, fontSize = 14.sp, maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                            .background(Color(0x8A000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x8A000000))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    Text(
                        vod.name, color = Color.White, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 通用加载/错误占位。 */
@Composable
fun StateBox(loading: Boolean, error: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = AppColors.Accent)
            error.isNotEmpty() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = Color(0xFFCF6679), fontSize = 15.sp)
                if (onRetry != null) {
                    TvFocusable(modifier = Modifier.padding(top = 16.dp), onClick = onRetry) {
                        Text("重试", color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

/** 顶部导航栏按钮。 */
@Composable
fun TopBarButton(label: String, autofocus: Boolean = false, onClick: () -> Unit) {
    TvFocusable(autofocus = autofocus, onClick = onClick) {
        Text(
            label, color = Color.White, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
