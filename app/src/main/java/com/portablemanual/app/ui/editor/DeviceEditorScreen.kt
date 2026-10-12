package com.portablemanual.app.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.ui.components.InputDialog
import com.portablemanual.app.ui.components.TagChip
import com.portablemanual.app.ui.rememberContainerViewModel
import com.portablemanual.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceEditorScreen(
    container: AppContainer,
    deviceId: Long?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val viewModel = rememberContainerViewModel(key = "editor-${deviceId ?: "new"}") {
        DeviceEditorViewModel(it.libraryRepository, deviceId)
    }

    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val initialForm by viewModel.initialForm.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var purchaseDate by remember { mutableStateOf<Long?>(null) }
    var folderId by remember { mutableStateOf<Long?>(null) }
    var selectedTagIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var folderMenuOpen by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showNewTag by remember { mutableStateOf(false) }

    // 编辑模式下把加载到的初值灌进表单，只灌一次
    LaunchedEffect(initialForm) {
        initialForm?.let { form ->
            name = form.name
            brand = form.brand
            model = form.model
            note = form.note
            purchaseDate = form.purchaseDate
            folderId = form.folderId
            selectedTagIds = form.tagIds
        }
    }

    val isEditing = deviceId != null
    val canSave = name.isNotBlank()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isEditing) "编辑设备" else "添加设备",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            viewModel.save(
                                DeviceForm(
                                    name = name,
                                    brand = brand,
                                    model = model,
                                    purchaseDate = purchaseDate,
                                    note = note,
                                    folderId = folderId,
                                    tagIds = selectedTagIds
                                ),
                                onSaved
                            )
                        }
                    ) {
                        Text(
                            "保存",
                            fontWeight = FontWeight.SemiBold,
                            color = if (canSave) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            Spacer(Modifier.height(Spacing.sm))

            FieldLabel("设备名称", required = true)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("比如：小米空气净化器 4 Pro") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("品牌")
                    OutlinedTextField(
                        value = brand,
                        onValueChange = { brand = it },
                        placeholder = { Text("小米") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("型号")
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        placeholder = { Text("AC-M15-SC") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            FieldLabel("购买日期")
            Surface(
                onClick = { showDatePicker = true },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = purchaseDate?.let {
                            SimpleDateFormat("yyyy 年 M 月 d 日", Locale.getDefault()).format(Date(it))
                        } ?: "未填写",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (purchaseDate == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (purchaseDate != null) {
                        IconButton(
                            onClick = { purchaseDate = null },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "清除日期",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            FieldLabel("所属文件夹")
            Surface(
                onClick = { folderMenuOpen = true },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = folders.firstOrNull { it.folder.id == folderId }?.folder?.name ?: "不归类",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        DropdownMenu(
                            expanded = folderMenuOpen,
                            onDismissRequest = { folderMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("不归类") },
                                onClick = {
                                    folderId = null
                                    folderMenuOpen = false
                                }
                            )
                            folders.sortedBy { it.folder.name }.forEach { node ->
                                DropdownMenuItem(
                                    text = { Text(node.folder.name) },
                                    onClick = {
                                        folderId = node.folder.id
                                        folderMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("标签")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showNewTag = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (tags.isEmpty()) {
                Text(
                    "还没有标签，新建一个来交叉筛选吧",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tags.forEach { node ->
                        TagChip(
                            text = node.tag.name,
                            colorIndex = node.tag.colorIndex,
                            selected = node.tag.id in selectedTagIds,
                            onClick = {
                                selectedTagIds = if (node.tag.id in selectedTagIds) {
                                    selectedTagIds - node.tag.id
                                } else {
                                    selectedTagIds + node.tag.id
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            FieldLabel("备注")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("比如滤芯型号、售后电话、拆装注意事项…") },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "备注会参与搜索，值得记的都写这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )

            Spacer(Modifier.height(Spacing.xxl))
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = purchaseDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    purchaseDate = pickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("确定", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }

    if (showNewTag) {
        InputDialog(
            title = "新建标签",
            label = "标签名称",
            onConfirm = { tagName ->
                viewModel.createTag(tagName) { newId ->
                    selectedTagIds = selectedTagIds + newId
                }
                showNewTag = false
            },
            onDismiss = { showNewTag = false }
        )
    }
}

@Composable
private fun FieldLabel(text: String, required: Boolean = false) {
    Row(Modifier.padding(bottom = Spacing.sm)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (required) {
            Text(
                " *",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
