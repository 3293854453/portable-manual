package com.portablemanual.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.backup.BackupManager
import com.portablemanual.app.data.prefs.SettingsRepository
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.image.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val storageBytes: Long = 0L,
    val deviceCount: Int = 0,
    val pageCount: Int = 0,
    val busy: Boolean = false,
    val progressText: String? = null,
    val message: String? = null
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val repository: LibraryRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    val themeMode: StateFlow<String> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.THEME_SYSTEM)

    val jpegQuality: StateFlow<Int> = settings.jpegQuality
        .stateIn(viewModelScope, SharingStarted.Eagerly, ImageCompressor.DEFAULT_QUALITY)

    val autoCrop: StateFlow<Boolean> = settings.autoCrop
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { repository.storageBytes() }
            val devices = repository.deviceCount()
            var pages = 0
            // 页数按设备逐个数，量级不大，够用
            withContext(Dispatchers.IO) {
                val list = repository.observeDevices(null, null, "").first()
                for (item in list) {
                    val doc = repository.getDocument(item.device.id) ?: continue
                    pages += repository.getPages(doc.id).size
                }
            }
            _uiState.value = _uiState.value.copy(
                storageBytes = bytes,
                deviceCount = devices,
                pageCount = pages
            )
        }
    }

    fun setTheme(mode: String) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setQuality(value: Int) {
        viewModelScope.launch { settings.setJpegQuality(value) }
    }

    fun setAutoCrop(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoCrop(enabled) }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, progressText = "正在打包…", message = null)
            val result = backupManager.export(uri) { done, total ->
                _uiState.value = _uiState.value.copy(progressText = "正在打包 $done / $total")
            }
            _uiState.value = _uiState.value.copy(busy = false, progressText = null)
            result.fold(
                onSuccess = { stats ->
                    _uiState.value = _uiState.value.copy(
                        message = "备份完成：${stats.devices} 台设备、${stats.pages} 页"
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(message = "备份失败：${e.message ?: "未知错误"}")
                }
            )
        }
    }

    fun importBackup(uri: Uri, mode: BackupManager.ImportMode) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, progressText = "正在还原…", message = null)
            val result = backupManager.import(uri, mode) { done, total ->
                _uiState.value = _uiState.value.copy(progressText = "正在还原 $done / $total")
            }
            _uiState.value = _uiState.value.copy(busy = false, progressText = null)
            result.fold(
                onSuccess = { stats ->
                    val skipped = if (stats.skipped > 0) "，跳过 ${stats.skipped} 台重复设备" else ""
                    _uiState.value = _uiState.value.copy(
                        message = "还原完成：${stats.devices} 台设备、${stats.pages} 页$skipped"
                    )
                    refreshStats()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(message = "还原失败：${e.message ?: "未知错误"}")
                }
            )
        }
    }

    fun pruneOrphans() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, progressText = "正在清理…")
            val freed = withContext(Dispatchers.IO) { repository.pruneOrphanImages() }
            _uiState.value = _uiState.value.copy(
                busy = false,
                progressText = null,
                message = if (freed > 0) "已清理 ${formatBytes(freed)}" else "没有需要清理的残留图片"
            )
            refreshStats()
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
}
