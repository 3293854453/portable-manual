package com.portablemanual.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portablemanual.app.di.AppContainer
import com.portablemanual.app.image.ImageCompressor
import com.portablemanual.app.ui.rememberContainerViewModel

@Composable
fun CaptureScreen(
    container: AppContainer,
    deviceId: Long,
    onDone: () -> Unit
) {
    val viewModel = rememberContainerViewModel(key = "capture-$deviceId") {
        CaptureViewModel(it.libraryRepository, it.settingsRepository)
    }

    val adjust by viewModel.adjust.collectAsStateWithLifecycle()
    val confirmed by viewModel.confirmed.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val current = adjust
    if (current != null) {
        AdjustStage(
            state = current,
            pageIndex = confirmed.size + 1,
            processing = processing,
            onQuadChange = viewModel::updateQuad,
            onConfirm = viewModel::confirmAdjust,
            onDiscard = viewModel::discardAdjust
        )
        return
    }

    if (saving) {
        SavingStage(pageCount = confirmed.size)
        return
    }

    CameraStage(
        confirmedCount = confirmed.size,
        processing = processing,
        onCaptured = { bitmap -> viewModel.enqueue(listOf(bitmap)) },
        onPickGallery = { uris ->
            // 相册导入：逐张解码后交给同一条调整流水线
            viewModel.enqueue(
                uris.mapNotNull { uri ->
                    runCatching { ImageCompressor.decodeFromUri(context, uri) }.getOrNull()
                }
            )
        },
        onFinish = { viewModel.saveAll(deviceId) { _ -> onDone() } },
        onCancel = onDone
    )
}

// ------------------------------------------------------------------ 相机

@Composable
private fun CameraStage(
    confirmedCount: Int,
    processing: Boolean,
    onCaptured: (Bitmap) -> Unit,
    onPickGallery: (List<Uri>) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris -> if (uris.isNotEmpty()) onPickGallery(uris) }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(androidx.camera.view.CameraController.IMAGE_CAPTURE)
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
        }
    }

    var flashOn by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, hasPermission) {
        if (hasPermission) {
            runCatching {
                controller.bindToLifecycle(lifecycleOwner)
                cameraReady = true
            }
        }
        onDispose {
            runCatching { controller.unbind() }
            cameraReady = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "需要相机权限才能拍照",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予权限", color = Color.White)
                }
                TextButton(onClick = onCancel) {
                    Text("改用相册导入", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        // 顶部：关闭 / 页数 / 闪光灯
        Row(
            Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "退出", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (confirmedCount > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                ) {
                    Text(
                        "已拍 $confirmedCount 页",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = {
                    flashOn = !flashOn
                    controller.imageCaptureFlashMode =
                        if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                }
            ) {
                Icon(
                    if (flashOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = "闪光灯",
                    tint = Color.White
                )
            }
        }

        // 提示条
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 64.dp)
        ) {
            Text(
                "把说明书平放，尽量拍满画面",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        // 底部控制条
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 相册
            CircleAction(onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = "从相册导入",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 快门
            Box(
                Modifier
                    .size(76.dp)
                    .background(Color.White.copy(alpha = 0.22f), CircleShape)
                    .padding(6.dp)
                    .background(Color.White, CircleShape)
                    .clickable(enabled = hasPermission && !processing) {
                        takePicture(controller, context, onCaptured)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                } else {
                    Icon(
                        Icons.Outlined.AutoFixHigh,
                        contentDescription = "拍照",
                        tint = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // 完成
            CircleAction(
                onClick = onFinish,
                enabled = confirmedCount > 0,
                highlighted = confirmedCount > 0
            ) {
                if (confirmedCount > 0) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "完成",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        "完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

private val PickVisualMediaRequestCompat
    get() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

@Composable
private fun CircleAction(
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(48.dp)
            .background(
                if (highlighted) Color.White else Color.White.copy(alpha = 0.16f),
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun takePicture(
    controller: LifecycleCameraController,
    context: android.content.Context,
    onCaptured: (Bitmap) -> Unit
) {
    controller.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = runCatching { image.toRotatedBitmap() }.getOrNull()
                image.close()
                if (bitmap != null) onCaptured(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                android.util.Log.w("CaptureScreen", "拍照失败", exception)
            }
        }
    )
}

private fun ImageProxy.toRotatedBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

// ------------------------------------------------------------------ 裁剪调整

@Composable
private fun AdjustStage(
    state: CaptureViewModel.AdjustState,
    pageIndex: Int,
    processing: Boolean,
    onQuadChange: (com.portablemanual.app.image.DocumentScanner.Quad) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CropEditor(
            bitmap = state.bitmap,
            quad = state.quad,
            onQuadChange = onQuadChange,
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(top = 64.dp, bottom = 120.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDiscard) {
                Icon(Icons.Outlined.Close, contentDescription = "放弃这一页", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.autoDetected) "已自动识别边界" else "未识别到纸张，手动拖一下吧",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f)
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    val detected = com.portablemanual.app.image.DocumentScanner.detect(state.bitmap)
                    if (detected != null) onQuadChange(detected)
                }
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "重新识别", tint = Color.White)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                onClick = onDiscard,
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.16f),
                contentColor = Color.White,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("重拍", fontWeight = FontWeight.Medium)
                }
            }
            Surface(
                onClick = onConfirm,
                enabled = !processing,
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                contentColor = Color.Black,
                modifier = Modifier.weight(1.4f)
            ) {
                Row(
                    Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (processing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.Black
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "用这一页（第 $pageIndex 页）",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SavingStage(pageCount: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(
                "正在保存 $pageCount 页…",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
