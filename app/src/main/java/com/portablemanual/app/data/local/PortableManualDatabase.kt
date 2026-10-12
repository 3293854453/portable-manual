package com.portablemanual.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.portablemanual.app.data.local.dao.DeviceDao
import com.portablemanual.app.data.local.dao.DocumentDao
import com.portablemanual.app.data.local.dao.FolderDao
import com.portablemanual.app.data.local.dao.PageDao
import com.portablemanual.app.data.local.dao.TagDao
import com.portablemanual.app.data.local.entity.DeviceEntity
import com.portablemanual.app.data.local.entity.DeviceTagCrossRef
import com.portablemanual.app.data.local.entity.DocumentEntity
import com.portablemanual.app.data.local.entity.FolderEntity
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.data.local.entity.TagEntity

@Database(
    entities = [
        FolderEntity::class,
        DeviceEntity::class,
        DocumentEntity::class,
        PageEntity::class,
        TagEntity::class,
        DeviceTagCrossRef::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PortableManualDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao
    abstract fun deviceDao(): DeviceDao
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun tagDao(): TagDao

    companion object {
        private const val DB_NAME = "portable-manual.db"

        fun build(context: Context): PortableManualDatabase =
            Room.databaseBuilder(context.applicationContext, PortableManualDatabase::class.java, DB_NAME)
                .build()
    }
}
