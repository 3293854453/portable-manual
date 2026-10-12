package com.portablemanual.app.ui.capture

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import com.portablemanual.app.image.DocumentScanner
import kotlin.math.hypot
import kotlin.math.min

/**
 * 裁剪编辑器：显示原图 + 可拖拽的四角，四角之外压暗。
 *
 * 交互细节（这些才是「好用」的来源）：
 *  - 触摸点离角 60dp 内才算抓到这个角，避免误触
 *  - 拖拽中不做任何动画/重组抖动，跟手
 *  - 角落有圆形手柄 + 白色描边，在深色和浅色照片上都看得见
 *  - 四角连线用虚线，一眼区分「自动识别的边界」
 */
@Composable
fun CropEditor(
    bitmap: Bitmap,
    quad: DocumentScanner.Quad,
    onQuadChange: (DocumentScanner.Quad) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentQuad by rememberUpdatedState(quad)
    val currentOnChange by rememberUpdatedState(onQuadChange)
    var draggingIndex by remember { mutableIntStateOf(-1) }

    val density = LocalDensity.current
    val touchSlopPx = with(density) { 44.dp.toPx() }

    BoxWithConstraints(modifier) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        val scale = min(
            containerWidth / bitmap.width.toFloat(),
            containerHeight / bitmap.height.toFloat()
        )
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val offsetX = (containerWidth - drawWidth) / 2f
        val offsetY = (containerHeight - drawHeight) / 2f

        fun toView(p: PointF) = Offset(offsetX + p.x * scale, offsetY + p.y * scale)
        fun toImage(o: Offset) = PointF(
            ((o.x - offsetX) / scale).coerceIn(0f, (bitmap.width - 1).toFloat()),
            ((o.y - offsetY) / scale).coerceIn(0f, (bitmap.height - 1).toFloat())
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, scale, offsetX, offsetY) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val points = currentQuad.points.map { toView(it) }
                            draggingIndex = points.indexOfFirst { point ->
                                hypot(point.x - start.x, point.y - start.y) <= touchSlopPx
                            }
                        },
                        onDragEnd = { draggingIndex = -1 },
                        onDragCancel = { draggingIndex = -1 },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val index = draggingIndex
                            if (index < 0) return@detectDragGestures
                            val origin = currentQuad.points[index]
                            val moved = PointF(
                                origin.x + dragAmount.x / scale,
                                origin.y + dragAmount.y / scale
                            )
                            val clamped = PointF(
                                moved.x.coerceIn(0f, (bitmap.width - 1).toFloat()),
                                moved.y.coerceIn(0f, (bitmap.height - 1).toFloat())
                            )
                            currentOnChange(currentQuad.replace(index, clamped))
                        }
                    )
                }
        ) {
            val imageRect = Rect(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight)

            // 1) 画原图
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isFilterBitmap = true
                    isAntiAlias = true
                }
                canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(
                        imageRect.left,
                        imageRect.top,
                        imageRect.right,
                        imageRect.bottom
                    ),
                    paint
                )
            }

            // 2) 四角之外压暗
            val quadPath = Path().apply {
                val p0 = toView(currentQuad.tl)
                val p1 = toView(currentQuad.tr)
                val p2 = toView(currentQuad.br)
                val p3 = toView(currentQuad.bl)
                moveTo(p0.x, p0.y)
                lineTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                close()
            }

            clipPath(quadPath, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(imageRect.left, imageRect.top),
                    size = Size(imageRect.width, imageRect.height)
                )
            }

            // 3) 边框虚线
            drawPath(
                path = quadPath,
                color = Color.White,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(14f, 10f),
                        0f
                    )
                )
            )

            // 4) 四角手柄
            currentQuad.points.forEachIndexed { index, point ->
                val viewPoint = toView(point)
                val active = index == draggingIndex
                val radius = if (active) 12.dp.toPx() else 9.dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = radius + 6.dp.toPx(),
                    center = viewPoint
                )
                drawCircle(
                    color = Color.White,
                    radius = radius,
                    center = viewPoint
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = radius,
                    center = viewPoint,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}
