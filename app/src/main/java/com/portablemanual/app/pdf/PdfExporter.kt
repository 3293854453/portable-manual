package com.portablemanual.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.portablemanual.app.image.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * 把多页图片合成一份 A4 PDF。
 *
 * 用系统自带的 [PdfDocument]，零第三方依赖（iText / PdfBox 会让 APK 暴涨）。
 * 每页图片等比缩放居中，留 24pt 白边。
 */
class PdfExporter(private val context: Context) {

    suspend fun export(
        imageFiles: List<File>,
        output: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(imageFiles.isNotEmpty()) { "没有可导出的页面" }

            val document = PdfDocument()
            try {
                imageFiles.forEachIndexed { index, file ->
                    val bitmap = ImageCompressor.decodeFromFile(file, PAGE_MAX_EDGE)
                        ?: throw IllegalStateException("第 ${index + 1} 页图片读取失败")

                    val pageInfo = PdfDocument.PageInfo
                        .Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1)
                        .create()
                    val page = document.startPage(pageInfo)

                    val availableWidth = PAGE_WIDTH - MARGIN * 2
                    val availableHeight = PAGE_HEIGHT - MARGIN * 2
                    val scale = min(
                        availableWidth / bitmap.width.toFloat(),
                        availableHeight / bitmap.height.toFloat()
                    )
                    val drawWidth = bitmap.width * scale
                    val drawHeight = bitmap.height * scale
                    val left = (PAGE_WIDTH - drawWidth) / 2f
                    val top = (PAGE_HEIGHT - drawHeight) / 2f

                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(left, top, left + drawWidth, top + drawHeight),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    document.finishPage(page)
                    bitmap.recycle()

                    onProgress(index + 1, imageFiles.size)
                }

                output.parentFile?.mkdirs()
                FileOutputStream(output).use { document.writeTo(it) }
                output
            } finally {
                document.close()
            }
        }
    }

    companion object {
        /** A4 @ 72dpi */
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 24f
        private const val PAGE_MAX_EDGE = 1600
    }
}
