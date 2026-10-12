package com.portablemanual.app.ui.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.data.prefs.SettingsRepository
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.image.DocumentScanner
import com.portablemanual.app.image.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 拍照流水线的状态机。
 *
 * 内存策略：已确认的页面立刻压成 JPEG 字节，只保留待调整的那一张 Bitmap。
 * 拍 30 页也不会 OOM。
 */
class CaptureViewModel(
    private val repository: LibraryRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    /** 待用户确认的裁剪状态。 */
    data class AdjustState(
        val bitmap: Bitmap,
        val quad: DocumentScanner.Quad,
        val autoDetected: Boolean
    )

    /** 已确认、等待保存的页（JPEG 字节 + 尺寸）。 */
    data class ConfirmedPage(val jpeg: ByteArray, val width: Int, val height: Int)

    /** 多选导入时排队等待逐张调整的原图。 */
    private val queue = ArrayDeque<Bitmap>()

    private val _adjust = MutableStateFlow<AdjustState?>(null)
    val adjust: StateFlow<AdjustState?> = _adjust.asStateFlow()

    private val _confirmed = MutableStateFlow<List<ConfirmedPage>>(emptyList())
    val confirmed: StateFlow<List<ConfirmedPage>> = _confirmed.asStateFlow()

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** 收到一张或多张原图：第一张立刻进入调整态，其余排队。 */
    fun enqueue(sources: List<Bitmap>) {
        if (sources.isEmpty()) return
        queue.addAll(sources)
        if (_adjust.value == null) nextFromQueue()
    }

    private fun nextFromQueue() {
        val next = queue.removeFirstOrNull() ?: return
        beginAdjust(next)
    }

    /** 收到一张原图：缩放 → 自动识别四角 → 进入调整态。 */
    private fun beginAdjust(source: Bitmap) {
        viewModelScope.launch {
            _processing.value = true
            val maxEdge = settings.maxEdge.first()
            val autoCrop = settings.autoCrop.first()

            val result = withContext(Dispatchers.Default) {
                val scaled = ImageCompressor.scaleToMaxEdge(source, maxEdge)
                if (scaled !== source) source.recycle()

                val detected = if (autoCrop) {
                    runCatching { DocumentScanner.detect(scaled) }.getOrNull()
                } else {
                    null
                }
                scaled to detected
            }

            _processing.value = false
            val (bitmap, quad) = result
            _adjust.value = AdjustState(
                bitmap = bitmap,
                quad = quad ?: DocumentScanner.fullQuad(bitmap.width, bitmap.height),
                autoDetected = quad != null
            )
        }
    }

    fun updateQuad(quad: DocumentScanner.Quad) {
        _adjust.value = _adjust.value?.copy(quad = quad, autoDetected = true)
    }

    /** 确认当前页：透视拉正 → 压缩 → 收进待保存队列。 */
    fun confirmAdjust() {
        val state = _adjust.value ?: return
        viewModelScope.launch {
            _processing.value = true
            val quality = settings.jpegQuality.first()

            val page = withContext(Dispatchers.Default) {
                val warped = if (isFullFrame(state.quad, state.bitmap)) {
                    state.bitmap
                } else {
                    val w = DocumentScanner.warp(state.bitmap, state.quad)
                    if (w !== state.bitmap) state.bitmap.recycle()
                    w
                }
                val bytes = ImageCompressor.toJpegBytes(warped, quality)
                val result = ConfirmedPage(bytes, warped.width, warped.height)
                warped.recycle()
                result
            }

            _confirmed.value = _confirmed.value + page
            _adjust.value = null
            _processing.value = false
            nextFromQueue()
        }
    }

    /** 放弃当前页。 */
    fun discardAdjust() {
        _adjust.value?.bitmap?.recycle()
        _adjust.value = null
        nextFromQueue()
    }

    fun removeConfirmed(index: Int) {
        val list = _confirmed.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _confirmed.value = list
        }
    }

    fun clearAll() {
        _confirmed.value = emptyList()
    }

    fun saveAll(deviceId: Long, onDone: (Int) -> Unit) {
        val pages = _confirmed.value
        if (pages.isEmpty()) {
            _message.value = "还没有拍下任何页面"
            return
        }
        viewModelScope.launch {
            _saving.value = true
            var saved = 0
            for (page in pages) {
                runCatching {
                    repository.addPageFromJpeg(deviceId, page.jpeg, page.width, page.height)
                }.onSuccess { saved++ }
            }
            _saving.value = false
            _confirmed.value = emptyList()
            onDone(saved)
        }
    }

    fun consumeMessage() { _message.value = null }

    private fun isFullFrame(quad: DocumentScanner.Quad, bitmap: Bitmap): Boolean {
        val w = (bitmap.width - 1).toFloat()
        val h = (bitmap.height - 1).toFloat()
        val tolerance = minOf(bitmap.width, bitmap.height) * 0.02f
        return kotlin.math.abs(quad.tl.x) < tolerance &&
            kotlin.math.abs(quad.tl.y) < tolerance &&
            kotlin.math.abs(quad.tr.x - w) < tolerance &&
            kotlin.math.abs(quad.tr.y) < tolerance &&
            kotlin.math.abs(quad.br.x - w) < tolerance &&
            kotlin.math.abs(quad.br.y - h) < tolerance &&
            kotlin.math.abs(quad.bl.x) < tolerance &&
            kotlin.math.abs(quad.bl.y - h) < tolerance
    }

    override fun onCleared() {
        _adjust.value?.bitmap?.recycle()
        _adjust.value = null
        while (queue.isNotEmpty()) queue.removeFirst().recycle()
        super.onCleared()
    }
}
