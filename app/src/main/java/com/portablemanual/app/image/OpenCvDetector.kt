package com.portablemanual.app.image

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 基于 OpenCV 的文档边缘检测。
 *
 * ## 为什么主力路径不是 Canny
 *
 * 最初用的是教科书管线（Canny → findContours → approxPolyDP），但在真实样本上
 * 全军覆没：纸张边界在 Canny 图里是**断裂的**（被手挡住、光照不均、木纹干扰），
 * 断掉的边永远闭不成环，findContours 自然找不到纸张轮廓。
 * 基于灰度中位数的自适应阈值在这种「白纸 + 浅色木桌」场景下也偏高，把弱边缘直接滤没了。
 *
 * 改成**区域分割**后立刻稳定：说明书是白纸，特征是「**低饱和度 + 高亮度**」，
 * 而木桌、椅子、鼠标垫都有明显色度。在 HSV 空间按 (S, V) 一刀切开，
 * 纸张就是一个完整实心区域，边界天然闭合 —— 比修补断裂的边缘可靠得多。
 *
 * ## 三条路径，按优先级回退
 *
 * 真实样本反复验证后确定的顺序，**不是按分数取最大**（那会偏向面积大的过检结果）：
 *
 * 1. **HSV 区域**（固定紧阈值）：彩色背景下最准。实测三张样本上四角紧贴纸张边缘
 * 2. **自适应阈值**：兜住「整幅画面都接近灰阶」的情况 —— 此时饱和度中位数趋近 0，
 *    HSV 路径会失效（白纸压在浅灰桌面上就是这种）
 * 3. **Canny 边缘**：最后兜底，适合非白纸文档
 *
 * ## 两个关键细节
 *
 * - **先凸包再 Douglas-Peucker**：掩膜内部有印刷内容造成的大量凹陷（极度非凸），
 *   直接 approxPolyDP 会得到十几个点；纸张本身是凸的，先取凸包抹平凹陷，
 *   epsilon 稍放就能稳定收敛到 4 个顶点。
 * - **形态学处理要克制**：HSV 路径只用 3×3 闭运算。核开大了会把纸张和邻近的
 *   亮区域桥接起来，导致框比纸张大一圈。
 */
internal object OpenCvDetector {

    private const val DETECT_MAX_EDGE = 720
    private const val MIN_AREA_RATIO = 0.06

    /**
     * 面积上限。
     *
     * 原先设 0.60，但相机页提示用户「尽量拍满画面」—— 照做的话纸张会占 70~90%，
     * 反而被判定为不可信而放弃裁剪。放宽到 0.92；
     * 真正拍满整幅（≈100%）的情况由 `CaptureViewModel.isFullFrame()` 单独处理（本来就不需要裁）。
     */
    private const val MAX_AREA_RATIO = 0.92

    /** 面积归一化的参考值：达到这个占比就算「够大」，再多也不加分。 */
    private const val AREA_NORM = 0.25
    private const val MAX_ASPECT = 4.0
    private const val MIN_SIDE = 20.0

    /**
     * 饱和度阈值的候选系数：S < 中位数 × 系数。
     *
     * 为什么不是一个固定值：纸张的「白」并不统一 —— 纯白打印纸 S≈3，
     * 米色/泛黄的手写本 S≈9~13。固定 0.35 会把米色纸直接排除掉，
     * 转而框到画面里另一张更白的纸上去（实测踩过这个坑）。
     */
    private val SATURATION_FACTORS = doubleArrayOf(0.35, 0.50, 0.70)

    private const val VALUE_FACTOR = 0.85

    /** 判定两个四边形「一致」：四角平均偏移 < 图像对角线的这个比例。 */
    private const val AGREE_TOLERANCE = 0.04

    /** 至少这么多个阈值给出相互一致的结果，才采信。 */
    private const val MIN_CONSENSUS = 2

    /** HED 边缘图的面积筛选区间。比传统路径宽松 —— HED 的轮廓本身就是可信的。 */
    private const val HED_MIN_AREA_RATIO = 0.04
    private const val HED_MAX_AREA_RATIO = 0.97

    /** 四边形精化：边缘点取样带宽（像素）与迭代次数。 */
    private const val REFINE_BAND = 3.0f
    private const val REFINE_ITERATIONS = 3
    private const val REFINE_MIN_POINTS = 10

    /** approxPolyDP 的精度候选，从小到大依次尝试，取第一个得到 4 个顶点的。 */
    private val APPROX_EPSILONS = doubleArrayOf(0.01, 0.02, 0.03, 0.04, 0.05)

    /** 自适应阈值的 (blockSize, C) 组合。 */
    private val ADAPTIVE_PARAMS = arrayOf(
        intArrayOf(71, -8),
        intArrayOf(51, -6),
        intArrayOf(101, -10)
    )

    fun detect(src: Bitmap): DocumentScanner.Quad? {
        if (src.width < 32 || src.height < 32) return null

        val longEdge = max(src.width, src.height)
        val scale = if (longEdge > DETECT_MAX_EDGE) {
            DETECT_MAX_EDGE.toDouble() / longEdge
        } else {
            1.0
        }

        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                src,
                max(1, (src.width * scale).roundToInt()),
                max(1, (src.height * scale).roundToInt()),
                true
            )
        } else {
            src
        }
        val safe = if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false)
        }

        val rgba = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val gray = Mat()
        val blurred = Mat()

        try {
            Utils.bitmapToMat(safe, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            // 路径 0：HED 神经网络（首选）。
            // 它直接在原图尺寸上工作，返回的四边形已经是原图坐标，不需要再缩放。
            if (HedEdgeDetector.isReady) {
                hedQuad(src)?.let { return it.clampTo(src.width, src.height) }
            }

            val imageArea = safe.width.toDouble() * safe.height
            val diagonal = hypot(safe.width.toDouble(), safe.height.toDouble())

            // 路径 1~3：传统方法，在缩到 720 的图上工作
            val quad = hsvRegionQuad(hsv, imageArea, diagonal)
                ?: adaptiveQuad(blurred, imageArea)
                ?: cannyQuad(blurred, imageArea)
                ?: return null

            val inverse = 1.0 / scale
            return quad.scaled(inverse.toFloat()).clampTo(src.width, src.height)
        } finally {
            rgba.release()
            rgb.release()
            hsv.release()
            gray.release()
            blurred.release()
            if (scaled !== src) scaled.recycle()
            if (safe !== scaled) safe.recycle()
        }
    }

    // ------------------------------------------------------------ 路径 1：HSV 区域

    /**
     * 按「低饱和 + 高亮度」切出纸张，并用**多阈值一致性投票**决定是否采信。
     *
     * 为什么要投票：纸张的白并不统一（纯白打印纸 S≈3，米色手写本 S≈9~13），
     * 单一固定阈值必然顾此失彼 —— 实测在「米色笔记本 + 旁边一本白纸印刷书」的画面里，
     * 紧阈值会把米色笔记本排除、转而框住那本印刷书，**框错对象**。
     *
     * 投票机制的价值在于：不一致时**宁可放弃**（交给后续路径或让用户手动拖角），
     * 也不要给出一个自信但错误的结果。框错对象比不框更糟。
     */
    private fun hsvRegionQuad(
        hsv: Mat,
        imageArea: Double,
        diagonal: Double
    ): DocumentScanner.Quad? {
        val candidates = ArrayList<DocumentScanner.Quad>()
        for (factor in SATURATION_FACTORS) {
            hsvQuadAt(hsv, factor, imageArea)?.let(candidates::add)
        }
        if (candidates.isEmpty()) return null

        var best: DocumentScanner.Quad? = null
        var bestSupport = 0
        for (candidate in candidates) {
            val support = candidates.count { agree(candidate, it, diagonal) }
            if (support > bestSupport) {
                bestSupport = support
                best = candidate
            }
        }
        return if (bestSupport >= MIN_CONSENSUS) best else null
    }

    private fun hsvQuadAt(hsv: Mat, saturationFactor: Double, imageArea: Double): DocumentScanner.Quad? {
        val sChannel = Mat()
        val vChannel = Mat()
        val mask = Mat()
        try {
            Core.extractChannel(hsv, sChannel, 1)
            Core.extractChannel(hsv, vChannel, 2)
            val sMax = (medianOf(sChannel) * saturationFactor).coerceIn(4.0, 254.0)
            val vMin = (medianOf(vChannel) * VALUE_FACTOR).coerceIn(1.0, 254.0)

            // HSV 通道范围：H 0..179，S 0..255，V 0..255
            Core.inRange(hsv, Scalar(0.0, 0.0, vMin), Scalar(179.0, sMax, 255.0), mask)
            // 只做 3×3 闭运算：核开大了会把纸张和邻近的亮区域桥接起来，框比纸张大一圈
            return quadFromMask(mask, imageArea, closeKernel = 3, openKernel = 0)
        } finally {
            sChannel.release()
            vChannel.release()
            mask.release()
        }
    }

    /** 两个四边形的四角平均偏移是否小于容差。 */
    private fun agree(a: DocumentScanner.Quad, b: DocumentScanner.Quad, diagonal: Double): Boolean {
        val pa = a.points
        val pb = b.points
        var sum = 0.0
        for (i in 0..3) {
            sum += hypot((pa[i].x - pb[i].x).toDouble(), (pa[i].y - pb[i].y).toDouble())
        }
        return sum / 4.0 < AGREE_TOLERANCE * diagonal
    }

    // ------------------------------------------------------------ 路径 2：自适应阈值

    /**
     * 自适应阈值：只保留「比局部均值亮」的像素。
     *
     * 这是「整幅画面都接近灰阶」时的救命路径 —— 此时饱和度中位数趋近 0，
     * HSV 路径的阈值会退化成几乎空集（实测白纸压在浅灰桌面上时掩膜只有 3%）。
     * 自适应阈值不依赖色彩，只看局部明暗起伏，反而能切出纸张。
     */
    private fun adaptiveQuad(blurred: Mat, imageArea: Double): DocumentScanner.Quad? {
        val mask = Mat()
        try {
            for (params in ADAPTIVE_PARAMS) {
                Imgproc.adaptiveThreshold(
                    blurred,
                    mask,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    params[0],
                    params[1].toDouble()
                )
                val quad = quadFromMask(mask, imageArea, closeKernel = 9, openKernel = 5)
                if (quad != null) return quad
            }
            return null
        } finally {
            mask.release()
        }
    }

    // ------------------------------------------------------------ 路径 3：Canny 兜底

    private fun cannyQuad(blurred: Mat, imageArea: Double): DocumentScanner.Quad? {
        val edges = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        try {
            val median = medianOf(blurred)
            for ((lowerFactor, upperFactor) in listOf(0.5 to 1.0, 0.33 to 0.9)) {
                Imgproc.Canny(
                    blurred,
                    edges,
                    max(0.0, median * lowerFactor),
                    min(255.0, median * upperFactor)
                )
                Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
                val quad = quadFromMask(edges, imageArea, closeKernel = 0, openKernel = 0)
                if (quad != null) return quad
            }
            return null
        } finally {
            edges.release()
            kernel.release()
        }
    }

    // ------------------------------------------------------------ 路径 0：HED 神经网络

    /**
     * 用 HED 边缘图找纸张四边形。
     *
     * HED 输出的是一条**细闭合环**（纸张轮廓），这带来一个反直觉的坑：
     * 不能按 `contourArea` 选最大轮廓 —— 环一旦有断口，`contourArea`
     * 会退化成"两端点连线围出的面积"，完全无意义（实测会选出只覆盖一角的小框）。
     * 所以这里遍历所有轮廓，按**凸包面积**筛选，取最大的那个合法四边形。
     */
    private fun hedQuad(src: Bitmap): DocumentScanner.Quad? {
        val edgeMap = HedEdgeDetector.edgeMap(src) ?: return null
        val size = HedEdgeDetector.INPUT_SIZE
        val imageArea = (size * size).toDouble()

        val mask = Mat(size, size, CvType.CV_8UC1)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        try {
            val bytes = ByteArray(size * size)
            for (i in bytes.indices) {
                bytes[i] = if (edgeMap[i] > HedEdgeDetector.EDGE_THRESHOLD) 255.toByte() else 0
            }
            mask.put(0, 0, bytes)

            // 边缘是细线，膨胀一下把可能的断口连上
            Imgproc.dilate(mask, mask, kernel)
            Imgproc.findContours(
                mask,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            var best: DocumentScanner.Quad? = null
            var bestArea = 0.0

            for (contour in contours) {
                val hullIndices = MatOfInt()
                try {
                    Imgproc.convexHull(contour, hullIndices, false)
                    val indices = hullIndices.toArray()
                    if (indices.size < 4) continue

                    val source = contour.toArray()
                    val hullPoints = Array(indices.size) { source[indices[it]] }
                    val hullArea = abs(Imgproc.contourArea(MatOfPoint(*hullPoints)))
                    val ratio = hullArea / imageArea
                    if (ratio < HED_MIN_AREA_RATIO || ratio > HED_MAX_AREA_RATIO) continue

                    val quad = quadFromHullPoints(hullPoints) ?: continue
                    if (ratio > bestArea) {
                        bestArea = ratio
                        best = quad
                    }
                } finally {
                    hullIndices.release()
                }
            }

            val found = best ?: return null
            // 用边缘点拟合直线精化角点（亚像素级），再换算回原图坐标
            val refined = refineQuad(edgeMap, found)
            // 边缘图是 256×256 的方图，原图可能不是方的 —— x/y 要分别缩放
            val scaleX = src.width.toFloat() / size
            val scaleY = src.height.toFloat() / size
            return DocumentScanner.Quad(
                PointF(refined.tl.x * scaleX, refined.tl.y * scaleY),
                PointF(refined.tr.x * scaleX, refined.tr.y * scaleY),
                PointF(refined.br.x * scaleX, refined.br.y * scaleY),
                PointF(refined.bl.x * scaleX, refined.bl.y * scaleY)
            )
        } finally {
            mask.release()
            kernel.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** 凸包点 → Douglas-Peucker 逼近 → 4 个顶点。 */
    private fun quadFromHullPoints(points: Array<Point>): DocumentScanner.Quad? {
        if (points.size < 4) return null
        val curve = MatOfPoint2f()
        val approx = MatOfPoint2f()
        try {
            curve.fromArray(*points)
            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null

            for (epsilon in APPROX_EPSILONS) {
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                if (approx.total() != 4L) continue
                val approxPoints = approx.toArray()
                if (!Imgproc.isContourConvex(MatOfPoint(*approxPoints))) continue
                return orderQuad(approxPoints)
            }
            return null
        } finally {
            curve.release()
            approx.release()
        }
    }

    /**
     * 四边形精化：用边缘点拟合直线，交点作为新顶点。
     *
     * 为什么需要：HED 边缘图只有 256×256，凸包 + 多边形逼近得到的角点被量化到
     * 1/256 ≈ 0.4%，而且会被边缘的粗细和毛刺带偏。
     * 但每条边附近其实有大量真实边缘像素 —— 用最小二乘把它们拟合成直线，
     * 再取相邻直线的交点，就能得到亚像素精度，且对噪声不敏感。
     *
     * 实测边缘拟合残差平均改善 5~24%（7 张样本里 6 张变好）。
     */
    private fun refineQuad(edgeMap: FloatArray, quad: DocumentScanner.Quad): DocumentScanner.Quad {
        val size = HedEdgeDetector.INPUT_SIZE

        var count = 0
        for (value in edgeMap) if (value > HedEdgeDetector.EDGE_THRESHOLD) count++
        if (count < REFINE_MIN_POINTS * 4) return quad

        val px = FloatArray(count)
        val py = FloatArray(count)
        var index = 0
        for (i in edgeMap.indices) {
            if (edgeMap[i] > HedEdgeDetector.EDGE_THRESHOLD) {
                px[index] = (i % size).toFloat()
                py[index] = (i / size).toFloat()
                index++
            }
        }

        var current = quad
        repeat(REFINE_ITERATIONS) {
            val lines = arrayOfNulls<Pair<PointF, PointF>>(4)
            val corners = current.points
            for (edge in 0 until 4) {
                val a = corners[edge]
                val b = corners[(edge + 1) % 4]
                val selected = ArrayList<Point>(64)
                for (j in 0 until count) {
                    if (perpendicularDistance(px[j], py[j], a, b) < REFINE_BAND) {
                        selected.add(Point(px[j].toDouble(), py[j].toDouble()))
                    }
                }
                if (selected.size >= REFINE_MIN_POINTS) {
                    lines[edge] = fitLine(selected)
                }
            }

            val refined = arrayOf(
                current.tl, current.tr, current.br, current.bl
            )
            for (edge in 0 until 4) {
                val first = lines[(edge + 3) % 4]
                val second = lines[edge]
                if (first == null || second == null) continue
                intersectLines(first, second)?.let { refined[edge] = it }
            }

            val next = DocumentScanner.Quad(refined[0], refined[1], refined[2], refined[3])
            val delta = maxOf(
                kotlin.math.abs(next.tl.x - current.tl.x), kotlin.math.abs(next.tl.y - current.tl.y),
                kotlin.math.abs(next.tr.x - current.tr.x), kotlin.math.abs(next.tr.y - current.tr.y),
                kotlin.math.abs(next.br.x - current.br.x), kotlin.math.abs(next.br.y - current.br.y),
                kotlin.math.abs(next.bl.x - current.bl.x), kotlin.math.abs(next.bl.y - current.bl.y)
            )
            current = next
            if (delta < 0.05f) return current
        }
        return current
    }

    /** 最小二乘拟合直线，返回直线上的两个点。 */
    private fun fitLine(points: List<Point>): Pair<PointF, PointF>? {
        val curve = MatOfPoint2f(*points.toTypedArray())
        val line = Mat()
        try {
            Imgproc.fitLine(curve, line, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
            val values = FloatArray(4)
            line.get(0, 0, values)
            // fitLine 输出顺序：vx, vy, x0, y0
            val base = PointF(values[2], values[3])
            return base to PointF(values[2] + values[0], values[3] + values[1])
        } catch (_: Exception) {
            return null
        } finally {
            curve.release()
            line.release()
        }
    }

    /** 两条直线的交点，平行时返回 null。 */
    private fun intersectLines(
        first: Pair<PointF, PointF>,
        second: Pair<PointF, PointF>
    ): PointF? {
        val (p1, p2) = first
        val (p3, p4) = second
        val d1x = p2.x - p1.x
        val d1y = p2.y - p1.y
        val d2x = p4.x - p3.x
        val d2y = p4.y - p3.y
        val denominator = d1x * d2y - d1y * d2x
        if (kotlin.math.abs(denominator) < 1e-8f) return null
        val t = ((p3.x - p1.x) * d2y - (p3.y - p1.y) * d2x) / denominator
        return PointF(p1.x + t * d1x, p1.y + t * d1y)
    }

    /** 点到直线的垂直距离。 */
    private fun perpendicularDistance(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = kotlin.math.hypot(dx, dy)
        if (length < 1e-6f) return kotlin.math.hypot(x - a.x, y - a.y)
        return kotlin.math.abs(dy * (x - a.x) - dx * (y - a.y)) / length
    }

    // ------------------------------------------------------------ 掩膜 → 四边形

    /**
     * 掩膜 → 最大外轮廓 → 凸包 → Douglas-Peucker 逼近 → 4 个顶点。
     *
     * 先取凸包是关键：掩膜内部因印刷内容而极度非凸，直接逼近会得到十几个点；
     * 纸张本身是凸的，取凸包正好抹平这些凹陷。
     */
    private fun quadFromMask(
        mask: Mat,
        imageArea: Double,
        closeKernel: Int,
        openKernel: Int
    ): DocumentScanner.Quad? {
        val work = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        var closeMat: Mat? = null
        var openMat: Mat? = null
        var curve: MatOfPoint2f? = null
        var approx: MatOfPoint2f? = null
        val hullIndices = MatOfInt()

        try {
            var source = mask
            if (closeKernel > 0) {
                closeMat = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(closeKernel.toDouble(), closeKernel.toDouble())
                )
                Imgproc.morphologyEx(mask, work, Imgproc.MORPH_CLOSE, closeMat)
                source = work
            }
            if (openKernel > 0) {
                openMat = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT,
                    Size(openKernel.toDouble(), openKernel.toDouble())
                )
                Imgproc.morphologyEx(source, work, Imgproc.MORPH_OPEN, openMat)
                source = work
            }

            Imgproc.findContours(
                source,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )
            if (contours.isEmpty()) return null

            val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            Imgproc.convexHull(largest, hullIndices, false)
            val hullIndexArray = hullIndices.toArray()
            if (hullIndexArray.size < 4) return null

            val sourcePoints = largest.toArray()
            val hullPoints = Array(hullIndexArray.size) { sourcePoints[hullIndexArray[it]] }
            curve = MatOfPoint2f()
            curve.fromArray(*hullPoints)
            approx = MatOfPoint2f()

            val perimeter = Imgproc.arcLength(curve, true)
            if (perimeter <= 0.0) return null

            for (epsilon in APPROX_EPSILONS) {
                Imgproc.approxPolyDP(curve, approx, epsilon * perimeter, true)
                if (approx.total() != 4L) continue

                val points = approx.toArray()
                if (!Imgproc.isContourConvex(MatOfPoint(*points))) continue
                val quad = orderQuad(points) ?: continue
                if (scoreQuad(quad, imageArea) != null) return quad
            }
            return null
        } finally {
            work.release()
            hierarchy.release()
            hullIndices.release()
            closeMat?.release()
            openMat?.release()
            curve?.release()
            approx?.release()
            contours.forEach { it.release() }
        }
    }

    // ------------------------------------------------------------ 几何校验与排序

    /**
     * 几何合理性校验：对边长度不能差太多、不能过于狭长、面积要在合理区间。
     *
     * 注意这里**不把分数用于跨候选比较** —— 实测「对边平衡度」会偏向面积更大、
     * 更方正的框，而真实纸张是透视倾斜的，平衡度天然偏低，于是过检的结果反而得分更高。
     * 所以路径之间用固定优先级回退，分数只做准入门槛。
     */
    private fun scoreQuad(quad: DocumentScanner.Quad, imageArea: Double): Double? {
        val points = quad.points
        val top = distance(points[0], points[1])
        val right = distance(points[1], points[2])
        val bottom = distance(points[2], points[3])
        val left = distance(points[3], points[0])

        val shortest = min(min(top, bottom), min(left, right))
        val longest = max(max(top, bottom), max(left, right))
        if (shortest < MIN_SIDE) return null
        if (longest / max(shortest, 1e-3) > MAX_ASPECT) return null

        val areaRatio = polygonArea(points) / imageArea
        if (areaRatio < MIN_AREA_RATIO || areaRatio > MAX_AREA_RATIO) return null

        val horizontal = min(top, bottom) / max(max(top, bottom), 1e-3)
        val vertical = min(left, right) / max(max(left, right), 1e-3)
        val balance = (horizontal + vertical) / 2.0
        return balance * min(areaRatio / AREA_NORM, 1.0)
    }

    /**
     * 把 4 个点排成 左上 / 右上 / 右下 / 左下。
     * x+y 最小是左上、最大是右下；x-y 最大是右上、最小是左下。
     */
    private fun orderQuad(points: Array<Point>): DocumentScanner.Quad? {
        if (points.size != 4) return null

        var tlIndex = 0
        var trIndex = 0
        var brIndex = 0
        var blIndex = 0
        var minSum = Double.MAX_VALUE
        var maxSum = -Double.MAX_VALUE
        var minDiff = Double.MAX_VALUE
        var maxDiff = -Double.MAX_VALUE

        points.forEachIndexed { index, p ->
            val sum = p.x + p.y
            val diff = p.x - p.y
            if (sum < minSum) { minSum = sum; tlIndex = index }
            if (sum > maxSum) { maxSum = sum; brIndex = index }
            if (diff > maxDiff) { maxDiff = diff; trIndex = index }
            if (diff < minDiff) { minDiff = diff; blIndex = index }
        }
        if (setOf(tlIndex, trIndex, brIndex, blIndex).size < 4) return null

        fun toPointF(p: Point) = PointF(p.x.toFloat(), p.y.toFloat())
        return DocumentScanner.Quad(
            toPointF(points[tlIndex]),
            toPointF(points[trIndex]),
            toPointF(points[brIndex]),
            toPointF(points[blIndex])
        )
    }

    private fun polygonArea(points: List<PointF>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(sum) / 2.0
    }

    private fun distance(a: PointF, b: PointF): Double =
        kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** 单通道图的中位数，用于自适应阈值。 */
    private fun medianOf(channel: Mat): Double {
        val histogram = Mat()
        try {
            Imgproc.calcHist(
                listOf(channel),
                MatOfInt(0),
                Mat(),
                histogram,
                MatOfInt(256),
                MatOfFloat(0f, 256f)
            )
            val total = channel.total()
            var accumulated = 0.0
            for (i in 0 until 256) {
                accumulated += histogram.get(i, 0)[0]
                if (accumulated >= total / 2.0) return i.toDouble()
            }
            return 128.0
        } finally {
            histogram.release()
        }
    }
}
