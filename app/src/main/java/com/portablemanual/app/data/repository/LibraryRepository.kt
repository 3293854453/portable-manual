package com.portablemanual.app.data.repository

import android.graphics.Bitmap
import com.portablemanual.app.data.local.PortableManualDatabase
import com.portablemanual.app.data.local.entity.DeviceEntity
import com.portablemanual.app.data.local.entity.DeviceTagCrossRef
import com.portablemanual.app.data.local.entity.DocumentEntity
import com.portablemanual.app.data.local.entity.FolderEntity
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.data.local.entity.TagEntity
import com.portablemanual.app.image.ImageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 新建标签的结果。UI 必须能区分「新建成功」和「重名已存在」，否则用户会以为操作失败了。 */
sealed interface TagResult {
    data class Created(val id: Long) : TagResult
    data class Exists(val id: Long) : TagResult
    data object Blank : TagResult
    data object Failed : TagResult
}

/**
 * 资料库的唯一数据出入口。UI 层只跟它打交道，不直接碰 DAO。
 */
class LibraryRepository(
    private val db: PortableManualDatabase,
    private val imageStore: ImageStore
) {

    private val folderDao = db.folderDao()
    private val deviceDao = db.deviceDao()
    private val documentDao = db.documentDao()
    private val pageDao = db.pageDao()
    private val tagDao = db.tagDao()

    // ---------------------------------------------------------------- 读

    fun observeFolders() = folderDao.observeAllWithCount()

    fun observeDevices(folderId: Long?, tagId: Long?, query: String) =
        deviceDao.observeDevices(folderId, tagId, query.trim())

    fun observeDevice(deviceId: Long) = deviceDao.observeDevice(deviceId)

    fun observeTags() = tagDao.observeAllWithCount()

    fun observeCrossRefs() = tagDao.observeAllCrossRefs()

    fun observeTagsForDevice(deviceId: Long) = tagDao.observeTagsForDevice(deviceId)

    fun observeDocument(deviceId: Long) = documentDao.observeByDevice(deviceId)

    fun observePages(documentId: Long) = pageDao.observeByDocument(documentId)

    suspend fun getDevice(deviceId: Long) = deviceDao.getById(deviceId)

    suspend fun getTagsForDevice(deviceId: Long) = tagDao.getTagsForDevice(deviceId)

    suspend fun getDocument(deviceId: Long) = documentDao.getByDevice(deviceId)

    suspend fun getPages(documentId: Long) = pageDao.getByDocument(documentId)

    suspend fun getPage(pageId: Long) = pageDao.getById(pageId)

    suspend fun getFolders() = folderDao.getAll()

    suspend fun getAllTags() = tagDao.getAll()

    suspend fun getAllCrossRefs() = tagDao.getAllCrossRefs()

    suspend fun deviceCount() = deviceDao.count()

    fun storageBytes(): Long = imageStore.totalBytes()

    /** 图片根目录，供 UI 直接拼 File 交给图片加载库。 */
    fun imageStoreRoot(): java.io.File = imageStore.root

    // ---------------------------------------------------------------- 文件夹

    suspend fun createFolder(name: String, parentId: Long?): Long =
        folderDao.insert(
            FolderEntity(
                name = name.trim().ifEmpty { "未命名文件夹" },
                parentId = parentId,
                sortOrder = 0,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun renameFolder(id: Long, name: String) {
        val folder = folderDao.getById(id) ?: return
        folderDao.update(folder.copy(name = name.trim().ifEmpty { folder.name }))
    }

    /** 移动文件夹。会拦截「移到自己子孙下」这种把树搞成环的操作。 */
    suspend fun moveFolder(id: Long, newParentId: Long?): Boolean {
        if (id == newParentId) return false
        if (newParentId != null && isDescendant(candidate = newParentId, ancestor = id)) return false
        val folder = folderDao.getById(id) ?: return false
        folderDao.update(folder.copy(parentId = newParentId))
        return true
    }

    private suspend fun isDescendant(candidate: Long, ancestor: Long): Boolean {
        var cursor: Long? = candidate
        var guard = 0
        while (cursor != null && guard++ < 64) {
            if (cursor == ancestor) return true
            cursor = folderDao.getById(cursor)?.parentId
        }
        return false
    }

    suspend fun deleteFolder(id: Long) {
        val folder = folderDao.getById(id) ?: return
        folderDao.delete(folder)
    }

    // ---------------------------------------------------------------- 设备

    suspend fun createDevice(
        name: String,
        brand: String,
        model: String,
        purchaseDate: Long?,
        note: String,
        folderId: Long?
    ): Long {
        val now = System.currentTimeMillis()
        val id = deviceDao.insert(
            DeviceEntity(
                name = name.trim().ifEmpty { "未命名设备" },
                brand = brand.trim(),
                model = model.trim(),
                purchaseDate = purchaseDate,
                note = note.trim(),
                folderId = folderId,
                createdAt = now,
                updatedAt = now
            )
        )
        // 每台设备诞生时就配一份空文档，保证 1:1 不变式
        documentDao.insert(
            DocumentEntity(
                deviceId = id,
                title = name.trim().ifEmpty { "未命名设备" },
                createdAt = now
            )
        )
        return id
    }

    suspend fun updateDevice(
        id: Long,
        name: String,
        brand: String,
        model: String,
        purchaseDate: Long?,
        note: String,
        folderId: Long?
    ) {
        val device = deviceDao.getById(id) ?: return
        deviceDao.update(
            device.copy(
                name = name.trim().ifEmpty { device.name },
                brand = brand.trim(),
                model = model.trim(),
                purchaseDate = purchaseDate,
                note = note.trim(),
                folderId = folderId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun touchDevice(id: Long) {
        val device = deviceDao.getById(id) ?: return
        deviceDao.update(device.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteDevice(id: Long) {
        val device = deviceDao.getById(id) ?: return
        deviceDao.delete(device)   // 文档 / 页 / 标签关联均级联删除
        imageStore.deleteDeviceFolder(id)
    }

    // ---------------------------------------------------------------- 标签

    suspend fun createTag(name: String, colorIndex: Int = 0): TagResult {
        val clean = name.trim()
        if (clean.isEmpty()) return TagResult.Blank
        tagDao.getByName(clean)?.let { return TagResult.Exists(it.id) }
        val id = tagDao.insert(
            TagEntity(name = clean, colorIndex = colorIndex, createdAt = System.currentTimeMillis())
        )
        // 唯一索引冲突时 IGNORE 会返回 -1，此时再查一次拿到已有 id
        if (id <= 0) {
            val existing = tagDao.getByName(clean)
            return if (existing != null) TagResult.Exists(existing.id) else TagResult.Failed
        }
        return TagResult.Created(id)
    }

    suspend fun renameTag(id: Long, name: String) {
        val tag = tagDao.getById(id) ?: return
        tagDao.update(tag.copy(name = name.trim().ifEmpty { tag.name }))
    }

    suspend fun setTagColor(id: Long, colorIndex: Int) {
        val tag = tagDao.getById(id) ?: return
        tagDao.update(tag.copy(colorIndex = colorIndex))
    }

    suspend fun deleteTag(id: Long) {
        val tag = tagDao.getById(id) ?: return
        tagDao.delete(tag)
    }

    /** 全量覆盖某台设备的标签集合。 */
    suspend fun setDeviceTags(deviceId: Long, tagIds: Set<Long>) {
        tagDao.unlinkAll(deviceId)
        if (tagIds.isNotEmpty()) {
            tagDao.linkAll(tagIds.map { DeviceTagCrossRef(deviceId, it) })
        }
        touchDevice(deviceId)
    }

    // ---------------------------------------------------------------- 页

    suspend fun ensureDocument(deviceId: Long): DocumentEntity {
        documentDao.getByDevice(deviceId)?.let { return it }
        val device = deviceDao.getById(deviceId)
        val now = System.currentTimeMillis()
        val id = documentDao.insert(
            DocumentEntity(deviceId = deviceId, title = device?.name ?: "说明书", createdAt = now)
        )
        return DocumentEntity(id = id, deviceId = deviceId, title = device?.name ?: "说明书", createdAt = now)
    }

    /** 保存一页（已裁剪 / 压缩好的位图），返回新页 id。 */
    suspend fun addPage(deviceId: Long, bitmap: Bitmap, quality: Int): Long {
        val document = ensureDocument(deviceId)
        val position = pageDao.countByDocument(document.id)
        val relativeName = imageStore.newRelativeName(deviceId)
        val size = imageStore.saveJpeg(bitmap, relativeName, quality)
        val pageId = pageDao.insert(
            PageEntity(
                documentId = document.id,
                position = position,
                fileName = relativeName,
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = size,
                createdAt = System.currentTimeMillis()
            )
        )
        touchDevice(deviceId)
        return pageId
    }

    /** 用已编码好的 JPEG 字节直接落一页（拍照流水线用，避免长期持有大 Bitmap）。 */
    suspend fun addPageFromJpeg(
        deviceId: Long,
        jpeg: ByteArray,
        width: Int,
        height: Int
    ): Long {
        val document = ensureDocument(deviceId)
        val position = pageDao.countByDocument(document.id)
        val relativeName = imageStore.newRelativeName(deviceId)
        val size = imageStore.writeBytes(relativeName, jpeg)
        val pageId = pageDao.insert(
            PageEntity(
                documentId = document.id,
                position = position,
                fileName = relativeName,
                width = width,
                height = height,
                sizeBytes = size,
                createdAt = System.currentTimeMillis()
            )
        )
        touchDevice(deviceId)
        return pageId
    }

    suspend fun deletePage(pageId: Long) {
        val page = pageDao.getById(pageId) ?: return
        pageDao.delete(page)
        imageStore.delete(page.fileName)
        normalizePositions(page.documentId)
    }

    /** 用新位图替换某一页（补拍 / 重新裁剪）。 */
    suspend fun replacePageImage(pageId: Long, bitmap: Bitmap, quality: Int) {
        val page = pageDao.getById(pageId) ?: return
        imageStore.saveJpeg(bitmap, page.fileName, quality)
        pageDao.update(
            page.copy(
                width = bitmap.width,
                height = bitmap.height,
                sizeBytes = imageStore.fileFor(page.fileName).length()
            )
        )
    }

    suspend fun reorderPages(documentId: Long, orderedPageIds: List<Long>) {
        val pages = pageDao.getByDocument(documentId).associateBy { it.id }
        val updated = orderedPageIds.mapIndexedNotNull { index, pageId ->
            pages[pageId]?.takeIf { it.position != index }?.copy(position = index)
        }
        if (updated.isNotEmpty()) pageDao.updateAll(updated)
    }

    private suspend fun normalizePositions(documentId: Long) {
        val pages = pageDao.getByDocument(documentId)
        val updated = pages.mapIndexedNotNull { index, page ->
            if (page.position != index) page.copy(position = index) else null
        }
        if (updated.isNotEmpty()) pageDao.updateAll(updated)
    }

    /** 数据库里当前被引用的所有图片相对路径。 */
    suspend fun allReferencedFileNames(): Set<String> {
        val result = HashSet<String>()
        val devices = deviceDao.observeDevices(null, null, "").first()
        for (item in devices) {
            val document = documentDao.getByDevice(item.device.id) ?: continue
            pageDao.getByDocument(document.id).forEach { result.add(it.fileName) }
        }
        return result
    }

    /** 清理数据库已无记录的孤儿图片，返回释放的字节数。 */
    suspend fun pruneOrphanImages(): Long = imageStore.pruneOrphans(allReferencedFileNames())

    fun deleteAllImages() = imageStore.clearAll()
}
