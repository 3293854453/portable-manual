package com.portablemanual.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portablemanual.app.BuildConfig
import com.portablemanual.app.backup.BackupManager
import com.portablemanual.app.data.prefs.SettingsRepository
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.image.DocumentScanner
import com.portablemanual.app.ui.components.ConfirmDialog
import com.portablemanual.app.ui.rememberContainerViewModel
import com.portablemanual.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBottomBarVisibilityChange: (Boolean) -> Unit = {}
) {
    val viewModel = rememberContainerViewModel {
        SettingsViewModel(it.settingsRepository, it.libraryRepository, it.backupManager)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val quality by viewModel.jpegQuality.collectAsStateWithLifecycle()
    val autoCrop by viewModel.autoCrop.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showPruneConfirm by remember { mutableStateOf(false) }
    var qualityDraft by remember(quality) { mutableStateOf(quality.toFloat()) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportBackup) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { pendingImportUri = it } }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(uiState.busy) {
        onBottomBarVisibilityChange(!uiState.busy)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(vertical = Spacing.sm)
                )

                SettingsSection(title = "外观", icon = Icons.Outlined.Tune) {
                    Text(
                        "主题",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf(
                            SettingsRepository.THEME_SYSTEM to "跟随系统",
                            SettingsRepository.THEME_LIGHT to "浅色",
                            SettingsRepository.THEME_DARK to "深色"
                        )
                        options.forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = themeMode == value,
                                onClick = { viewModel.setTheme(value) },
                                shape = SegmentedButtonDefaults.itemShape(index, options.size)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                SettingsSection(title = "拍照与图像", icon = Icons.Outlined.PhotoCamera) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "自动裁边拉正",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "拍完自动识别纸张四角并拉正",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoCrop, onCheckedChange = viewModel::setAutoCrop)
                    }

                    Spacer(Modifier.height(Spacing.lg))
                    Text(
                        "压缩质量 · $quality",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "越低越省空间，建议 70–85",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = qualityDraft,
                        onValueChange = { qualityDraft = it },
                        onValueChangeFinished = { viewModel.setQuality(qualityDraft.roundToInt()) },
                        valueRange = 40f..100f,
                        steps = 11
                    )
                }

                SettingsSection(title = "存储", icon = Icons.Outlined.Storage) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("设备", "${uiState.deviceCount}")
                        StatItem("页面", "${uiState.pageCount}")
                        StatItem("占用", formatBytes(uiState.storageBytes))
                    }
                    Spacer(Modifier.height(Spacing.md))
                    SettingsActionRow(
                        icon = Icons.Outlined.CleaningServices,
                        title = "清理残留图片",
                        subtitle = "删掉数据库里已经不存在的图片文件"
                    ) { showPruneConfirm = true }
                }

                SettingsSection(title = "备份与迁移", icon = Icons.Outlined.FileUpload) {
                    Text(
                        "本应用完全离线，数据只存在这台手机上。换手机前请务必导出一份备份包。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.md))
                    SettingsActionRow(
                        icon = Icons.Outlined.FileDownload,
                        title = "导出备份",
                        subtitle = "生成一个 .zip，包含全部数据与图片"
                    ) {
                        exportLauncher.launch(defaultBackupFileName())
                    }
                    SettingsActionRow(
                        icon = Icons.Outlined.FileUpload,
                        title = "导入备份",
                        subtitle = "从 .zip 还原资料库"
                    ) {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }

                SettingsSection(title = "关于", icon = Icons.Outlined.Info) {
                    InfoRow("版本", BuildConfig.VERSION_NAME)
                    InfoRow("开源协议", "Apache-2.0")
                    InfoRow("网络权限", "无（完全离线）")
                    InfoRow(
                        "裁剪引擎",
                        DocumentScanner.engineName
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "便携说明书 · 把纸质说明书拍下来，随时能查",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(96.dp))
            }

            if (uiState.busy) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.lg)
                ) {
                    Column(Modifier.padding(Spacing.lg)) {
                        Text(
                            uiState.progressText ?: "处理中…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("怎么还原？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(
                        "合并导入：保留现有数据，追加备份里的内容（相同的设备会跳过）。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "覆盖导入：先清空当前所有数据，再完整还原。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importBackup(uri, BackupManager.ImportMode.MERGE)
                    pendingImportUri = null
                }) { Text("合并导入", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.importBackup(uri, BackupManager.ImportMode.REPLACE)
                    pendingImportUri = null
                }) { Text("覆盖导入", color = MaterialTheme.colorScheme.error) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showPruneConfirm) {
        ConfirmDialog(
            title = "清理残留图片？",
            message = "只会删除数据库里已无记录的图片文件，正常使用的说明书不受影响。",
            confirmLabel = "清理",
            onConfirm = {
                viewModel.pruneOrphans()
                showPruneConfirm = false
            },
            onDismiss = { showPruneConfirm = false }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Column(Modifier.padding(top = Spacing.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(Spacing.lg)) { content() }
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(22.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
    return "便携说明书-备份-$stamp.zip"
}
