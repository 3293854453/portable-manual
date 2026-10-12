package com.portablemanual.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * 图片解码 / 缩放 / 压缩。
 *
 * 策略：先按 [inSampleSize] 粗采样（避免大图 OOM），再精确缩放到最长边 [maxEdge]，
 * 最后以 JPEG [quality] 落盘。实测可把一张 4000×3000 / 3MB 的原图压到 ~250KB。
 */
object ImageCompressor {

    const val DEFAULT_MAX_EDGE = 1600
    const val DEFAULT_QUALITY = 80

    /** 从 Uri 解码并按最长边缩放，同时按 EXIF 摆正方向。失败返回 null。 */
    fun decodeFromUri(
        context: Context,
        uri: Uri,
        maxEdge: Int = DEFAULT_MAX_EDGE
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val scaled = scaleToMaxEdge(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        return applyExifRotation(context, uri, scaled)
    }

    /** 从本地文件解码并按最长边缩放。 */
    fun decodeFromFile(file: File, maxEdge: Int = DEFAULT_MAX_EDGE): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val scaled = scaleToMaxEdge(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /** 等比缩放到最长边不超过 [maxEdge]；已足够小则原样返回。 */
    fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        val w = max(1, (src.width * ratio).toInt())
        val h = max(1, (src.height * ratio).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** 写入 JPEG，返回文件字节数。 */
    fun saveJpeg(bitmap: Bitmap, out: File, quality: Int = DEFAULT_QUALITY): Long {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), fos)
            fos.flush()
        }
        return out.length()
    }

    fun toJpegBytes(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), bos)
        return bos.toByteArray()
    }
}
