package com.portablemanual.app.di

import android.content.Context
import com.portablemanual.app.backup.BackupManager
import com.portablemanual.app.data.local.PortableManualDatabase
import com.portablemanual.app.data.prefs.SettingsRepository
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.image.ImageStore
import com.portablemanual.app.pdf.PdfExporter

/**
 * 手写依赖容器。
 *
 * 没上 Hilt —— 这个体量的应用用不上注解处理那一套，
 * 少一个 KSP 处理器就少一处构建风险，代码也更直白。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: PortableManualDatabase by lazy { PortableManualDatabase.build(appContext) }

    val imageStore: ImageStore by lazy { ImageStore(appContext) }

    val libraryRepository: LibraryRepository by lazy { LibraryRepository(database, imageStore) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val backupManager: BackupManager by lazy { BackupManager(appContext, database, imageStore) }

    val pdfExporter: PdfExporter by lazy { PdfExporter(appContext) }
}
