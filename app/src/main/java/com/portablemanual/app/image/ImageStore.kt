package com.portablemanual.app.image

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.UUID

/**
 * 图片文件仓库。所有图片落在 `filesDir/images/{deviceId}/{uuid}.jpg`。
 *
 * 数据库里只存**相对路径**（如 `12/a1b2.jpg`），因此备份包跨设备还原后依然有效。
 */
class ImageStore(private val context: Context) {

    val root: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun fileFor(relativeName: String): File = File(root, relativeName)

    fun exists(relativeName: String): Boolean = fileFor(relativeName).exists()

    /** 生成一个新的相对路径（同时建好目录）。 */
    fun newRelativeName(deviceId: Long): String {
        File(root, deviceId.toString()).mkdirs()
        return "$deviceId/${UUID.randomUUID().toString().replace("-", "")}.jpg"
    }

    fun saveJpeg(bitmap: Bitmap, relativeName: String, quality: Int): Long =
        ImageCompressor.saveJpeg(bitmap, fileFor(relativeName), quality)

    /** 直接写入原始字节（备份还原时用，避免重复编解码）。 */
    fun writeBytes(relativeName: String, bytes: ByteArray): Long {
        val target = fileFor(relativeName)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target.length()
    }

    fun readBytes(relativeName: String): ByteArray = fileFor(relativeName).readBytes()

    fun delete(relativeName: String) {
        runCatching {
            val f = fileFor(relativeName)
            if (f.exists()) f.delete()
        }
    }

    /** 删除某台设备的所有图片（设备被删时调用）。 */
    fun deleteDeviceFolder(deviceId: Long) {
        runCatching { File(root, deviceId.toString()).deleteRecursively() }
    }

    fun totalBytes(): Long = runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        .getOrDefault(0L)

    fun clearAll() {
        runCatching { root.listFiles()?.forEach { it.deleteRecursively() } }
    }

    /** 清理数据库里已不存在记录的孤儿图片，返回释放的字节数。 */
    fun pruneOrphans(referenced: Set<String>): Long {
        var freed = 0L
        runCatching {
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                if (rel !in referenced) {
                    freed += f.length()
                    f.delete()
                }
            }
            // 顺手清掉空目录
            root.listFiles()?.filter { it.isDirectory && it.listFiles()?.isEmpty() != false }
                ?.forEach { it.delete() }
        }
        return freed
    }

    companion object {
        private const val DIR_NAME = "images"
    }
}
