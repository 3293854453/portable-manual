package com.portablemanual.app.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 说明书自动裁剪的对外门面：检测纸张四角 → 透视变换拉正。
 *
 * 检测采用**双引擎**策略：
 *   1. 首选 [OpenCvDetector]（Canny + findContours + approxPolyDP 的业界标准管线）；
 *   2. OpenCV 原生库加载失败时，退回 [HeuristicDetector]（纯 Kotlin，无依赖）。
 *
 * 这样既拿到成熟算法的准确度，又保证「原生库出问题」不会让功能整个消失。
 *
 * 透视变换仍用 Android 自带的 `Matrix.setPolyToPoly()`：
 * 与 OpenCV 的 `warpPerspective` 数学等价，没必要为此多绕一层。
 */
object DocumentScanner {

    data class Quad(
        val tl: PointF,
        val tr: PointF,
        val br: PointF,
        val bl: PointF
    ) {
        fun toFloatArray(): FloatArray = floatArrayOf(
            tl.x, tl.y,
            tr.x, tr.y,
            br.x, br.y,
            bl.x, bl.y
        )

        val points: List<PointF> get() = listOf(tl, tr, br, bl)

        fun scaled(factor: Float): Quad = Quad(
            PointF(tl.x * factor, tl.y * factor),
            PointF(tr.x * factor, tr.y * factor),
            PointF(br.x * factor, br.y * factor),
            PointF(bl.x * factor, bl.y * factor)
        )

        fun clampTo(width: Int, height: Int): Quad {
            fun c(p: PointF) = PointF(
                p.x.coerceIn(0f, (width - 1).toFloat()),
                p.y.coerceIn(0f, (height - 1).toFloat())
            )
            return Quad(c(tl), c(tr), c(br), c(bl))
        }

        /** 替换第 [index] 个角（0=左上 1=右上 2=右下 3=左下）。 */
        fun replace(index: Int, point: PointF): Quad = when (index) {
            0 -> copy(tl = point)
            1 -> copy(tr = point)
            2 -> copy(br = point)
            else -> copy(bl = point)
        }
    }

    /** 全图四角，作为手动裁剪的初始值。 */
    fun fullQuad(width: Int, height: Int): Quad = Quad(
        PointF(0f, 0f),
        PointF((width - 1).toFloat(), 0f),
        PointF((width - 1).toFloat(), (height - 1).toFloat()),
        PointF(0f, (height - 1).toFloat())
    )

    /** 当前检测是否跑在 OpenCV 上。 */
    val usingOpenCv: Boolean get() = OpenCvRuntime.isAvailable

    /** 当前生效的裁剪引擎名称，供设置页展示。 */
    val engineName: String
        get() = when {
            HedEdgeDetector.isReady -> "HED 神经网络"
            OpenCvRuntime.isAvailable -> "OpenCV（传统）"
            else -> "纯 Kotlin（降级）"
        }

    /**
     * 自动检测。失败返回 null，交给 UI 让用户手动拖角。
     * 内部会做重活，**必须在后台线程调用**。
     */
    fun detect(src: Bitmap): Quad? {
        if (src.width < 32 || src.height < 32) return null

        if (OpenCvRuntime.ensureInitialized()) {
            val quad = runCatching { OpenCvDetector.detect(src) }.getOrNull()
            if (quad != null) return quad
        }

        // OpenCV 不可用，或它没找到合法四边形 —— 再给启发式一次机会，
        // 但门槛调高，避免在 OpenCV 判定「无文档」时硬塞一个错的结果。
        return runCatching {
            HeuristicDetector.detect(src, HeuristicDetector.FALLBACK_MIN_SCORE)
        }.getOrNull()
    }

    /** 按四角做透视拉正，输出尺寸由四边长度推算。 */
    fun warp(src: Bitmap, quad: Quad, maxOutputEdge: Int = 2400): Bitmap {
        val wTop = distance(quad.tl, quad.tr)
        val wBottom = distance(quad.bl, quad.br)
        val hLeft = distance(quad.tl, quad.bl)
        val hRight = distance(quad.tr, quad.br)

        var outW = max(wTop, wBottom).roundToInt()
        var outH = max(hLeft, hRight).roundToInt()
        outW = outW.coerceIn(1, maxOutputEdge)
        outH = outH.coerceIn(1, maxOutputEdge)

        return warpTo(src, quad, outW, outH)
    }

    fun warpTo(src: Bitmap, quad: Quad, outW: Int, outH: Int): Bitmap {
        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )
        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(quad.toFloatArray(), 0, dst, 0, 4)
        if (!ok) return src.copy(Bitmap.Config.ARGB_8888, false)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** 自动裁剪一步到位：检测 + 拉正。检测失败则原样返回。 */
    fun autoCrop(src: Bitmap): Bitmap {
        val quad = detect(src) ?: return src
        return warp(src, quad)
    }

    private fun distance(a: PointF, b: PointF): Float =
        kotlin.math.hypot(a.x - b.x, a.y - b.y)
}
