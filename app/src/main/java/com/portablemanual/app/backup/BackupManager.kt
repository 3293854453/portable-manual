package com.portablemanual.app.backup

import android.content.Context
import android.net.Uri
import com.portablemanual.app.data.local.PortableManualDatabase
import com.portablemanual.app.data.local.entity.DeviceEntity
import com.portablemanual.app.data.local.entity.DeviceTagCrossRef
import com.portablemanual.app.data.local.entity.DocumentEntity
import com.portablemanual.app.data.local.entity.FolderEntity
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.data.local.entity.TagEntity
import com.portablemanual.app.image.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整库备份 / 恢复。
 *
 * 备份包结构：
 * ```
 * manifest.json      —— 全部结构化数据
 * images/12/a1b2.jpg —— 原样搬运的图片
 * ```
 * 因为库里只存相对路径，所以这套包换到任何一台手机都能完整还原。
 */
class BackupManager(
    private val context: Context,
    private val db: PortableManualDatabase,
    private val imageStore: ImageStore
) {

    enum class ImportMode { MERGE, REPLACE }

    data class Stats(
        val folders: Int = 0,
        val devices: Int = 0,
        val pages: Int = 0,
        val tags: Int = 0,
        val skipped: Int = 0
    )

    // ---------------------------------------------------------------- 导出

    suspend fun export(
        target: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Stats> = withContext(Dispatchers.IO) {
        runCatching {
            val folderDao = db.folderDao()
            val tagDao = db.tagDao()
            val deviceDao = db.deviceDao()
            val documentDao = db.documentDao()
            val pageDao = db.pageDao()

            val folders = folderDao.getAll()
            val tags = tagDao.getAll()
            val devices = deviceDao.observeDevices(null, null, "").first().map { it.device }
            val crossRefs = tagDao.getAllCrossRefs()
            val tagsByDevice = crossRefs.groupBy({ it.deviceId }, { it.tagId })
            val tagNameById = tags.associate { it.id to it.name }

            val documents = ArrayList<DocumentEntity>()
            val pagesByDocument = HashMap<Long, List<PageEntity>>()
            devices.forEach { device ->
                val doc = documentDao.getByDevice(device.id) ?: return@forEach
                documents.add(doc)
                pagesByDocument[doc.id] = pageDao.getByDocument(doc.id)
            }
            val allPages = pagesByDocument.values.flatten()

            val manifest = JSONObject().apply {
                put("format", FORMAT)
                put("version", FORMAT_VERSION)
                put("exportedAt", System.currentTimeMillis())

                put("folders", JSONArray().apply {
                    folders.forEach { f ->
                        put(JSONObject().apply {
                            put("id", f.id)
                            put("name", f.name)
                            putNullableLong("parentId", f.parentId)
                            put("sortOrder", f.sortOrder)
                            put("createdAt", f.createdAt)
                        })
                    }
                })

                put("tags", JSONArray().apply {
                    tags.forEach { t ->
                        put(JSONObject().apply {
                            put("id", t.id)
                            put("name", t.name)
                            put("colorIndex", t.colorIndex)
                            put("createdAt", t.createdAt)
                        })
                    }
                })

                put("devices", JSONArray().apply {
                    devices.forEach { d ->
                        put(JSONObject().apply {
                            put("id", d.id)
                            put("name", d.name)
                            put("brand", d.brand)
                            put("model", d.model)
                            putNullableLong("purchaseDate", d.purchaseDate)
                            put("note", d.note)
                            putNullableLong("folderId", d.folderId)
                            put("createdAt", d.createdAt)
                            put("updatedAt", d.updatedAt)
                            put("tagNames", JSONArray().apply {
                                tagsByDevice[d.id].orEmpty()
                                    .mapNotNull { tagNameById[it] }
                                    .forEach { put(it) }
                            })
                        })
                    }
                })

                put("documents", JSONArray().apply {
                    documents.forEach { doc ->
                        put(JSONObject().apply {
                            put("id", doc.id)
                            put("deviceId", doc.deviceId)
                            put("title", doc.title)
                            put("createdAt", doc.createdAt)
                        })
                    }
                })

                put("pages", JSONArray().apply {
                    allPages.forEach { p ->
                        put(JSONObject().apply {
                            put("documentId", p.documentId)
                            put("position", p.position)
                            put("fileName", p.fileName)
                            put("width", p.width)
                            put("height", p.height)
                            put("sizeBytes", p.sizeBytes)
                            put("createdAt", p.createdAt)
                        })
                    }
                })
            }

            val total = allPages.size + 1
            var done = 0

            val output = context.contentResolver.openOutputStream(target)
                ?: throw IllegalStateException("无法写入目标文件")
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                done++
                onProgress(done, total)

                allPages.forEach { page ->
                    val file = imageStore.fileFor(page.fileName)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry("$IMAGE_PREFIX${page.fileName}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    done++
                    onProgress(done, total)
                }
            }

            Stats(
                folders = folders.size,
                devices = devices.size,
                pages = allPages.size,
                tags = tags.size
            )
        }
    }

    // ---------------------------------------------------------------- 导入

    suspend fun import(
        source: Uri,
        mode: ImportMode,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Stats> = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(context.cacheDir, "backup-import").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            var manifest: JSONObject? = null
            var extracted = 0
            try {
                val input = context.contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("无法读取备份文件")

                ZipInputStream(input.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == MANIFEST_NAME) {
                            manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        } else if (name.startsWith(IMAGE_PREFIX) && !entry.isDirectory) {
                            val relative = name.removePrefix(IMAGE_PREFIX)
                            val target = File(staging, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            extracted++
                            onProgress(extracted, extracted.coerceAtLeast(1))
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                val data = manifest ?: throw IllegalStateException("备份文件缺少 manifest.json")
                if (data.optString("format") != FORMAT) {
                    throw IllegalStateException("这不是「便携说明书」的备份文件")
                }

                applyImport(data, staging, mode, onProgress)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private suspend fun applyImport(
        data: JSONObject,
        staging: File,
        mode: ImportMode,
        onProgress: (done: Int, total: Int) -> Unit
    ): Stats {
        val folderDao = db.folderDao()
        val tagDao = db.tagDao()
        val deviceDao = db.deviceDao()
        val documentDao = db.documentDao()
        val pageDao = db.pageDao()

        if (mode == ImportMode.REPLACE) {
            deviceDao.observeDevices(null, null, "").first().forEach { deviceDao.delete(it.device) }
            folderDao.getAll().forEach { folderDao.delete(it) }
            tagDao.getAll().forEach { tagDao.delete(it) }
            imageStore.clearAll()
        }

        var skipped = 0

        // 1) 文件夹：先建后挂，按父先于子的顺序插入
        val rawFolders = data.optJSONArray("folders") ?: JSONArray()
        val folderNodes = (0 until rawFolders.length()).map { rawFolders.getJSONObject(it) }
        val folderIdMap = HashMap<Long, Long>()
        var guard = 0
        while (folderIdMap.size < folderNodes.size && guard++ < folderNodes.size + 2) {
            folderNodes.forEach { node ->
                val oldId = node.getLong("id")
                if (folderIdMap.containsKey(oldId)) return@forEach
                val oldParent = node.optNullableLong("parentId")
                val newParent = if (oldParent == null) null else folderIdMap[oldParent] ?: return@forEach
                val newId = folderDao.insert(
                    FolderEntity(
                        name = node.optString("name"),
                        parentId = newParent,
                        sortOrder = node.optInt("sortOrder"),
                        createdAt = node.optLong("createdAt", System.currentTimeMillis())
                    )
                )
                folderIdMap[oldId] = newId
            }
        }

        // 2) 标签：同名复用
        val rawTags = data.optJSONArray("tags") ?: JSONArray()
        val tagIdMap = HashMap<Long, Long>()
        for (i in 0 until rawTags.length()) {
            val node = rawTags.getJSONObject(i)
            val name = node.optString("name").trim()
            if (name.isEmpty()) continue
            val existing = tagDao.getByName(name)
            val newId = existing?.id ?: tagDao.insert(
                TagEntity(
                    name = name,
                    colorIndex = node.optInt("colorIndex"),
                    createdAt = node.optLong("createdAt", System.currentTimeMillis())
                )
            )
            tagIdMap[node.getLong("id")] = newId
        }

        // 3) 设备 + 文档
        val rawDevices = data.optJSONArray("devices") ?: JSONArray()
        val existingKeys = deviceDao.observeDevices(null, null, "").first()
            .map { deviceKey(it.device.name, it.device.brand, it.device.model, it.device.note) }
            .toHashSet()

        val deviceIdMap = HashMap<Long, Long>()
        val documentIdMap = HashMap<Long, Long>()
        val docToDeviceMap = HashMap<Long, Long>()
        var importedDevices = 0

        for (i in 0 until rawDevices.length()) {
            val node = rawDevices.getJSONObject(i)
            val oldDeviceId = node.getLong("id")
            val name = node.optString("name")
            val brand = node.optString("brand")
            val model = node.optString("model")
            val note = node.optString("note")

            if (mode == ImportMode.MERGE) {
                val key = deviceKey(name, brand, model, note)
                if (key in existingKeys) {
                    skipped++
                    continue
                }
                existingKeys.add(key)
            }

            val now = System.currentTimeMillis()
            val newDeviceId = deviceDao.insert(
                DeviceEntity(
                    name = name.ifBlank { "未命名设备" },
                    brand = brand,
                    model = model,
                    purchaseDate = node.optNullableLong("purchaseDate"),
                    note = note,
                    folderId = node.optNullableLong("folderId")?.let { folderIdMap[it] },
                    createdAt = node.optLong("createdAt", now),
                    updatedAt = node.optLong("updatedAt", now)
                )
            )
            deviceIdMap[oldDeviceId] = newDeviceId
            importedDevices++

            val newDocId = documentDao.insert(
                DocumentEntity(
                    deviceId = newDeviceId,
                    title = node.optString("title").ifBlank { name },
                    createdAt = now
                )
            )
            documentIdMap[oldDeviceId] = newDocId
            docToDeviceMap[newDocId] = newDeviceId

            val tagNames = node.optJSONArray("tagNames") ?: JSONArray()
            val refs = ArrayList<DeviceTagCrossRef>()
            for (t in 0 until tagNames.length()) {
                val tagName = tagNames.optString(t).trim()
                if (tagName.isEmpty()) continue
                val tagId = tagDao.getByName(tagName)?.id
                    ?: tagDao.insert(TagEntity(name = tagName, createdAt = now))
                refs.add(DeviceTagCrossRef(newDeviceId, tagId))
            }
            if (refs.isNotEmpty()) tagDao.linkAll(refs)
        }

        // 4) 文档 id 映射（备份里的 documents 数组可能包含额外文档）
        val rawDocuments = data.optJSONArray("documents") ?: JSONArray()
        val docIdMap = HashMap<Long, Long>()
        for (i in 0 until rawDocuments.length()) {
            val node = rawDocuments.getJSONObject(i)
            val oldDocId = node.getLong("id")
            val oldDeviceId = node.getLong("deviceId")
            val mapped = documentIdMap[oldDeviceId] ?: deviceIdMap[oldDeviceId]?.let { newDeviceId ->
                documentDao.insert(
                    DocumentEntity(
                        deviceId = newDeviceId,
                        title = node.optString("title"),
                        createdAt = node.optLong("createdAt", System.currentTimeMillis())
                    )
                ).also { docToDeviceMap[it] = newDeviceId }
            }
            if (mapped != null) docIdMap[oldDocId] = mapped
        }

        // 5) 页 + 图片搬运
        val rawPages = data.optJSONArray("pages") ?: JSONArray()
        var importedPages = 0
        for (i in 0 until rawPages.length()) {
            val node = rawPages.getJSONObject(i)
            val newDocId = docIdMap[node.getLong("documentId")] ?: continue
            val oldFileName = node.optString("fileName")
            val staged = File(staging, oldFileName)
            if (!staged.exists()) continue

            val newDeviceId = docToDeviceMap[newDocId] ?: 0L
            val newRelative = imageStore.newRelativeName(newDeviceId)
            val size = imageStore.writeBytes(newRelative, staged.readBytes())
            pageDao.insert(
                PageEntity(
                    documentId = newDocId,
                    position = node.optInt("position"),
                    fileName = newRelative,
                    width = node.optInt("width"),
                    height = node.optInt("height"),
                    sizeBytes = size,
                    createdAt = node.optLong("createdAt", System.currentTimeMillis())
                )
            )
            importedPages++
            onProgress(importedPages, rawPages.length())
        }

        return Stats(
            folders = folderIdMap.size,
            devices = importedDevices,
            pages = importedPages,
            tags = tagIdMap.size,
            skipped = skipped
        )
    }

    private fun deviceKey(name: String, brand: String, model: String, note: String): String =
        listOf(name, brand, model, note).joinToString("\u0001") { it.trim() }

    private fun JSONObject.putNullableLong(key: String, value: Long?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key).takeIf { has(key) }

    companion object {
        const val FORMAT = "portable-manual-backup"
        const val FORMAT_VERSION = 1
        private const val MANIFEST_NAME = "manifest.json"
        private const val IMAGE_PREFIX = "images/"
    }
}
