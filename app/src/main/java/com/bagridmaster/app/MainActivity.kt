package com.bagridmaster.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bagridmaster.app.capture.CaptureRuntimeState
import com.bagridmaster.app.analysis.AnalysisRequest
import com.bagridmaster.app.analysis.CvAnalysisEngine
import com.bagridmaster.app.assistant.AssistantController
import com.bagridmaster.app.assistant.AssistantStatus
import com.bagridmaster.app.data.SettingsRepository
import com.bagridmaster.app.model.AppSettings
import com.bagridmaster.app.model.MarkerColor
import com.bagridmaster.app.model.OverlayContentMode
import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.media.GalleryPermissions
import com.bagridmaster.app.overlay.OverlayRuntimeState
import com.bagridmaster.app.ui.AppNavigation
import com.bagridmaster.app.ui.SettingsPage
import com.bagridmaster.app.ui.SettingsEntry
import com.bagridmaster.app.ui.ImageSourceDropdown
import com.bagridmaster.app.ui.AboutPage
import com.bagridmaster.app.ui.AppTab
import com.bagridmaster.app.ui.BoardCalibrationCard
import com.bagridmaster.app.ui.theme.BAGridMasterTheme
import com.bagridmaster.app.ui.RecognitionDebugPanel
import com.bagridmaster.app.ui.SelectedImageHomeResult
import com.bagridmaster.app.debug.RecognitionDebugStore
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private lateinit var repository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)

        setContent {
            BAGridMasterTheme {
                val settings by remember { repository.settings.map<AppSettings, AppSettings?> { it } }.collectAsStateWithLifecycle(
                    initialValue = null,
                )
                val overlayRunning by OverlayRuntimeState.isRunning.collectAsStateWithLifecycle()
                val captureActive by CaptureRuntimeState.isActive.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var changingInput by remember { mutableStateOf(false) }
                val loadedSettings = settings
                if (loadedSettings == null) {
                    Text("正在加载设置…", Modifier.padding(24.dp))
                    return@BAGridMasterTheme
                }

                AppRoot(
                    settings = loadedSettings,
                    overlayRunning = overlayRunning,
                    captureActive = captureActive,
                    changingInput = changingInput,
                    onBubbleSize = { scope.launch { repository.setBubbleSize(it) } },
                    onBubbleOpacity = { scope.launch { repository.setBubbleOpacity(it) } },
                    onOverlayContentMode = {
                        scope.launch { repository.setOverlayContentMode(it) }
                    },
                    onTemporaryHideSeconds = { scope.launch { repository.setTemporaryHideSeconds(it) } },
                    onSnapToEdge = { scope.launch { repository.setSnapToEdge(it) } },
                    onHaptics = { scope.launch { repository.setHapticsEnabled(it) } },
                    onCaptureDelay = { scope.launch { repository.setCaptureDelayMs(it) } },
                    onMarkerColor = { scope.launch { repository.setMarkerColor(it) } },
                    onMarkerStroke = { scope.launch { repository.setMarkerStroke(it) } },
                    onStrategyAlgorithm = {
                        scope.launch { repository.setStrategyAlgorithm(it) }
                    },
                    onShowBoardHeaders = { scope.launch { repository.setShowBoardHeaders(it) } },
                    onShowKnownObjects = { scope.launch { repository.setShowKnownObjects(it) } },
                    onShowInventoryInfo = { scope.launch { repository.setShowInventoryInfo(it) } },
                    onExperienceCalibration = { scope.launch { repository.setExperienceCalibrationEnabled(it) } },
                    onBlackBorderDetection = { scope.launch { repository.setBlackBorderDetectionEnabled(it) } },
                    onImageInputMode = { mode ->
                        AssistantController.stop(this)
                        changingInput = true
                        scope.launch {
                            try { repository.setImageInputMode(mode) }
                            catch (error: java.io.IOException) {
                                Toast.makeText(this@MainActivity, "图片来源保存失败，请重试", Toast.LENGTH_LONG).show()
                            }
                            finally { changingInput = false }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun AppRoot(
    settings: AppSettings,
    overlayRunning: Boolean,
    captureActive: Boolean,
    changingInput: Boolean,
    onBubbleSize: (Float) -> Unit,
    onBubbleOpacity: (Float) -> Unit,
    onOverlayContentMode: (OverlayContentMode) -> Unit,
    onTemporaryHideSeconds: (Int) -> Unit = {},
    onSnapToEdge: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onCaptureDelay: (Int) -> Unit,
    onMarkerColor: (MarkerColor) -> Unit,
    onMarkerStroke: (Float) -> Unit,
    onStrategyAlgorithm: (StrategyAlgorithm) -> Unit,
    onShowBoardHeaders: (Boolean) -> Unit,
    onShowKnownObjects: (Boolean) -> Unit,
    onImageInputMode: (ImageInputMode?) -> Unit,
    onShowInventoryInfo: (Boolean) -> Unit = {},
    onExperienceCalibration: (Boolean) -> Unit = {},
    onBlackBorderDetection: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val assistantStatus by AssistantController.status.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var permissionJump by rememberSaveable { mutableIntStateOf(0) }
    var instructionsExpanded by rememberSaveable { mutableStateOf(false) }
    var captureRequestId by rememberSaveable { mutableLongStateOf(-1L) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var enableGalleryAfterGrant by rememberSaveable { mutableStateOf(false) }
    var manualAnalyzing by remember { mutableStateOf(false) }
    val manualScope = rememberCoroutineScope()
    // Keep the existing live/gallery modes at their old memory footprint. The second engine is
    // created only after the user actually confirms a document in the picker.
    val manualEngine = remember(context) { lazy(LazyThreadSafetyMode.NONE) { CvAnalysisEngine(context.applicationContext) } }
    DisposableEffect(manualEngine) { onDispose { if (manualEngine.isInitialized()) manualEngine.value.close() } }
    val latestRecognition by RecognitionDebugStore.latest.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionRefresh++
    }
    val overlayAllowed = remember(permissionRefresh) { Settings.canDrawOverlays(context) }
    val galleryAllowed = remember(permissionRefresh) { GalleryPermissions.hasFullAccess(context) }
    val galleryPartial = remember(permissionRefresh) { GalleryPermissions.hasPartialAccess(context) }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++
        if (GalleryPermissions.hasFullAccess(context)) {
            if (enableGalleryAfterGrant) onImageInputMode(ImageInputMode.LATEST_PHOTO)
        } else {
            Toast.makeText(context, "自动读取最新图片需要允许访问所有照片；仅选择部分照片不足以启用此模式", Toast.LENGTH_LONG).show()
        }
        enableGalleryAfterGrant = false
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionRefresh++ }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        AssistantController.consentResult(context, captureRequestId, result.resultCode, result.data)
    }

    val selectedImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            manualAnalyzing = true
            val metrics = context.resources.displayMetrics
            manualScope.launch {
                try {
                    manualEngine.value.analyze(
                        AnalysisRequest(
                            captureDelayMs = settings.captureDelayMs,
                            includeAlternativeCandidates = settings.showTopCandidates,
                            screenWidthPx = metrics.widthPixels,
                            screenHeightPx = metrics.heightPixels,
                            strategyAlgorithm = settings.strategyAlgorithm,
                            imageInputMode = ImageInputMode.SELECTED_PHOTO,
                            selectedImageUri = uri.toString(),
                            experienceCalibrationEnabled = settings.experienceCalibrationEnabled,
                            blackBorderDetectionEnabled = settings.blackBorderDetectionEnabled,
                        ),
                    )
                } catch (error: Exception) {
                    Toast.makeText(context, error.message ?: "识别失败，请重新选择图片", Toast.LENGTH_LONG).show()
                } finally {
                    manualAnalyzing = false
                }
            }
        }
    }

    val requestOverlayPermission = {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri(),
            ),
        )
    }

    val openApplicationPermissions = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }

    val startAssistant = {
        if (!settings.hasSelectedImageSource) {
            Toast.makeText(context, "请选择识别来源", Toast.LENGTH_SHORT).show()
        } else if (settings.imageInputMode == ImageInputMode.LATEST_PHOTO && !GalleryPermissions.hasFullAccess(context)) {
            Toast.makeText(context, "请先在设置中允许访问所有照片", Toast.LENGTH_LONG).show()
            selectedTab = AppTab.SETTINGS
            settingsPage = SettingsPage.PERMISSIONS
            permissionJump++
        } else {
            val id = AssistantController.begin(context, settings.imageInputMode)
            if (settings.imageInputMode == ImageInputMode.SCREEN_CAPTURE) {
                captureRequestId = id
                runCatching {
                    val manager = context.getSystemService(MediaProjectionManager::class.java)
                    captureLauncher.launch(manager.createScreenCaptureIntent())
                }.onFailure { AssistantController.fail(context, id, "获取屏幕授权失败：无法打开系统授权窗口") }
            }
        }
    }

    AppNavigation(selectedTab, { selectedTab = it }, permissionJump,
        detailTitle = settingsPage?.title?.takeIf { selectedTab == AppTab.SETTINGS },
        onBack = { settingsPage = null },
    ) { tab ->
        if (tab == AppTab.HOME) {
            StatusHero(
                overlayRunning = overlayRunning,
                captureActive = captureActive,
                overlayAllowed = overlayAllowed,
                imageInputMode = settings.imageInputMode.takeIf { settings.hasSelectedImageSource },
                status = assistantStatus,
                changingInput = changingInput,
                manualAnalyzing = manualAnalyzing,
                onToggleOverlay = {
                    if (settings.imageInputMode == ImageInputMode.SELECTED_PHOTO) selectedImageLauncher.launch(arrayOf("image/*"))
                    else if (OverlayRuntimeState.isRunning.value || AssistantController.status.value.starting) AssistantController.stop(context)
                    else if (overlayAllowed) startAssistant()
                },
                onImageInputMode = { mode ->
                    if (mode != ImageInputMode.LATEST_PHOTO || GalleryPermissions.hasFullAccess(context)) {
                        onImageInputMode(mode)
                    } else {
                        enableGalleryAfterGrant = true
                        galleryPermissionLauncher.launch(GalleryPermissions.requestedPermissions())
                    }
                },
            )
            if (settings.imageInputMode == ImageInputMode.SELECTED_PHOTO) {
                latestRecognition?.takeIf { it.inputDescription.startsWith("自行选择：") && it.result != null && it.frame != null }
                    ?.let { SelectedImageHomeResult(it, settings) }
            }
            OutlinedButton(onClick = {
                selectedTab = AppTab.SETTINGS
                settingsPage = SettingsPage.PERMISSIONS
                permissionJump++
            }) { Text("权限管理 →") }
            OutlinedButton(onClick = { instructionsExpanded = !instructionsExpanded }) {
                Text(if (instructionsExpanded) "使用说明 ▴" else "使用说明 ▾")
            }
            if (instructionsExpanded) SettingsCard {
                Text("1. 到权限管理允许悬浮窗显示；使用相册识别时，请允许访问全部照片。")
                Text("2. 屏幕识别：选择屏幕捕获，点击启动并同意屏幕捕获；在游戏内点悬浮按钮识别。取消屏幕共享会自动停止助手。")
                Text("3. 最新截图：选择最新截图并启动助手。每轮先点「清屏」，然后手动截图当前画面，再点击「识别」。")
                Text("点「清屏」后面板不透明度降至10%持续3秒，以便截图，但「识别」按钮在清屏后0.5秒就可点击，无需等待不透明度恢复。")
                Text("出现「识别」按钮后，也可点其下方的「临时隐藏」；悬浮窗会在检测到新截图或达到设置时长后恢复。")
                Text("只读取清屏后新保存的截图。长按按钮可再次清屏；请把面板移出棋盘与物品区域，不要截取相册中的标注图、裁剪图或长截图。")
                Text("4. 自行选择：点击「选择图片」，从系统窗口确认一张图片；完成后会在首页直接显示带推荐标注的原图。")
                Text("5. 到「结果信息」查看识别详情、原图或标注图，也可以保存图片。推荐仅供参考，请核对游戏画面后操作。")
            }
        }
        if (tab == AppTab.RESULTS) RecognitionDebugPanel()
        if (tab == AppTab.SETTINGS) {
            if (settingsPage == null) {
                SettingsPage.entries.forEach { page ->
                    SettingsEntry(page.title, page.description, onClick = { settingsPage = page })
                    if (page != SettingsPage.ABOUT) HorizontalDivider()
                }
            }
            if (settingsPage == SettingsPage.ABOUT) AboutPage()
            if (settingsPage == SettingsPage.PERMISSIONS) {
                SectionTitle("权限管理", "屏幕捕获授权已并入首页的悬浮助手启动按钮")
                PermissionCard(
                    title = "显示在其他应用上层",
                    description = "用于悬浮按钮和推荐格标记",
                    granted = overlayAllowed,
                    actionLabel = if (overlayAllowed) "已授权" else "去授权",
                    onAction = requestOverlayPermission,
                )
                PermissionCard(
                    title = "相册权限",
                    description = if (galleryPartial && !galleryAllowed) "仅部分照片；读取最新截图需允许全部照片" else "用于自动读取相册最新截图",
                    granted = galleryAllowed,
                    actionLabel = if (galleryAllowed) "已授权" else "去授权",
                    onAction = { galleryPermissionLauncher.launch(GalleryPermissions.requestedPermissions()) },
                )
                SettingsEntry("应用权限设置", "打开系统设置，查看或修改应用的全部权限") {
                    openApplicationPermissions()
                }
            }
            if (settingsPage == SettingsPage.OVERLAY) {
                SectionTitle("悬浮窗", "显示密度和外观会实时同步到游戏内")
                SettingsCard {
                    Text(
                        "信息显示",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OverlayContentMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.overlayContentMode == mode,
                                onClick = { onOverlayContentMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text(
                        settings.overlayContentMode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    SettingSlider(
                        title = "触发按钮大小",
                        valueLabel = "${settings.bubbleSizeDp.roundToInt()} dp",
                        value = settings.bubbleSizeDp,
                        range = 30f..75f,
                        onValueChange = onBubbleSize,
                    )
                    SettingSlider(
                        title = "按钮透明度",
                        valueLabel = "${(settings.bubbleOpacity * 100).roundToInt()}%",
                        value = settings.bubbleOpacity,
                        range = 0.45f..1f,
                        onValueChange = onBubbleOpacity,
                    )
                    SettingSlider(
                        title = "临时隐藏时间",
                        valueLabel = "${settings.temporaryHideSeconds} 秒",
                        value = settings.temporaryHideSeconds.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onValueChange = { onTemporaryHideSeconds(it.roundToInt()) },
                    )
                    Text(
                        "仅用于最新截图模式；检测到新截图时会提前恢复悬浮窗。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingSwitch(
                        title = "拖动后吸附边缘",
                        subtitle = "自动吸附到屏幕上、左、右三侧",
                        checked = settings.snapToEdge,
                        onCheckedChange = onSnapToEdge,
                    )
                    SettingSwitch(
                        title = "点击震动反馈",
                        subtitle = "确认已经触发一次分析",
                        checked = settings.hapticsEnabled,
                        onCheckedChange = onHaptics,
                    )
                }
            }
            if (settingsPage == SettingsPage.ANNOTATIONS) {
                SectionTitle("游戏覆盖标注", "仅影响画面显示，不影响计算；覆盖层不拦截游戏点击")
                SettingsCard {
                    SettingSwitch(
                        title = "行列刻度",
                        subtitle = "显示水平 A–I、垂直 1–5 和网格辅助线",
                        checked = settings.showBoardHeaders,
                        onCheckedChange = onShowBoardHeaders,
                    )
                    SettingSwitch(
                        title = "已确定物品位置标注",
                        subtitle = "a/b/c 对应左下物品顺序；标出点亮、全开和灰框空格，全开物品整块标注",
                        checked = settings.showKnownObjects,
                        onCheckedChange = onShowKnownObjects,
                    )
                    SettingSwitch(
                        title = "左下物品信息",
                        subtitle = "在物品贴图下侧显示 a/b/c 的形状及剩余数量/总数",
                        checked = settings.showInventoryInfo,
                        onCheckedChange = onShowInventoryInfo,
                    )
                    HorizontalDivider()
                    SettingSlider(
                        title = "推荐框线宽",
                        valueLabel = "${settings.markerStrokeDp.roundToInt()} dp",
                        value = settings.markerStrokeDp,
                        range = 2f..8f,
                        onValueChange = onMarkerStroke,
                    )
                    Text(
                        "推荐框颜色",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MarkerColor.entries.forEach { color ->
                            FilterChip(
                                selected = settings.markerColor == color,
                                onClick = { onMarkerColor(color) },
                                label = { Text(color.label) },
                                leadingIcon = {
                                    Spacer(
                                        Modifier
                                            .size(12.dp)
                                            .background(Color(color.argb), CircleShape),
                                    )
                                },
                            )
                        }
                    }
                    Text("推荐框独立保留；自行选择图片的结果图使用以上配置，结果信息中的调试标注不受影响。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (settingsPage == SettingsPage.RECOGNITION) {
                SectionTitle("截图与识别", "适用于屏幕捕获和相册截图")
                SettingsCard {
                    SettingSwitch(
                        title = "经验坐标校正",
                        subtitle = "关闭后使用每次现场检测，并暂停经验坐标学习；已保存记录不会删除",
                        checked = settings.experienceCalibrationEnabled,
                        onCheckedChange = onExperienceCalibration,
                    )
                    SettingSwitch(
                        title = "画面黑边检测",
                        subtitle = "任一边检测到至少5px连续黑边时，仅本次停用经验校正和记录",
                        checked = settings.blackBorderDetectionEnabled,
                        enabled = settings.experienceCalibrationEnabled,
                        onCheckedChange = onBlackBorderDetection,
                    )
                }
                BoardCalibrationCard()
                SettingsCard {
                    Text(
                        "探索算法",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StrategyAlgorithm.entries.forEach { algorithm ->
                            FilterChip(
                                selected = settings.strategyAlgorithm == algorithm,
                                onClick = { onStrategyAlgorithm(algorithm) },
                                label = { Text(algorithm.label) },
                            )
                        }
                    }
                    Text(
                        settings.strategyAlgorithm.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    SettingSlider(
                        title = "截图等待时间",
                        valueLabel = "${settings.captureDelayMs} ms",
                        value = settings.captureDelayMs.toFloat(),
                        range = 0f..400f,
                        onValueChange = { onCaptureDelay((it / 25f).roundToInt() * 25) },
                    )

                }
                CvPipelineCard()
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun StatusHero(
    overlayRunning: Boolean,
    captureActive: Boolean,
    overlayAllowed: Boolean,
    imageInputMode: ImageInputMode?,
    status: AssistantStatus,
    changingInput: Boolean,
    manualAnalyzing: Boolean = false,
    onToggleOverlay: () -> Unit,
    onImageInputMode: (ImageInputMode?) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    imageInputMode == ImageInputMode.SELECTED_PHOTO && manualAnalyzing -> "正在识别所选图片"
                    imageInputMode == ImageInputMode.SELECTED_PHOTO -> "选择一张图片进行识别"
                    status.starting -> "正在启动悬浮助手"
                    overlayRunning -> "悬浮助手正在运行"
                    else -> "悬浮助手尚未启动"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (imageInputMode == null) "请授权悬浮显示、相册权限（全部始终允许），然后选择识别来源。"
                else if (imageInputMode == ImageInputMode.SELECTED_PHOTO) "点击「选择图片」，在系统窗口中确认图片；识别结果会直接显示在首页。"
                else if (imageInputMode == ImageInputMode.LATEST_PHOTO) "点击「清屏」后，请手动截图，之后点击「识别」"
                else if (captureActive) "屏幕捕获已就绪，点击悬浮按钮识别游戏；到「结果信息」查看截图与识别详情。"
                else "点击启动后请同意系统屏幕捕获授权；授权成功才会显示悬浮窗。",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            status.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // Weights depend only on available screen width, never on the selected label.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onToggleOverlay,
                    modifier = Modifier.weight(1f).testTag("toggle_assistant"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    enabled = !changingInput && !manualAnalyzing &&
                        ((imageInputMode == ImageInputMode.SELECTED_PHOTO) || (overlayAllowed && imageInputMode != null) || overlayRunning || status.starting),
                    colors = if (overlayRunning) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(
                        when {
                            changingInput -> "正在切换来源…"
                            manualAnalyzing -> "正在识别…"
                            imageInputMode == ImageInputMode.SELECTED_PHOTO -> "选择图片"
                            status.starting -> "取消启动"
                            overlayRunning -> "停止悬浮助手"
                            !overlayAllowed -> "请先授权悬浮窗"
                            else -> "启动悬浮助手"
                        },
                    )
                }
                ImageSourceDropdown(imageInputMode, !changingInput, Modifier.weight(1.4f), onImageInputMode)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (granted) Color(0xFF17A673) else Color(0xFFF29F3D),
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAction, enabled = !granted) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CvPipelineCard() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("当前 CV 流程", fontWeight = FontWeight.Bold)
            Text("隐藏悬浮层 → 采集画面 → 定位棋盘 → 识别物品卡与数量")
            HorizontalDivider()
            Text(
                "格子状态分类 → 碎片与对角线补全 → 预计算开局 / 有限前瞻 / 贪婪探索",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
