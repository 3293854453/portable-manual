package com.portablemanual.app.ui.tags

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portablemanual.app.data.local.entity.TagWithCount
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.ui.components.ConfirmDialog
import com.portablemanual.app.ui.components.DeviceCard
import com.portablemanual.app.ui.components.EmptyState
import com.portablemanual.app.ui.components.InputDialog
import com.portablemanual.app.ui.components.TagChip
import com.portablemanual.app.ui.components.tagSwatchColor
import com.portablemanual.app.ui.rememberContainerViewModel
import com.portablemanual.app.ui.theme.Spacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagsScreen(
    container: AppContainer,
    onOpenDevice: (Long) -> Unit
) {
    val viewModel = rememberContainerViewModel { TagsViewModel(it.libraryRepository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var colorTarget by remember { mutableStateOf<TagWithCount?>(null) }
    var deleteTarget by remember { mutableStateOf<TagWithCount?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建标签", fontWeight = FontWeight.Medium) }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            Text(
                text = "标签",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )

            if (state.tags.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Label,
                    title = "还没有标签",
                    subtitle = "标签用来做交叉筛选，比如「已过保」「常用」「厨房」",
                    actionLabel = "新建第一个标签",
                    onAction = { showCreate = true }
                )
            } else {
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            contentPadding = PaddingValues(vertical = Spacing.xs)
                        ) {
                            item {
                                TagChip(
                                    text = "全部",
                                    colorIndex = 0,
                                    selected = state.selectedTagId == null,
                                    onClick = { viewModel.selectTag(null) }
                                )
                            }
                            items(state.tags, key = { it.tag.id }) { node ->
                                Box {
                                    var menuOpen by remember { mutableStateOf(false) }
                                    TagChip(
                                        text = "${node.tag.name} ${node.deviceCount}",
                                        colorIndex = node.tag.colorIndex,
                                        selected = state.selectedTagId == node.tag.id,
                                        modifier = Modifier.combinedClickable(
                                            onClick = { viewModel.selectTag(node.tag.id) },
                                            onLongClick = { menuOpen = true }
                                        )
                                    )
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重命名") },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                renameTarget = node
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("换个深浅") },
                                            leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                colorTarget = node
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除标签") },
                                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                deleteTarget = node
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.visibleDevices.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().height(320.dp)) {
                                EmptyState(
                                    icon = Icons.Outlined.Label,
                                    title = "这个标签下还没有设备",
                                    subtitle = "去设备的编辑页给它打上这个标签"
                                )
                            }
                        }
                    }

                    items(state.visibleDevices, key = { it.device.id }) { meta ->
                        DeviceCard(
                            meta = meta,
                            tags = state.tagsByDevice[meta.device.id].orEmpty(),
                            imageRoot = state.imageRoot,
                            onClick = { onOpenDevice(meta.device.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        InputDialog(
            title = "新建标签",
            label = "标签名称",
            onConfirm = { name ->
                viewModel.createTag(name)
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }

    renameTarget?.let { target ->
        InputDialog(
            title = "重命名标签",
            initialValue = target.tag.name,
            label = "标签名称",
            onConfirm = { name ->
                viewModel.renameTag(target.tag.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    colorTarget?.let { target ->
        TagColorDialog(
            current = target.tag.colorIndex,
            onPick = { index ->
                viewModel.setTagColor(target.tag.id, index)
                colorTarget = null
            },
            onDismiss = { colorTarget = null }
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除标签「${target.tag.name}」？",
            message = "标签会从所有设备上移除，设备本身不受影响。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deleteTag(target.tag.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun TagColorDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择深浅", style = MaterialTheme.typography.titleLarge) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                repeat(com.portablemanual.app.ui.theme.TagPalette.size) { index ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tagSwatchColor(index),
                        border = if (index == current) {
                            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else null,
                        onClick = { onPick(index) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (index == current) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.surface,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}
