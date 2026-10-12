package com.portablemanual.app.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.portablemanual.app.data.local.entity.PageEntity
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.ui.components.ConfirmDialog
import com.portablemanual.app.ui.components.TagChip
import com.portablemanual.app.ui.rememberContainerViewModel
import com.portablemanual.app.ui.theme.Spacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(
    container: AppContainer,
    deviceId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onCapture: () -> Unit,
    onRead: (Int) -> Unit
) {
    val viewModel = rememberContainerViewModel(key = "detail-$deviceId") {
        DeviceDetailViewModel(
            context = it.appContext,
            repository = it.libraryRepository,
            imageStore = it.imageStore,
            pdfExporter = it.pdfExporter,
            deviceId = deviceId
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var deletePageTarget by remember { mutableStateOf<PageEntity?>(null) }
    var showDeleteDevice by remember { mutableStateOf(false) }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let(viewModel::exportPdf) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val device = state.device?.device

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = device?.name ?: "说明书",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("导出为 PDF") },
                                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pdfLauncher.launch("${device?.name ?: "说明书"}.pdf")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除这台设备") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showDeleteDevice = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    BottomAction(
                        icon = Icons.Outlined.PhotoCamera,
                        label = if (state.pages.isEmpty()) "开始拍照" else "补拍",
                        primary = true,
                        modifier = Modifier.weight(1f),
                        onClick = onCapture
                    )
                    BottomAction(
                        icon = Icons.Outlined.PictureAsPdf,
                        label = "导出 PDF",
                        primary = false,
                        enabled = state.pages.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = { pdfLauncher.launch("${device?.name ?: "说明书"}.pdf") }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            if (device == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = padding.calculateBottomPadding() + Spacing.xl
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DeviceHeader(
                            name = device.name,
                            brand = device.brand,
                            model = device.model,
                            purchaseDate = device.purchaseDate,
                            note = device.note,
                            tags = state.tags,
                            pageCount = state.pages.size,
                            cover = state.pages.firstOrNull()?.fileName,
                            imageRoot = state.imageRoot
                        )
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.md, bottom = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "说明书页面",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                "${state.pages.size} 页",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (state.pages.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.xl),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Outlined.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(Spacing.md))
                                    Text(
                                        "还没有页面",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(
                                        "点下面的「开始拍照」，把说明书一页页拍下来",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                        Box {
                            var pageMenu by remember { mutableStateOf(false) }
                            PageThumbnail(
                                page = page,
                                index = index,
                                imageRoot = state.imageRoot,
                                onClick = { onRead(index) },
                                onLongClick = { pageMenu = true }
                            )
                            DropdownMenu(
                                expanded = pageMenu,
                                onDismissRequest = { pageMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("前移一位") },
                                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                                    enabled = index > 0,
                                    onClick = {
                                        pageMenu = false
                                        viewModel.movePage(page.id, -1)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("后移一位") },
                                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                                    enabled = index < state.pages.size - 1,
                                    onClick = {
                                        pageMenu = false
                                        viewModel.movePage(page.id, 1)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除这一页") },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                    onClick = {
                                        pageMenu = false
                                        deletePageTarget = page
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (state.busy) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.xl)
                ) {
                    Row(
                        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text("正在导出 PDF…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    deletePageTarget?.let { page ->
        val index = state.pages.indexOfFirst { it.id == page.id }
        ConfirmDialog(
            title = "删除第 ${index + 1} 页？",
            message = "这一页的图片会被永久删除。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                viewModel.deletePage(page.id)
                deletePageTarget = null
            },
            onDismiss = { deletePageTarget = null }
        )
    }

    if (showDeleteDevice) {
        ConfirmDialog(
            title = "删除「${device?.name ?: "这台设备"}」？",
            message = "说明书和所有图片都会被永久删除，无法恢复。",
            confirmLabel = "永久删除",
            destructive = true,
            onConfirm = {
                showDeleteDevice = false
                viewModel.deleteDevice(onBack)
            },
            onDismiss = { showDeleteDevice = false }
        )
    }
}

@Composable
private fun DeviceHeader(
    name: String,
    brand: String,
    model: String,
    purchaseDate: Long?,
    note: String,
    tags: List<com.portablemanual.app.data.local.entity.TagEntity>,
    pageCount: Int,
    cover: String?,
    imageRoot: File
) {
    Column {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        ) {
            if (cover != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(imageRoot, cover))
                        .crossfade(true)
                        .build(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        val subtitle = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
        }

        val meta = buildList {
            add("$pageCount 页")
            purchaseDate?.let {
                add("购于 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)))
            }
        }
        Text(
            meta.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                tags.forEach { TagChip(it.name, it.colorIndex) }
            }
        }

        if (note.isNotBlank()) {
            Spacer(Modifier.height(Spacing.md))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(Spacing.md)
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageThumbnail(
    page: PageEntity,
    index: Int,
    imageRoot: File,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(imageRoot, page.fileName))
                    .crossfade(true)
                    .build(),
                contentDescription = "第 ${index + 1} 页",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (primary) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
    ) {
        Row(
            Modifier.padding(vertical = Spacing.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        }
    }
}
