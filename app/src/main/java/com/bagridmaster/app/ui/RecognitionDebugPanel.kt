package com.bagridmaster.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.LaunchedEffect
import com.bagridmaster.app.analysis.itemCode
import com.bagridmaster.app.analysis.cellAddress
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import com.bagridmaster.app.analysis.AnalysisResult
import com.bagridmaster.app.analysis.timingSummary
import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.RgbaPatch
import com.bagridmaster.app.debug.RecognitionDebugStore
import com.bagridmaster.app.debug.RecognitionDebugSnapshot
import com.bagridmaster.app.debug.DebugImageExporter
import com.bagridmaster.app.debug.DebugAnnotationRenderer
import com.bagridmaster.app.debug.debugAnnotations
import com.bagridmaster.app.debug.debugReport
import com.bagridmaster.app.model.AppSettings
import com.bagridmaster.app.overlay.GameOverlayImageRenderer
import com.bagridmaster.app.vision.RgbaFrame
import com.bagridmaster.app.media.toBitmap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Compact, directly visible result for an image chosen from the system picker. */
@Composable
fun SelectedImageHomeResult(snapshot: RecognitionDebugSnapshot, settings: AppSettings) {
    val frame = snapshot.frame ?: return
    val result = snapshot.result ?: return
    var enlarged by remember(snapshot.capturedAtMillis) { mutableStateOf(false) }
    var rotated90 by rememberSaveable(snapshot.capturedAtMillis) { mutableStateOf(false) }
    var annotated by rememberSaveable(snapshot.capturedAtMillis) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    fun export(marked: Boolean) {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                saveMessage = DebugImageExporter.save(
                    context, snapshot, marked, if (rotated90) 1 else 0,
                    gameOverlaySettings = settings,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveMessage = "保存失败：${error.message}"
            } finally { saving = false }
        }
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val pending = pendingExport
        pendingExport = null
        if (allowed && pending != null) export(pending) else saveMessage = "未获存储写入权限，图片未保存"
    }
    fun saveCurrent() {
        if (saving) return
        if (DebugImageExporter.needsLegacyWritePermission(context)) {
            pendingExport = annotated
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else export(annotated)
    }
    val rendered by produceState<Pair<RgbaFrame, Pair<ImageBitmap, ImageBitmap>>?>(null, frame, result, settings) {
        val bitmaps = withContext(Dispatchers.Default) {
            val original = frame.toBitmap()
            val marked = original.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            original to marked
        }
        withContext(Dispatchers.Main.immediate) {
            GameOverlayImageRenderer.draw(context, bitmaps.second, result, settings)
        }
        value = frame to (bitmaps.first.asImageBitmap() to bitmaps.second.asImageBitmap())
        awaitDispose {
            bitmaps.first.recycle()
            bitmaps.second.recycle()
        }
    }
    val bitmaps = rendered?.takeIf { it.first === frame }?.second ?: return
    val originalBitmap = bitmaps.first
    val markedBitmap = bitmaps.second
    Card(Modifier.fillMaxWidth().testTag("selected_image_result")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自行选择图片的识别结果", style = MaterialTheme.typography.titleMedium)
            Text(
                if (result.recommendations.isEmpty()) result.statusText else
                    "建议：" + result.recommendations.joinToString("、") { cellAddress(it.row, it.column) },
                color = MaterialTheme.colorScheme.primary,
            )
            Screenshot(markedBitmap, null, false, Modifier.testTag("selected_image_preview")
                .clickable(onClickLabel = "查看大图") { enlarged = true })
        }
    }
    if (enlarged) {
        Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { enlarged = false }) { Text("关闭") }
                    TextButton(onClick = { annotated = !annotated }) { Text(if (annotated) "原图" else "标注") }
                    TextButton(onClick = { rotated90 = !rotated90 }, modifier = Modifier.testTag("rotate_selected_image")) {
                        Text("旋转")
                    }
                    TextButton(onClick = { saveCurrent() }, enabled = !saving) {
                        Text(if (saving) "保存中…" else if (annotated) "保存标注图" else "保存原图")
                    }
                }
                saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("双击放大点击位置，再次双击复位；支持双指缩放和拖动。旋转只在0°与90°之间切换。",
                    style = MaterialTheme.typography.bodySmall)
                ZoomableScreenshot(
                    if (annotated) markedBitmap else originalBitmap,
                    null,
                    false,
                    if (rotated90) 1 else 0,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun RecognitionDebugPanel() {
    val snapshot by RecognitionDebugStore.latest.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var enlarged by remember { mutableStateOf(false) }
    var annotated by rememberSaveable { mutableStateOf(true) }
    var rotated90 by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<RecognitionDebugSnapshot, Boolean>?>(null) }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    fun export(target: RecognitionDebugSnapshot, marked: Boolean) {
        if (saving) return
        saving = true
        saveMessage = null
        scope.launch {
            try {
                saveMessage = DebugImageExporter.save(context, target, marked, if (rotated90) 1 else 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveMessage = "保存失败：${error.message}"
            } finally { saving = false }
        }
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val pending = pendingExport
        pendingExport = null
        if (allowed && pending != null) export(pending.first, pending.second)
        else saveMessage = "未获存储写入权限，图片未保存"
    }
    val current = snapshot
    val frame = current?.frame
    val rendered by produceState<Pair<RgbaFrame, ImageBitmap>?>(null, frame) {
        value = null
        value = frame?.let { withContext(Dispatchers.Default) { it to it.toBitmap().asImageBitmap() } }
    }
    val bitmap = rendered?.takeIf { it.first === frame }?.second
    val saveCurrent = {
        if (current?.frame != null && !saving) {
            if (DebugImageExporter.needsLegacyWritePermission(context)) {
                pendingExport = current to annotated
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else export(current, annotated)
        }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("识别调试", style = MaterialTheme.typography.titleLarge)
            Text("保留最近一次截图及结果（包括失败）。可手动保存原图/标注图到相册；不会自动保存或上传。", style = MaterialTheme.typography.bodySmall)
            if (current == null) {
                Text("暂无记录。进入游戏后点击悬浮按钮，再返回此处查看。")
            } else {
                val time = remember(current.capturedAtMillis) {
                    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(current.capturedAtMillis))
                }
                Text("$time · ${current.algorithm}\n${current.result?.timingSummary() ?: "总耗时：${current.elapsedMs} ms"}\n${current.message}")
                Text(current.inputDescription, style = MaterialTheme.typography.bodySmall)
                if (frame != null && bitmap != null) {
                    Text("原图 ${frame.width}×${frame.height}；S=勾选 E=空 L=点亮 C=完成剪影 ?=待确认", style = MaterialTheme.typography.bodySmall)
                    Screenshot(bitmap, current.result, annotated, Modifier.testTag("result_thumbnail").clickable(onClickLabel = "查看大图") { enlarged = true })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { annotated = !annotated }) { Text(if (annotated) "查看原图" else "显示标注") }
                        TextButton(onClick = saveCurrent, enabled = !saving) { Text(if (saving) "保存中…" else if (annotated) "保存标注图" else "保存原图") }
                    }
                }
                saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val result = current.result
                if (result != null) {
                    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "收起完整信息" else "展开所有识别信息") }
                    if (showDetails) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            result.inventory.forEach { item ->
                                val patch = item.spriteTemplate
                                val sprite = remember(patch) { RgbaFrame(patch.width, patch.height, patch.width * 4, patch.rgba8888, 0).toBitmap().asImageBitmap() }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(sprite, "物品${itemCode(item.index)}采集贴图", Modifier.size(72.dp))
                                    Text("物品${itemCode(item.index)}")
                                }
                            }
                        }
                        Text("贴图预旋转 / 预分割", style = MaterialTheme.typography.titleSmall)
                        Text("以下是用于匹配的水平参考，不是棋盘实际摆放；分格按行编号。", style = MaterialTheme.typography.bodySmall)
                        result.inventory.forEach { TemplatePreprocessingPreview(it) }
                        val report = remember(result) { result.debugReport() }
                        SelectionContainer { Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                TextButton(onClick = { enlarged = false; RecognitionDebugStore.clear() }) { Text("清空调试记录") }
            }
        }
    }
    if (enlarged && bitmap != null) {
        Dialog(onDismissRequest = { enlarged = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { enlarged = false }) { Text("关闭") }
                    TextButton(onClick = { annotated = !annotated }) { Text(if (annotated) "原图" else "标注") }
                    TextButton(onClick = { rotated90 = !rotated90 }, modifier = Modifier.testTag("rotate_result_image")) {
                        Text("旋转")
                    }
                    TextButton(onClick = saveCurrent, enabled = !saving) { Text(if (saving) "保存中…" else if (annotated) "保存标注图" else "保存原图") }
                }
                saveMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("双击放大点击位置，再次双击复位；支持双指缩放和拖动。旋转只在0°与90°之间切换。", style = MaterialTheme.typography.bodySmall)
                ZoomableScreenshot(bitmap, current?.result, annotated, if (rotated90) 1 else 0, Modifier.weight(1f))

            }
        }
    }
}

@Composable
internal fun TemplatePreprocessingPreview(item: ItemCardRecognition) {
    val preview = item.templatePreview
    Column(Modifier.fillMaxWidth().testTag("template_preview_${itemCode(item.index)}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("物品${itemCode(item.index)}：${preview?.explanation ?: "本次无预处理记录"}", style = MaterialTheme.typography.bodySmall)
        if (preview?.aligned != null) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF263746))) {
                PatchImage(preview.aligned, "物品${itemCode(item.index)}预旋转参考",
                    Modifier.fillMaxWidth().aspectRatio(preview.columns.toFloat() / preview.rows))
                Canvas(Modifier.matchParentSize()) {
                    for (c in 1 until preview.columns) drawLine(Color.Cyan,
                        androidx.compose.ui.geometry.Offset(size.width * c / preview.columns, 0f),
                        androidx.compose.ui.geometry.Offset(size.width * c / preview.columns, size.height))
                    for (r in 1 until preview.rows) drawLine(Color.Cyan,
                        androidx.compose.ui.geometry.Offset(0f, size.height * r / preview.rows),
                        androidx.compose.ui.geometry.Offset(size.width, size.height * r / preview.rows))
                }
            }
            // Logical rows never wrap into each other; narrow screens can scroll the whole grid.
            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview.tiles.chunked(preview.columns.coerceAtLeast(1)).forEachIndexed { row, tiles ->
                    Row(Modifier.testTag("template_${itemCode(item.index)}_row_${row + 1}"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.forEachIndexed { column, tile ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PatchImage(tile, "物品${itemCode(item.index)}分格${row * preview.columns + column + 1}", Modifier.size(48.dp).background(Color(0xFF263746)))
                                Text("${row + 1}-${column + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchImage(patch: RgbaPatch, label: String, modifier: Modifier) {
    val bitmap = remember(patch) { patch.toPreviewBitmap().asImageBitmap() }
    Image(bitmap, label, modifier)
}

/** Captured screenshots are opaque, while segmented preview patches carry an alpha mask. */
internal fun RgbaPatch.toPreviewBitmap(): android.graphics.Bitmap {
    val colors = IntArray(width * height) { i ->
        ((rgba8888[i * 4 + 3].toInt() and 255) shl 24) or
            ((rgba8888[i * 4].toInt() and 255) shl 16) or
            ((rgba8888[i * 4 + 1].toInt() and 255) shl 8) or (rgba8888[i * 4 + 2].toInt() and 255)
    }
    return createBitmap(width, height).apply { setPixels(colors, 0, width, 0, 0, width, height) }
}

@Composable
private fun Screenshot(bitmap: ImageBitmap, result: AnalysisResult?, annotated: Boolean, modifier: Modifier = Modifier,
    quarterTurns: Int = 0) {
    val annotations = remember(result) { result?.debugAnnotations().orEmpty() }
    val turns = kotlin.math.abs(quarterTurns) % 2
    val rotatedWidth = if (turns == 0) bitmap.width else bitmap.height
    val rotatedHeight = if (turns == 0) bitmap.height else bitmap.width
    Canvas(modifier.fillMaxWidth().aspectRatio(rotatedWidth.toFloat() / rotatedHeight)) {
        val canvas = drawContext.canvas.nativeCanvas
        val scale = minOf(size.width / rotatedWidth, size.height / rotatedHeight)
        canvas.save()
        canvas.translate(size.width / 2f, size.height / 2f)
        canvas.rotate(turns * 90f)
        canvas.scale(scale, scale)
        canvas.translate(-bitmap.width / 2f, -bitmap.height / 2f)
        canvas.drawBitmap(bitmap.asAndroidBitmap(), 0f, 0f, null)
        if (annotated) {
            DebugAnnotationRenderer.draw(canvas, annotations, bitmap.width, bitmap.height)
        }
        canvas.restore()
    }
}

@Composable
internal fun ZoomableScreenshot(bitmap: ImageBitmap, result: AnalysisResult?, annotated: Boolean,
    quarterTurns: Int = 0, modifier: Modifier = Modifier) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val turns = kotlin.math.abs(quarterTurns) % 2
    val naturalWidth = if (turns == 0) bitmap.width else bitmap.height
    val naturalHeight = if (turns == 0) bitmap.height else bitmap.width
    var zoomState by remember(bitmap, turns) { mutableStateOf(ImageZoomState()) }
    val density = LocalDensity.current
    val fit = if (viewport.width > 0 && viewport.height > 0)
        minOf(viewport.width.toFloat() / naturalWidth, viewport.height.toFloat() / naturalHeight) else 0f
    val imageWidth = naturalWidth * fit
    val imageHeight = naturalHeight * fit
    LaunchedEffect(bitmap, viewport, turns) { zoomState = ImageZoomState() }
    Box(modifier.fillMaxWidth().clipToBounds().testTag("zoom_image")
        .semantics { stateDescription = "缩放 ${zoomState.scale}" }.onSizeChanged { viewport = it }
        .pointerInput(bitmap, viewport, turns) {
            detectTapGestures(onDoubleTap = { position ->
                if (zoomState.scale <= 1f &&
                    (kotlin.math.abs(position.x - size.width / 2f) > imageWidth / 2f ||
                        kotlin.math.abs(position.y - size.height / 2f) > imageHeight / 2f)) return@detectTapGestures
                zoomState = if (zoomState.scale > 1f) ImageZoomState() else
                    zoomState.transform(position.x - size.width / 2f, position.y - size.height / 2f,
                        3f, 0f, 0f, imageWidth, imageHeight, size.width.toFloat(), size.height.toFloat())
            })
        }
        .pointerInput(bitmap, viewport, turns) {
            detectTransformGestures { centroid, movement, zoom, _ ->
                zoomState = zoomState.transform(centroid.x - size.width / 2f, centroid.y - size.height / 2f,
                    zoom, movement.x, movement.y, imageWidth, imageHeight, size.width.toFloat(), size.height.toFloat())
            }
        }, contentAlignment = Alignment.Center) {
        if (fit > 0f) Box(Modifier.size(with(density) { imageWidth.toDp() }, with(density) { imageHeight.toDp() })) {
            Screenshot(bitmap, result, annotated, Modifier.graphicsLayer {
                scaleX = zoomState.scale; scaleY = zoomState.scale
                translationX = zoomState.x; translationY = zoomState.y
            }, turns)
        }
    }
}
