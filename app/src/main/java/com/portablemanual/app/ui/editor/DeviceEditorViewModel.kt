package com.portablemanual.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.data.local.entity.FolderWithCount
import com.portablemanual.app.data.local.entity.TagWithCount
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.data.repository.TagResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceForm(
    val name: String = "",
    val brand: String = "",
    val model: String = "",
    val purchaseDate: Long? = null,
    val note: String = "",
    val folderId: Long? = null,
    val tagIds: Set<Long> = emptySet()
)

class DeviceEditorViewModel(
    private val repository: LibraryRepository,
    private val deviceId: Long?
) : ViewModel() {

    val folders: StateFlow<List<FolderWithCount>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagWithCount>> = repository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _initialForm = MutableStateFlow<DeviceForm?>(null)
    val initialForm: StateFlow<DeviceForm?> = _initialForm.asStateFlow()

    private val _ready = MutableStateFlow(deviceId == null)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        if (deviceId != null) {
            viewModelScope.launch {
                val device = repository.getDevice(deviceId)
                val deviceTags = repository.getTagsForDevice(deviceId)
                _initialForm.value = DeviceForm(
                    name = device?.name.orEmpty(),
                    brand = device?.brand.orEmpty(),
                    model = device?.model.orEmpty(),
                    purchaseDate = device?.purchaseDate,
                    note = device?.note.orEmpty(),
                    folderId = device?.folderId,
                    tagIds = deviceTags.map { it.id }.toSet()
                )
                _ready.value = true
            }
        }
    }

    fun createTag(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.createTag(name) }.onSuccess { result ->
                when (result) {
                    is TagResult.Created -> onCreated(result.id)
                    // 重名时也把已有标签选上，而不是静默什么都不做
                    is TagResult.Exists -> onCreated(result.id)
                    TagResult.Blank, TagResult.Failed -> Unit
                }
            }
        }
    }

    fun save(form: DeviceForm, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val id = if (deviceId == null) {
                repository.createDevice(
                    name = form.name,
                    brand = form.brand,
                    model = form.model,
                    purchaseDate = form.purchaseDate,
                    note = form.note,
                    folderId = form.folderId
                )
            } else {
                repository.updateDevice(
                    id = deviceId,
                    name = form.name,
                    brand = form.brand,
                    model = form.model,
                    purchaseDate = form.purchaseDate,
                    note = form.note,
                    folderId = form.folderId
                )
                deviceId
            }
            repository.setDeviceTags(id, form.tagIds)
            onDone(id)
        }
    }
}
