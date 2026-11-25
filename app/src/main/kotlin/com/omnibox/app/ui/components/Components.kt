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

/** TV 可聚焦容器：焦点时放大 + 白色描边 + 底色，遥控器点击触发 onClick。 */
@Composable
fun TvFocusable(
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    enabled: Boolean = true,
    scale: Float = 1.05f,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val requester = remember { FocusRequester() }
    val anim by animateFloatAsState(if (focused && enabled) scale else 1f, label = "focus")
    LaunchedEffect(autofocus) {
        if (autofocus && enabled) {
            runCatching { requester.requestFocus() }
        }
    }
    Surface(
        modifier = modifier
            .scale(anim)
            .focusRequester(requester)
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { onClick() }
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (focused && enabled) Modifier.border(2.dp, AppColors.FocusBorder, RoundedCornerShape(10.dp))
                else Modifier
            ),
        color = if (focused && enabled) AppColors.FocusFill else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
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
 * 豆瓣热播海报卡片 —— 完全对齐 ubox 的 VodPoster 显示：
 * 圆角海报(aspect 0.68) + 左上角评分角标 + 底部黑底白字片名栏。
 */
@Composable
fun DoubanPoster(
    vod: Vod,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    onClick: () -> Unit,
) {
    TvFocusable(modifier = modifier.width(150.dp), autofocus = autofocus, onClick = onClick) {
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
            // 左上角评分角标（ubox: 半透明黑底 "x.x 分"）
            if (vod.score.isNotEmpty()) {
                Text(
                    "${vod.score} 分", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(Color(0x8C000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 底部黑底白字片名栏（ubox: 居中、单行省略）
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x8C000000))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            ) {
                Text(
                    vod.name, color = Color.White, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
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
