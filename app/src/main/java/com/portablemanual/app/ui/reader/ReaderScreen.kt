package com.portablemanual.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.ui.rememberContainerViewModel
import java.io.File
import kotlin.math.max

/**
 * 全屏阅读器。
 *
 * 交互：
 *  - 左右滑动翻页（HorizontalPager）
 *  - 双指缩放 / 拖动（仅在放大后允许平移）
 *  - 单击画面显隐顶部信息条
 *  - 缩放到 1x 时自动回正
 */
@Composable
fun ReaderScreen(
    container: AppContainer,
    deviceId: Long,
    startPage: Int,
    onBack: () -> Unit
) {
    val viewModel = rememberContainerViewModel(key = "reader-$deviceId") {
        ReaderViewModel(it.libraryRepository, deviceId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var chromeVisible by remember { mutableStateOf(true) }

    if (state.pages.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("没有可阅读的页面", color = Color.White.copy(alpha = 0.7f))
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startPage.coerceIn(0, state.pages.lastIndex)
    ) { state.pages.size }

    LaunchedEffect(pagerState.currentPage) {
        chromeVisible = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 8.dp
        ) { index ->
            val page = state.pages[index]
            ZoomablePage(
                file = File(state.imageRoot, page.fileName),
                onTap = { chromeVisible = !chromeVisible }
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifBlank { "说明书" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = "第 ${pagerState.currentPage + 1} / ${state.pages.size} 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.14f),
                contentColor = Color.White
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.ZoomOutMap,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.width(16.dp)
                    )
                    Text(
                        "双指放大 · 左右滑动翻页",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(file: File, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scaleState = rememberUpdatedState(scale)
    val offsetState = rememberUpdatedState(offset)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformAndTap(
                    currentScale = { scaleState.value },
                    onTransform = { pan, zoom ->
                        val newScale = (scaleState.value * zoom).coerceIn(1f, 6f)
                        scale = newScale
                        offset = if (newScale > 1.02f) offsetState.value + pan else Offset.Zero
                    },
                    onTap = onTap
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(file)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

/**
 * 同时处理「双指缩放 / 放大后拖动」与「单击显隐工具栏」。
 *
 * 关键点：**单指且未放大时必须完全不消费事件**，否则 HorizontalPager 收不到横向拖动，
 * 翻页就彻底失效了。所以这里只在两种情况接管：
 *   1. 屏幕上出现两根以上手指（缩放）
 *   2. 已经放大过（此时单指拖动是平移图片，不是翻页）
 *
 * 为什么要手写而不是分别用 detectTapGestures + detectTransformGestures：
 * 后者会消费 down 事件，导致前者直接被打断 —— 两个独立 modifier 叠在一起必然二选一。
 */
private suspend fun PointerInputScope.detectTransformAndTap(
    currentScale: () -> Float,
    onTransform: (pan: Offset, zoom: Float) -> Unit,
    onTap: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val startTime = System.currentTimeMillis()
        var moved = false
        var maxPointers = 1
        var panAccum = Offset.Zero

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            maxPointers = max(maxPointers, pressed)

            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            panAccum += pan

            val shouldHandle = maxPointers > 1 || currentScale() > 1.02f
            if (shouldHandle) {
                moved = true
                onTransform(pan, zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (panAccum.getDistance() > viewConfiguration.touchSlop) {
                // 单指横滑：放行给 Pager，但这一下不再算点击
                moved = true
            }
        }

        if (!moved && maxPointers == 1 && System.currentTimeMillis() - startTime < 400) {
            onTap()
        }
    }
}
