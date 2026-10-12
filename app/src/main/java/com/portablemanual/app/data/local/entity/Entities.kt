package com.portablemanual.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 文件夹：自引用树结构，用于层级归类（如 家电 / 厨房 / 冰箱）。
 * 删除文件夹时级联删除子文件夹；挂在它下面的设备会被置空（不删设备）。
 */
@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId")]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long
)

/** 设备：用户真正关心的实体，一台设备对应一份说明书文档。 */
@Entity(
    tableName = "devices",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId"), Index("name"), Index("brand"), Index("model")]
)
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val brand: String = "",
    val model: String = "",
    val purchaseDate: Long? = null,
    val note: String = "",
    val folderId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 文档：v1 中与设备 1:1，但独立成表。
 * 这样 v2 想给一台设备挂「保修卡 / 发票」时不必改结构。
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("deviceId")]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val deviceId: Long,
    val title: String,
    val createdAt: Long
)

/** 页：文档下的一张图。fileName 为 images 目录内的相对路径，换机后依然有效。 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentId: Long,
    val position: Int,
    val fileName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val createdAt: Long
)

/** 标签：与设备多对多。colorIndex 指向黑白灰调色板中的一档。 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorIndex: Int = 0,
    val createdAt: Long
)

@Entity(
    tableName = "device_tag",
    primaryKeys = ["deviceId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class DeviceTagCrossRef(
    val deviceId: Long,
    val tagId: Long
)

// ---------------------------------------------------------------- 查询投影

/** 列表卡片所需的设备信息：设备本体 + 页数 + 封面图。 */
data class DeviceWithMeta(
    @Embedded val device: DeviceEntity,
    val pageCount: Int,
    val coverFileName: String?
)

/** 文件夹 + 直属设备数量。 */
data class FolderWithCount(
    @Embedded val folder: FolderEntity,
    val deviceCount: Int
)

/** 标签 + 关联设备数量。 */
data class TagWithCount(
    @Embedded val tag: TagEntity,
    val deviceCount: Int
)
