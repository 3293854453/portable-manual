package com.portablemanual.app.ui.detail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.data.local.entity.DeviceWithMeta
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.data.local.entity.TagEntity
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.image.ImageStore
import com.portablemanual.app.pdf.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DetailUiState(
    val device: DeviceWithMeta? = null,
    val tags: List<TagEntity> = emptyList(),
    val pages: List<PageEntity> = emptyList(),
    val imageRoot: File = File("/"),
    val busy: Boolean = false,
    val message: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModel(
    private val context: Context,
    private val repository: LibraryRepository,
    private val imageStore: ImageStore,
    private val pdfExporter: PdfExporter,
    private val deviceId: Long
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val document = repository.observeDocument(deviceId)

    private val pages = document.flatMapLatest { doc ->
        if (doc == null) flowOf(emptyList()) else repository.observePages(doc.id)
    }

    val uiState: StateFlow<DetailUiState> = kotlinx.coroutines.flow.combine(
        repository.observeDevice(deviceId),
        repository.observeTagsForDevice(deviceId),
        pages,
        busy,
        message
    ) { device, tags, pageList, isBusy, msg ->
        DetailUiState(
            device = device,
            tags = tags,
            pages = pageList,
            imageRoot = imageStore.root,
            busy = isBusy,
            message = msg
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    fun deletePage(pageId: Long) {
        viewModelScope.launch { repository.deletePage(pageId) }
    }

    /** 把某页往前/往后挪一位。 */
    fun movePage(pageId: Long, offset: Int) {
        viewModelScope.launch {
            val doc = repository.getDocument(deviceId) ?: return@launch
            val current = repository.getPages(doc.id).sortedBy { it.position }
            val index = current.indexOfFirst { it.id == pageId }
            if (index < 0) return@launch
            val target = index + offset
            if (target < 0 || target >= current.size) return@launch

            val reordered = current.toMutableList()
            val moved = reordered.removeAt(index)
            reordered.add(target, moved)
            repository.reorderPages(doc.id, reordered.map { it.id })
        }
    }

    fun deleteDevice(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteDevice(deviceId)
            onDone()
        }
    }

    fun exportPdf(uri: Uri) {
        viewModelScope.launch {
            busy.value = true
            message.value = null
            val doc = repository.getDocument(deviceId) ?: return@launch
            val pageList = repository.getPages(doc.id)
            if (pageList.isEmpty()) {
                busy.value = false
                message.value = "这份说明书还没有页面"
                return@launch
            }

            val temp = File(context.cacheDir, "export-${deviceId}.pdf")
            val result = pdfExporter.export(pageList.map { imageStore.fileFor(it.fileName) }, temp)

            result.fold(
                onSuccess = {
                    val copied = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                temp.inputStream().use { it.copyTo(out) }
                                true
                            } ?: false
                        }.getOrDefault(false)
                    }
                    temp.delete()
                    message.value = if (copied) "PDF 已导出（${pageList.size} 页）" else "PDF 写入失败"
                },
                onFailure = { e ->
                    temp.delete()
                    message.value = "导出失败：${e.message ?: "未知错误"}"
                }
            )
            busy.value = false
        }
    }

    fun consumeMessage() { message.value = null }
}
