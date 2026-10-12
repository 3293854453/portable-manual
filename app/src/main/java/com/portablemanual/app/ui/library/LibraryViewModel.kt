package com.portablemanual.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.data.local.entity.DeviceWithMeta
import com.portablemanual.app.data.local.entity.FolderWithCount
import com.portablemanual.app.data.local.entity.TagEntity
import com.portablemanual.app.data.local.entity.TagWithCount
import com.portablemanual.app.data.repository.LibraryRepository
import com.portablemanual.app.image.ImageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class LibraryUiState(
    val loading: Boolean = true,
    val query: String = "",
    val folders: List<FolderWithCount> = emptyList(),
    val tags: List<TagWithCount> = emptyList(),
    val devices: List<DeviceWithMeta> = emptyList(),
    val tagsByDevice: Map<Long, List<TagEntity>> = emptyMap(),
    val selectedFolderId: Long? = null,
    val selectedTagId: Long? = null,
    val imageRoot: File = File("/")
) {
    /** 当前文件夹的祖先链，从根到当前。 */
    val breadcrumb: List<FolderWithCount>
        get() {
            val chain = ArrayList<FolderWithCount>()
            var cursor = selectedFolderId
            var guard = 0
            while (cursor != null && guard++ < 64) {
                val folder = folders.firstOrNull { it.folder.id == cursor } ?: break
                chain.add(0, folder)
                cursor = folder.folder.parentId
            }
            return chain
        }

    /** 当前文件夹下的直接子文件夹。 */
    val childFolders: List<FolderWithCount>
        get() = folders
            .filter { it.folder.parentId == selectedFolderId }
            .sortedBy { it.folder.sortOrder }

    val isFiltering: Boolean get() = query.isNotBlank() || selectedTagId != null
}

private data class Filter(val folderId: Long?, val tagId: Long?, val query: String)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: LibraryRepository,
    imageStore: ImageStore
) : ViewModel() {

    private val folderId = MutableStateFlow<Long?>(null)
    private val tagId = MutableStateFlow<Long?>(null)
    private val query = MutableStateFlow("")

    private val filter = combine(folderId, tagId, query) { f, t, q -> Filter(f, t, q) }

    private val devices = filter.flatMapLatest { f ->
        repository.observeDevices(f.folderId, f.tagId, f.query).map { f to it }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        devices,
        repository.observeFolders(),
        repository.observeTags(),
        repository.observeCrossRefs()
    ) { devicePair, folders, tags, crossRefs ->
        val (currentFilter, list) = devicePair
        val tagById = tags.associate { it.tag.id to it.tag }
        val tagsByDevice = crossRefs
            .groupBy({ it.deviceId }, { it.tagId })
            .mapValues { (_, ids) -> ids.mapNotNull { tagById[it] } }

        LibraryUiState(
            loading = false,
            query = currentFilter.query,
            folders = folders,
            tags = tags,
            devices = list,
            tagsByDevice = tagsByDevice,
            selectedFolderId = currentFilter.folderId,
            selectedTagId = currentFilter.tagId,
            imageRoot = imageStore.root
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onQueryChange(value: String) { query.value = value }

    fun selectFolder(id: Long?) { folderId.value = id }

    fun selectTag(id: Long?) { tagId.value = if (tagId.value == id) null else id }

    fun createFolder(name: String, parentId: Long? = folderId.value) {
        viewModelScope.launch { repository.createFolder(name, parentId) }
    }

    fun renameFolder(id: Long, name: String) {
        viewModelScope.launch { repository.renameFolder(id, name) }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            if (folderId.value == id) folderId.value = null
            repository.deleteFolder(id)
        }
    }

    fun deleteDevice(id: Long) {
        viewModelScope.launch { repository.deleteDevice(id) }
    }

    fun moveDeviceToFolder(deviceId: Long, targetFolderId: Long?) {
        viewModelScope.launch {
            val device = repository.getDevice(deviceId) ?: return@launch
            repository.updateDevice(
                id = device.id,
                name = device.name,
                brand = device.brand,
                model = device.model,
                purchaseDate = device.purchaseDate,
                note = device.note,
                folderId = targetFolderId
            )
        }
    }
}
