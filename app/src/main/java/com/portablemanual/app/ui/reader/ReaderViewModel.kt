package com.portablemanual.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.data.repository.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

data class ReaderUiState(
    val title: String = "",
    val pages: List<PageEntity> = emptyList(),
    val imageRoot: File = File("/")
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    repository: LibraryRepository,
    deviceId: Long
) : ViewModel() {

    val uiState: StateFlow<ReaderUiState> = repository.observeDocument(deviceId)
        .flatMapLatest { document ->
            if (document == null) {
                flowOf(ReaderUiState(imageRoot = repository.imageStoreRoot()))
            } else {
                repository.observePages(document.id).map { pages ->
                    ReaderUiState(
                        title = document.title,
                        pages = pages,
                        imageRoot = repository.imageStoreRoot()
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())
}
