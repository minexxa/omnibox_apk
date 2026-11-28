@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.omnibox.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface as TvSurface

/**
 * TV 可聚焦容器 —— 基于 Compose for TV（androidx.tv:tv-material）的 Surface：
 * 聚焦时放大 + 描边 + 柔和光晕，遥控器 DPAD 中心键触发 onClick。
 * 参数对齐 ubox 的 TvFocusable，便于忠实还原其 TV 页面：
 * - radius/borderColor/showBorder 控制焦点描边；
 * - scale=1f 可关闭放大，只保留描边（ubox 详情页按钮即用此方式）；
 * - onFocusChanged 供按钮在获得焦点时改变自身配色。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
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
    LaunchedEffect(focused) { onFocusChanged?.invoke(focused) }
    LaunchedEffect(autofocus, enabled) {
        if (autofocus && enabled) {
            runCatching { requester.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(radius)
    TvSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusRequester(requester),
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = Color.White,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = scale,
            pressedScale = scale,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = if (showBorder) {
                Border(border = BorderStroke(2.dp, borderColor), shape = shape)
            } else {
                Border.None
            },
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = if (showBorder) {
                Glow(elevationColor = borderColor.copy(alpha = 0.25f), elevation = 6.dp)
            } else {
                Glow.None
            },
        ),
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
    TvPosterCard(modifier = modifier.width(160.dp), autofocus = autofocus, onClick = onClick) {
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
    TvPosterCard(modifier = sizeMod, autofocus = autofocus, onClick = onClick) {
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
                    OmniButton("重试", onRetry, Modifier.padding(top = 16.dp), autofocus = true)
                }
            }
        }
    }
}

/** 顶部导航栏按钮。 */
@Composable
fun TopBarButton(label: String, autofocus: Boolean = false, onClick: () -> Unit) {
    OmniButton(
        label = label, onClick = onClick, autofocus = autofocus,
        height = 44.dp, fontSize = 15.sp, contentPadding = PaddingValues(horizontal = 18.dp),
    )
}

/**
 * TV 海报卡片：基于 tv-material 的 Card（聚焦放大 + 描边 + 光晕），
 * 内容区为 ColumnScope，调用方直接放 Column / Box 均可。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPosterCard(
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    scale: Float = 1.05f,
    radius: Dp = 10.dp,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(autofocus) {
        if (autofocus) runCatching { requester.requestFocus() }
    }
    val shape = RoundedCornerShape(radius)
    Card(
        onClick = onClick,
        modifier = modifier.focusRequester(requester),
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = AppColors.FocusFill,
            focusedContentColor = Color.White,
            pressedContainerColor = AppColors.FocusFill,
            pressedContentColor = Color.White,
        ),
        scale = CardDefaults.scale(focusedScale = scale, pressedScale = scale),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, AppColors.FocusBorder), shape = shape),
        ),
        glow = CardDefaults.glow(
            focusedGlow = Glow(elevationColor = AppColors.FocusBorder.copy(alpha = 0.25f), elevation = 6.dp),
        ),
        content = content,
    )
}

/**
 * 通用 TV 按钮：基于 tv-material 的 Button。
 * 默认灰底，聚焦 / selected 时高亮；聚焦有白色描边。文字颜色由按钮状态决定（聚焦色可单独指定）。
 * - scale：聚焦放大倍数，默认 1f（只描边）；
 * - selectedBorder：selected 时常驻白色描边（用于左侧筛选栏）；
 * - align：文字对齐，默认居中。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OmniButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    autofocus: Boolean = false,
    height: Dp = 48.dp,
    radius: Dp = 8.dp,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight? = null,
    containerColor: Color = AppColors.ButtonBg,
    focusedContainerColor: Color = AppColors.Accent,
    selectedContainerColor: Color = AppColors.Accent,
    focusedContentColor: Color = Color.White,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    scale: Float = 1f,
    selectedBorder: Boolean = false,
    align: TextAlign = TextAlign.Center,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(autofocus, enabled) {
        if (autofocus && enabled) runCatching { requester.requestFocus() }
    }
    val shape = RoundedCornerShape(radius)
    val focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape)
    val base = if (selected) selectedContainerColor else containerColor
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height).focusRequester(requester),
        contentPadding = contentPadding,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = base,
            contentColor = Color.White,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = focusedContentColor,
            disabledContainerColor = Color(0x33333333),
            disabledContentColor = Color.Gray,
        ),
        scale = ButtonDefaults.scale(focusedScale = scale, pressedScale = scale),
        border = ButtonDefaults.border(
            border = if (selected && selectedBorder) Border(border = BorderStroke(1.5.dp, Color.White), shape = shape) else Border.None,
            focusedBorder = focusedBorder,
        ),
        glow = ButtonDefaults.glow(),
    ) {
        Text(
            label,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
            modifier = if (align == TextAlign.Center) Modifier else Modifier.fillMaxWidth(),
        )
    }
}

/** TV 列表项：基于 tv-material 的 ListItem（设置页的菜单 / 单选 / 开关）。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OmniListItem(
    headline: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val supportingSlot: (@Composable () -> Unit)? =
        if (supporting != null) ({ Text(supporting, fontSize = 13.sp, color = Color(0xFFB0BEC5)) }) else null
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(headline, fontSize = 16.sp) },
        modifier = modifier.fillMaxWidth(),
        supportingContent = supportingSlot,
        leadingContent = leading,
        trailingContent = trailing,
        shape = ListItemDefaults.shape(shape),
        colors = ListItemDefaults.colors(
            containerColor = AppColors.CardBg,
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF2A3036),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFF2A3036),
            pressedContentColor = Color.White,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        border = ListItemDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White), shape = shape),
        ),
    )
}
