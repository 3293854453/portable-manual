package com.portablemanual.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portablemanual.app.data.local.entity.FolderWithCount
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.ui.components.ConfirmDialog
import com.portablemanual.app.ui.components.DeviceCard
import com.portablemanual.app.ui.components.EmptyState
import com.portablemanual.app.ui.components.InputDialog
import com.portablemanual.app.ui.components.TagChip
import com.portablemanual.app.ui.rememberContainerViewModel
import com.portablemanual.app.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenDevice: (Long) -> Unit,
    onCreateDevice: () -> Unit
) {
    val viewModel = rememberContainerViewModel { LibraryViewModel(it.libraryRepository, it.imageStore) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var searchActive by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<FolderWithCount?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderWithCount?>(null) }
    var deleteDeviceTarget by remember { mutableStateOf<Long?>(null) }
    var moveDeviceTarget by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateDevice,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加设备", fontWeight = FontWeight.Medium) }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            LibraryTopBar(
                searchActive = searchActive,
                query = state.query,
                title = state.breadcrumb.lastOrNull()?.folder?.name ?: "资料库",
                onQueryChange = viewModel::onQueryChange,
                onOpenSearch = { searchActive = true },
                onCloseSearch = {
                    searchActive = false
                    viewModel.onQueryChange("")
                },
                onNewFolder = { showNewFolderDialog = true }
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.sm,
                    bottom = 104.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.fillMaxSize()
            ) {
                if (state.breadcrumb.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        BreadcrumbRow(
                            chain = state.breadcrumb,
                            onNavigate = viewModel::selectFolder
                        )
                    }
                }

                if (state.childFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        FolderChipRow(
                            folders = state.childFolders,
                            onOpen = { viewModel.selectFolder(it.folder.id) },
                            onRename = { renameFolderTarget = it },
                            onDelete = { deleteFolderTarget = it }
                        )
                    }
                }

                if (state.tags.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TagFilterRow(
                            tags = state.tags,
                            selectedTagId = state.selectedTagId,
                            onSelect = viewModel::selectTag
                        )
                    }
                }

                if (state.devices.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(380.dp)) {
                            EmptyState(
                                icon = Icons.Outlined.LibraryBooks,
                                title = if (state.isFiltering) "没有匹配的设备" else "还没有说明书",
                                subtitle = if (state.isFiltering) {
                                    "换个关键词，或清掉标签筛选试试"
                                } else {
                                    "点右下角「添加设备」，拍照存下第一份说明书"
                                },
                                actionLabel = if (state.isFiltering) "清除筛选" else null,
                                onAction = {
                                    viewModel.onQueryChange("")
                                    viewModel.selectTag(null)
                                }
                            )
                        }
                    }
                }

                items(state.devices, key = { it.device.id }) { meta ->
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }

                        DeviceCard(
                            meta = meta,
                            tags = state.tagsByDevice[meta.device.id].orEmpty(),
                            imageRoot = state.imageRoot,
                            onClick = { onOpenDevice(meta.device.id) },
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenDevice(meta.device.id) },
                                onLongClick = { menuOpen = true }
                            )
                        )

                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("移动到文件夹") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DriveFileMove, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    moveDeviceTarget = meta.device.id
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除设备") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    deleteDeviceTarget = meta.device.id
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        InputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            onConfirm = { name ->
                viewModel.createFolder(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    renameFolderTarget?.let { target ->
        InputDialog(
            title = "重命名文件夹",
            initialValue = target.folder.name,
            label = "文件夹名称",
            onConfirm = { name ->
                viewModel.renameFolder(target.folder.id, name)
                renameFolderTarget = null
            },
            onDismiss = { renameFolderTarget = null }
        )
    }

    deleteFolderTarget?.let { target ->
        ConfirmDialog(
            title = "删除文件夹？",
            message = "「${target.folder.name}」及其子文件夹会被删除，里面的设备会移到根目录，不会被删掉。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteFolder(target.folder.id)
                deleteFolderTarget = null
            },
            onDismiss = { deleteFolderTarget = null }
        )
    }

    deleteDeviceTarget?.let { deviceId ->
        val name = state.devices.firstOrNull { it.device.id == deviceId }?.device?.name ?: "这台设备"
        ConfirmDialog(
            title = "删除「$name」？",
            message = "这台设备的说明书和所有图片都会被永久删除，无法恢复。",
            confirmLabel = "永久删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteDevice(deviceId)
                deleteDeviceTarget = null
            },
            onDismiss = { deleteDeviceTarget = null }
        )
    }

    moveDeviceTarget?.let { deviceId ->
        FolderPickerDialog(
            folders = state.folders,
            onPick = { folderId ->
                viewModel.moveDeviceToFolder(deviceId, folderId)
                moveDeviceTarget = null
            },
            onDismiss = { moveDeviceTarget = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    searchActive: Boolean,
    query: String,
    title: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onNewFolder: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchActive) {
        if (searchActive) {
            focusRequester.requestFocus()
        } else {
            keyboard?.hide()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = !searchActive,
            enter = fadeIn(tween(150)) + slideInHorizontally(tween(200)) { -it / 3 },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 3 },
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(
            visible = searchActive,
            enter = fadeIn(tween(150)) + slideInHorizontally(tween(200)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { it / 3 },
            modifier = Modifier.weight(1f)
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("搜名称、品牌、型号、备注") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }

        Spacer(Modifier.width(Spacing.xs))

        if (searchActive) {
            IconButton(onClick = onCloseSearch) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
            }
        } else {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索")
            }
            IconButton(onClick = onNewFolder) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
            }
        }
    }
}

@Composable
private fun BreadcrumbRow(
    chain: List<FolderWithCount>,
    onNavigate: (Long?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        contentPadding = PaddingValues(vertical = Spacing.xs)
    ) {
        item {
            BreadcrumbChip(text = "全部", active = false, onClick = { onNavigate(null) })
        }
        items(chain) { node ->
            val isLast = node.folder.id == chain.last().folder.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                BreadcrumbChip(
                    text = node.folder.name,
                    active = isLast,
                    onClick = { if (!isLast) onNavigate(node.folder.id) }
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbChip(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        onClick = onClick
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderChipRow(
    folders: List<FolderWithCount>,
    onOpen: (FolderWithCount) -> Unit,
    onRename: (FolderWithCount) -> Unit,
    onDelete: (FolderWithCount) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(vertical = Spacing.xs)
    ) {
        items(folders, key = { it.folder.id }) { node ->
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpen(node) },
                        onLongClick = { menuOpen = true }
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = node.folder.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        if (node.deviceCount > 0) {
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = "${node.deviceCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename(node)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除文件夹") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete(node)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TagFilterRow(
    tags: List<com.portablemanual.app.data.local.entity.TagWithCount>,
    selectedTagId: Long?,
    onSelect: (Long?) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(vertical = Spacing.xs)
    ) {
        item {
            TagChip(
                text = "全部标签",
                colorIndex = 0,
                selected = selectedTagId == null,
                onClick = { onSelect(null) }
            )
        }
        items(tags, key = { it.tag.id }) { node ->
            TagChip(
                text = "${node.tag.name}${if (node.deviceCount > 0) " ${node.deviceCount}" else ""}",
                colorIndex = node.tag.colorIndex,
                selected = selectedTagId == node.tag.id,
                onClick = { onSelect(node.tag.id) }
            )
        }
    }
}

@Composable
private fun FolderPickerDialog(
    folders: List<FolderWithCount>,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember(folders) { folders.sortedBy { it.folder.name } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到…", style = MaterialTheme.typography.titleLarge) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.height(320.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    FolderPickRow(name = "根目录（不归类）", depth = 0) { onPick(null) }
                }
                items(sorted, key = { it.folder.id }) { node ->
                    FolderPickRow(name = node.folder.name, depth = depthOf(node.folder.id, folders)) {
                        onPick(node.folder.id)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

private fun depthOf(folderId: Long, folders: List<FolderWithCount>): Int {
    var depth = 0
    var cursor = folders.firstOrNull { it.folder.id == folderId }?.folder?.parentId
    var guard = 0
    while (cursor != null && guard++ < 32) {
        depth++
        cursor = folders.firstOrNull { it.folder.id == cursor }?.folder?.parentId
    }
    return depth
}

@Composable
private fun FolderPickRow(name: String, depth: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                start = (Spacing.md + (depth * 16).dp),
                top = Spacing.md,
                bottom = Spacing.md,
                end = Spacing.md
            )
        )
    }
}
