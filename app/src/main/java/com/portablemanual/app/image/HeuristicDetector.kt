package com.portablemanual.app.image

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯 Kotlin 的启发式文档检测 —— **降级方案**。
 *
 * 正常情况下走 [OpenCvDetector]；只有在 OpenCV 原生库加载失败时才会用到这里。
 * 保留它而不是删掉，是因为「功能降级」远好于「功能消失」。
 *
 * 思路：生成四路候选掩膜（背景差 / 亮区 / 暗区 / Sobel 边缘），
 * 各自取最大连通域，用 x+y、x-y 极值提取四角，再按
 * 「面积占比 × (对边均衡度 + 边缘支持度)」打分取最优。
 *
 * 它的短板很明确：极值取角是对 approxPolyDP 的粗糙替代，复杂背景上不如 OpenCV。
 */
internal object HeuristicDetector {

    private const val DETECT_MAX_EDGE = 560

    /**
     * 面积占比下限。
     *
     * 原先设 0.15，在真实样本上直接误杀 —— 用户把说明书放在桌上拍，
     * 纸张往往只占画面 10~25%。实测一张「说明书居中放在木桌上」的照片里，
     * 纸张只有 21.8%。门槛设高了，检测再准也永远返回 null。
     */
    private const val MIN_AREA_RATIO = 0.06f
    private const val MAX_AREA_RATIO = 0.85f
    private const val EDGE_FLOOR = 14

    /** Douglas-Peucker 逼近精度，相对多边形周长。0.02 是文档扫描的经典取值。 */
    private const val APPROX_EPSILON_RATIO = 0.02f

    /**
     * 作为降级路径时的分数门槛。
     * 打分公式里已经乘过面积占比，所以量级本身就小（正确检测约 0.15~0.25），
     * 门槛按同量级定，只用来挡掉近乎零分的噪声结果。
     */
    const val FALLBACK_MIN_SCORE = 0.05f

    fun detect(src: Bitmap, minScore: Float = 0f): DocumentScanner.Quad? {
        if (src.width < 32 || src.height < 32) return null

        val longEdge = max(src.width, src.height)
        val factor = if (longEdge > DETECT_MAX_EDGE) DETECT_MAX_EDGE.toFloat() / longEdge else 1f
        val w = max(1, (src.width * factor).roundToInt())
        val h = max(1, (src.height * factor).roundToInt())

        val small = if (factor < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val gray = IntArray(w * h) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val blurred = boxBlur(gray, w, h, 1)
        val magnitude = sobelMagnitude(blurred, w, h)

        val candidates = buildList {
            quadFromMask(backgroundDiffMask(pixels, w, h), w, h)?.let(::add)
            quadFromMask(otsuMask(blurred, w, h, bright = true), w, h)?.let(::add)
            quadFromMask(otsuMask(blurred, w, h, bright = false), w, h)?.let(::add)
            quadFromMask(edgeMask(magnitude, w, h), w, h)?.let(::add)
        }

        val best = candidates
            .map { it to score(it, w, h, magnitude) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first
            ?: return null

        val inv = 1f / factor
        return best.scaled(inv).clampTo(src.width, src.height)
    }

    // ------------------------------------------------------------ 掩膜生成

    /**
     * 背景差掩膜：以最外圈像素的 RGB 中位数为桌面底色，
     * 计算每个像素到该底色的颜色距离，再对距离图做 Otsu 自适应阈值。
     *
     * 比颜色而不是比亮度 —— 浅色桌面 + 浅色纸张的色差通常远大于亮度差。
     */
    private fun backgroundDiffMask(pixels: IntArray, w: Int, h: Int): BooleanArray {
        val ring = 3
        val samples = ArrayList<Int>(ring * 2 * (w + h))
        for (d in 0 until ring) {
            for (x in 0 until w) {
                samples.add(pixels[d * w + x])
                samples.add(pixels[(h - 1 - d) * w + x])
            }
            for (y in 0 until h) {
                samples.add(pixels[y * w + d])
                samples.add(pixels[y * w + (w - 1 - d)])
            }
        }
        val baseR = medianChannel(samples, 16)
        val baseG = medianChannel(samples, 8)
        val baseB = medianChannel(samples, 0)

        val dist = IntArray(w * h) { i ->
            val c = pixels[i]
            val dr = ((c shr 16) and 0xFF) - baseR
            val dg = ((c shr 8) and 0xFF) - baseG
            val db = (c and 0xFF) - baseB
            min(255, (abs(dr) + abs(dg) + abs(db)) / 3)
        }

        val threshold = otsuThreshold(dist)
        val mask = BooleanArray(w * h) { i -> dist[i] > threshold }
        return open(mask, w, h, 1)
    }

    private fun medianChannel(samples: ArrayList<Int>, shift: Int): Int {
        val values = IntArray(samples.size) { (samples[it] shr shift) and 0xFF }
        values.sort()
        return values[values.size / 2]
    }

    private fun otsuMask(gray: IntArray, w: Int, h: Int, bright: Boolean): BooleanArray {
        val t = otsuThreshold(gray)
        val mask = BooleanArray(gray.size) { i ->
            if (bright) gray[i] > t else gray[i] < t
        }
        return open(mask, w, h, 1)
    }

    private fun edgeMask(magnitude: IntArray, w: Int, h: Int): BooleanArray {
        val t = max(EDGE_FLOOR, otsuThreshold(magnitude))
        val mask = BooleanArray(magnitude.size) { i -> magnitude[i] > t }
        return dilate(mask, w, h, 1)
    }

    private fun otsuThreshold(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.coerceIn(0, 255)]++
        val total = values.size
        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * hist[i]

        var sumB = 0.0
        var weightB = 0
        var bestVar = -1.0
        var threshold = 128
        for (t in 0..255) {
            weightB += hist[t]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += t.toDouble() * hist[t]
            val meanB = sumB / weightB
            val meanF = (sumAll - sumB) / weightF
            val between = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > bestVar) {
                bestVar = between
                threshold = t
            }
        }
        return threshold
    }

    // ------------------------------------------------------------ 形态学

    private fun open(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask
        return dilate(erode(mask, w, h, radius), w, h, radius)
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        var cur = mask
        repeat(radius) {
            val next = BooleanArray(cur.size)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    next[i] = cur[i] && cur[i - 1] && cur[i + 1] && cur[i - w] && cur[i + w]
                }
            }
            cur = next
        }
        return cur
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        var cur = mask
        repeat(radius) {
            val next = BooleanArray(cur.size)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    next[i] = cur[i] || cur[i - 1] || cur[i + 1] || cur[i - w] || cur[i + w]
                }
            }
            cur = next
        }
        return cur
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return src
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        val diameter = radius * 2 + 1

        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -radius..radius) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / diameter
                sum += src[row + (x + radius + 1).coerceIn(0, w - 1)]
                sum -= src[row + (x - radius).coerceIn(0, w - 1)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) sum += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / diameter
                sum += tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                sum -= tmp[(y - radius).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }

    private fun sobelMagnitude(gray: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val a = gray[i - w - 1]
                val b = gray[i - w]
                val c = gray[i - w + 1]
                val d = gray[i - 1]
                val f = gray[i + 1]
                val g = gray[i + w - 1]
                val hh = gray[i + w]
                val k = gray[i + w + 1]
                val gx = (c + 2 * f + k) - (a + 2 * d + g)
                val gy = (g + 2 * hh + k) - (a + 2 * b + c)
                out[i] = min(255, (abs(gx) + abs(gy)) / 4)
            }
        }
        return out
    }

    // ------------------------------------------------------------ 连通域与四角

    private class Component(
        val pixels: IntArray,
        val touchesTop: Boolean,
        val touchesBottom: Boolean,
        val touchesLeft: Boolean,
        val touchesRight: Boolean
    ) {
        val count: Int get() = pixels.size
    }

    /**
     * 从掩膜里提取四边形：最大连通域 → 凸包 → **Douglas-Peucker 多边形逼近**。
     *
     * 这是原版最大的短板。旧实现用「x+y / x-y 极值」直接猜四角，
     * 对透视畸变和毛刺都很敏感；现在改成先收成凸包、再做正式的多边形逼近，
     * 与 OpenCV 的 `approxPolyDP` 是同一套数学。
     */
    private fun quadFromMask(mask: BooleanArray, w: Int, h: Int): DocumentScanner.Quad? {
        val comp = largestComponent(mask, w, h) ?: return null
        if (comp.touchesTop && comp.touchesBottom && comp.touchesLeft && comp.touchesRight) return null

        val hull = convexHull(hullCandidates(comp.pixels, w, h))
        if (hull.size < 4) return null

        val perimeter = polygonPerimeter(hull)
        if (perimeter <= 0f) return null

        val approx = approximateClosedPolygon(hull, APPROX_EPSILON_RATIO * perimeter)
        if (approx.size != 4) return null

        val quad = orderQuad(approx) ?: return null

        // 用四边形围出的面积判断，而不是前景像素数 —— 边缘掩膜是「细环」，像素少但围出的面积正是纸张大小
        val ratio = polygonArea(quad.points) / (w.toFloat() * h)
        if (ratio < MIN_AREA_RATIO || ratio > MAX_AREA_RATIO) return null
        return quad
    }

    /**
     * 只保留每行最左 / 最右的像素作为凸包候选。
     * 同一行内其余像素都落在这两点连线上，所以这不改变凸包结果，
     * 却能把候选点从几十万降到几千 —— 否则排序就要几百毫秒。
     */
    private fun hullCandidates(pixels: IntArray, w: Int, h: Int): List<PointF> {
        val rowMin = IntArray(h) { Int.MAX_VALUE }
        val rowMax = IntArray(h) { -1 }
        for (p in pixels) {
            val x = p % w
            val y = p / w
            if (x < rowMin[y]) rowMin[y] = x
            if (x > rowMax[y]) rowMax[y] = x
        }
        val points = ArrayList<PointF>(h * 2)
        for (y in 0 until h) {
            val lo = rowMin[y]
            if (lo == Int.MAX_VALUE) continue
            val hi = rowMax[y]
            points.add(PointF(lo.toFloat(), y.toFloat()))
            if (hi != lo) points.add(PointF(hi.toFloat(), y.toFloat()))
        }
        return points
    }

    /** Andrew's monotone chain 凸包，逆时针返回，顺带剔除共线点。 */
    private fun convexHull(input: List<PointF>): List<PointF> {
        if (input.size < 3) return emptyList()
        val points = input.sortedWith(compareBy({ it.x }, { it.y }))

        val hull = ArrayList<PointF>(points.size * 2)
        for (p in points) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        val lowerSize = hull.size + 1
        for (i in points.indices.reversed()) {
            val p = points[i]
            while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        if (hull.isNotEmpty()) hull.removeAt(hull.size - 1)
        return hull
    }

    private fun cross(o: PointF, a: PointF, b: PointF): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * 对闭合多边形做 Douglas-Peucker 逼近。
     * 先把环从「离起点最远的点」处剪开成两条开放折线，各自简化后再拼回去。
     */
    private fun approximateClosedPolygon(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 4) return points

        var farIndex = 0
        var maxDistance = -1f
        for (i in points.indices) {
            val dx = points[i].x - points[0].x
            val dy = points[i].y - points[0].y
            val d = dx * dx + dy * dy
            if (d > maxDistance) {
                maxDistance = d
                farIndex = i
            }
        }
        if (farIndex == 0) return points

        val first = douglasPeucker(points.subList(0, farIndex + 1), epsilon)
        val second = douglasPeucker(points.subList(farIndex, points.size) + points[0], epsilon)

        // 两条链共享首尾，去掉重复端点
        return first.dropLast(1) + second.dropLast(1)
    }

    private fun douglasPeucker(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))

        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val start = range[0]
            val end = range[1]
            if (end <= start + 1) continue

            var maxDistance = 0f
            var index = -1
            for (i in start + 1 until end) {
                val d = perpendicularDistance(points[i], points[start], points[end])
                if (d > maxDistance) {
                    maxDistance = d
                    index = i
                }
            }
            if (maxDistance > epsilon && index > 0) {
                keep[index] = true
                stack.addLast(intArrayOf(start, index))
                stack.addLast(intArrayOf(index, end))
            }
        }

        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun perpendicularDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1e-6f) {
            val px = p.x - a.x
            val py = p.y - a.y
            return kotlin.math.sqrt(px * px + py * py)
        }
        val numerator = kotlin.math.abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x)
        return numerator / kotlin.math.sqrt(lengthSquared)
    }

    private fun polygonPerimeter(points: List<PointF>): Float {
        var total = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            total += kotlin.math.hypot(a.x - b.x, a.y - b.y)
        }
        return total
    }

    /** 把 4 个点排成 左上 / 右上 / 右下 / 左下。 */
    private fun orderQuad(points: List<PointF>): DocumentScanner.Quad? {
        if (points.size != 4) return null

        var tlIndex = 0
        var trIndex = 0
        var brIndex = 0
        var blIndex = 0
        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE

        points.forEachIndexed { index, p ->
            val sum = p.x + p.y
            val diff = p.x - p.y
            if (sum < minSum) { minSum = sum; tlIndex = index }
            if (sum > maxSum) { maxSum = sum; brIndex = index }
            if (diff > maxDiff) { maxDiff = diff; trIndex = index }
            if (diff < minDiff) { minDiff = diff; blIndex = index }
        }
        if (setOf(tlIndex, trIndex, brIndex, blIndex).size < 4) return null

        return DocumentScanner.Quad(
            points[tlIndex],
            points[trIndex],
            points[brIndex],
            points[blIndex]
        )
    }

    /** 8 邻域连通域标记，栈迭代避免递归爆栈。返回像素数最多的那个连通域。 */
    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): Component? {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val collected = IntArray(mask.size)
        var best: Component? = null
        var bestCount = 0

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var sp = 0
            stack[sp++] = start
            visited[start] = true

            var count = 0
            var top = false
            var bottom = false
            var left = false
            var right = false

            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                collected[count++] = p

                if (y == 0) top = true
                if (y == h - 1) bottom = true
                if (x == 0) left = true
                if (x == w - 1) right = true

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val n = ny * w + nx
                        if (mask[n] && !visited[n]) {
                            visited[n] = true
                            stack[sp++] = n
                        }
                    }
                }
            }

            if (count > bestCount) {
                bestCount = count
                best = Component(collected.copyOf(count), top, bottom, left, right)
            }
        }
        return best
    }

    // ------------------------------------------------------------ 打分

    private fun score(
        quad: DocumentScanner.Quad,
        w: Int,
        h: Int,
        magnitude: IntArray
    ): Float {
        val pts = quad.points
        val areaRatio = polygonArea(pts) / (w.toFloat() * h)
        if (areaRatio < MIN_AREA_RATIO || areaRatio > MAX_AREA_RATIO) return 0f
        if (!isConvex(pts)) return 0f

        val top = distance(pts[0], pts[1])
        val right = distance(pts[1], pts[2])
        val bottom = distance(pts[2], pts[3])
        val left = distance(pts[3], pts[0])
        if (min(min(top, bottom), min(left, right)) < 10f) return 0f

        val eps = 1e-3f
        val horizontal = min(top, bottom) / max(max(top, bottom), eps)
        val vertical = min(left, right) / max(max(left, right), eps)
        val balance = (horizontal + vertical) / 2f

        val edge = edgeSupport(quad, magnitude, w, h)

        return areaRatio * (0.25f + 0.30f * balance + 0.75f * edge)
    }

    /** 沿四边形四条边采样 Sobel 梯度，返回 0..1 的平均强度。 */
    private fun edgeSupport(
        quad: DocumentScanner.Quad,
        magnitude: IntArray,
        w: Int,
        h: Int
    ): Float {
        val pts = quad.points
        var sum = 0.0
        var count = 0
        val steps = 48
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x in 1 until w - 1 && y in 1 until h - 1) {
                    sum += magnitude[y * w + x]
                    count++
                }
            }
        }
        if (count == 0) return 0f
        return ((sum / count) / 255.0).toFloat().coerceIn(0f, 1f)
    }

    private fun polygonArea(pts: List<PointF>): Float {
        var sum = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    private fun isConvex(pts: List<PointF>): Boolean {
        var sign = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val c = pts[(i + 2) % pts.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-4f) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun distance(a: PointF, b: PointF): Float =
        kotlin.math.hypot(a.x - b.x, a.y - b.y)
}
