package com.portablemanual.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.portablemanual.app.data.local.entity.DeviceEntity
import com.portablemanual.app.data.local.entity.DeviceTagCrossRef
import com.portablemanual.app.data.local.entity.DeviceWithMeta
import com.portablemanual.app.data.local.entity.DocumentEntity
import com.portablemanual.app.data.local.entity.FolderEntity
import com.portablemanual.app.data.local.entity.FolderWithCount
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.data.local.entity.TagEntity
import com.portablemanual.app.data.local.entity.TagWithCount
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query(
        """
        SELECT f.*, (SELECT COUNT(*) FROM devices d WHERE d.folderId = f.id) AS deviceCount
        FROM folders f
        ORDER BY f.sortOrder ASC, f.name ASC
        """
    )
    fun observeAllWithCount(): Flow<List<FolderWithCount>>

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT COUNT(*) FROM devices WHERE folderId = :folderId")
    suspend fun deviceCount(folderId: Long): Int

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)
}

@Dao
interface DeviceDao {

    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM pages p
                JOIN documents doc ON p.documentId = doc.id
                WHERE doc.deviceId = d.id) AS pageCount,
            (SELECT p.fileName FROM pages p
                JOIN documents doc ON p.documentId = doc.id
                WHERE doc.deviceId = d.id
                ORDER BY p.position ASC LIMIT 1) AS coverFileName
        FROM devices d
        WHERE (:folderId IS NULL OR d.folderId = :folderId)
          AND (:tagId IS NULL OR d.id IN (SELECT deviceId FROM device_tag WHERE tagId = :tagId))
          AND (:q = '' OR d.name LIKE '%' || :q || '%'
                       OR d.brand LIKE '%' || :q || '%'
                       OR d.model LIKE '%' || :q || '%'
                       OR d.note LIKE '%' || :q || '%')
        ORDER BY d.updatedAt DESC
        """
    )
    fun observeDevices(folderId: Long?, tagId: Long?, q: String): Flow<List<DeviceWithMeta>>

    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM pages p
                JOIN documents doc ON p.documentId = doc.id
                WHERE doc.deviceId = d.id) AS pageCount,
            (SELECT p.fileName FROM pages p
                JOIN documents doc ON p.documentId = doc.id
                WHERE doc.deviceId = d.id
                ORDER BY p.position ASC LIMIT 1) AS coverFileName
        FROM devices d WHERE d.id = :id
        """
    )
    fun observeDevice(id: Long): Flow<DeviceWithMeta?>

    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getById(id: Long): DeviceEntity?

    @Query("SELECT COUNT(*) FROM devices")
    suspend fun count(): Int

    @Insert
    suspend fun insert(device: DeviceEntity): Long

    @Update
    suspend fun update(device: DeviceEntity)

    @Delete
    suspend fun delete(device: DeviceEntity)
}

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE deviceId = :deviceId ORDER BY id ASC LIMIT 1")
    suspend fun getByDevice(deviceId: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE deviceId = :deviceId ORDER BY id ASC LIMIT 1")
    fun observeByDevice(deviceId: Long): Flow<DocumentEntity?>

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)
}

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position ASC, id ASC")
    fun observeByDocument(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position ASC, id ASC")
    suspend fun getByDocument(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getById(id: Long): PageEntity?

    @Query("SELECT COUNT(*) FROM pages WHERE documentId = :documentId")
    suspend fun countByDocument(documentId: Long): Int

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Insert
    suspend fun insertAll(pages: List<PageEntity>)

    @Update
    suspend fun update(page: PageEntity)

    @Update
    suspend fun updateAll(pages: List<PageEntity>)

    @Delete
    suspend fun delete(page: PageEntity)

    @Query("DELETE FROM pages WHERE documentId = :documentId")
    suspend fun deleteByDocument(documentId: Long)
}

@Dao
interface TagDao {

    @Query(
        """
        SELECT t.*, (SELECT COUNT(*) FROM device_tag x WHERE x.tagId = t.id) AS deviceCount
        FROM tags t ORDER BY t.name ASC
        """
    )
    fun observeAllWithCount(): Flow<List<TagWithCount>>

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Update
    suspend fun update(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN device_tag x ON t.id = x.tagId
        WHERE x.deviceId = :deviceId
        ORDER BY t.name ASC
        """
    )
    fun observeTagsForDevice(deviceId: Long): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.* FROM tags t
        JOIN device_tag x ON t.id = x.tagId
        WHERE x.deviceId = :deviceId
        ORDER BY t.name ASC
        """
    )
    suspend fun getTagsForDevice(deviceId: Long): List<TagEntity>

    @Query("SELECT * FROM device_tag")
    fun observeAllCrossRefs(): Flow<List<DeviceTagCrossRef>>

    @Query("SELECT * FROM device_tag")
    suspend fun getAllCrossRefs(): List<DeviceTagCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(ref: DeviceTagCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(refs: List<DeviceTagCrossRef>)

    @Query("DELETE FROM device_tag WHERE deviceId = :deviceId AND tagId = :tagId")
    suspend fun unlink(deviceId: Long, tagId: Long)

    @Query("DELETE FROM device_tag WHERE deviceId = :deviceId")
    suspend fun unlinkAll(deviceId: Long)
}
