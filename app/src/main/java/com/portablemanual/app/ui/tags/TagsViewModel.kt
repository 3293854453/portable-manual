package com.portablemanual.app.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.data.local.entity.DeviceWithMeta
import com.portablemanual.app.data.local.entity.TagEntity
import com.portablemanual.app.data.local.entity.TagWithCount
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.data.repository.TagResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class TagsUiState(
    val tags: List<TagWithCount> = emptyList(),
    val devices: List<DeviceWithMeta> = emptyList(),
    val tagsByDevice: Map<Long, List<TagEntity>> = emptyMap(),
    val selectedTagId: Long? = null,
    val imageRoot: File = File("/")
) {
    val selectedTag: TagWithCount? get() = tags.firstOrNull { it.tag.id == selectedTagId }
    val visibleDevices: List<DeviceWithMeta>
        get() = if (selectedTagId == null) devices else devices.filter { d ->
            tagsByDevice[d.device.id]?.any { it.id == selectedTagId } == true
        }
}

class TagsViewModel(private val repository: LibraryRepository) : ViewModel() {

    private val selectedTagId = MutableStateFlow<Long?>(null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<TagsUiState> = combine(
        repository.observeTags(),
        repository.observeDevices(null, null, ""),
        repository.observeCrossRefs(),
        selectedTagId
    ) { tags, devices, crossRefs, selected ->
        val tagById = tags.associate { it.tag.id to it.tag }
        val tagsByDevice = crossRefs
            .groupBy({ it.deviceId }, { it.tagId })
            .mapValues { (_, ids) -> ids.mapNotNull { tagById[it] } }

        TagsUiState(
            tags = tags,
            devices = devices,
            tagsByDevice = tagsByDevice,
            selectedTagId = selected,
            imageRoot = repository.imageStoreRoot()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagsUiState())

    fun selectTag(id: Long?) {
        selectedTagId.value = if (selectedTagId.value == id) null else id
    }

    fun createTag(name: String) {
        viewModelScope.launch {
            runCatching { repository.createTag(name) }
                .onSuccess { result ->
                    when (result) {
                        is TagResult.Created -> {
                            _message.value = "已创建标签「${name.trim()}」"
                            selectedTagId.value = result.id
                        }
                        is TagResult.Exists -> {
                            _message.value = "标签「${name.trim()}」已存在"
                            selectedTagId.value = result.id
                        }
                        TagResult.Blank -> _message.value = "标签名不能为空"
                        TagResult.Failed -> _message.value = "创建失败，请重试"
                    }
                }
                .onFailure { e ->
                    _message.value = "创建失败：${e.message ?: "数据库错误"}"
                }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun renameTag(id: Long, name: String) {
        viewModelScope.launch { repository.renameTag(id, name) }
    }

    fun setTagColor(id: Long, colorIndex: Int) {
        viewModelScope.launch { repository.setTagColor(id, colorIndex) }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            if (selectedTagId.value == id) selectedTagId.value = null
            repository.deleteTag(id)
        }
    }
}
