package com.bagridmaster.app.analysis

import android.content.Context
import com.bagridmaster.app.capture.CaptureFrameBroker
import com.bagridmaster.app.capture.CaptureFrameException
import com.bagridmaster.app.capture.CaptureRuntimeState
import com.bagridmaster.app.vision.GameVisionDetector
import com.bagridmaster.app.vision.BoardCellState
import com.bagridmaster.app.vision.ItemTemplateStore
import com.bagridmaster.app.vision.MlKitInventoryCountRecognizer
import com.bagridmaster.app.vision.FragmentCompletionPlanner
import com.bagridmaster.app.vision.LocalTemplateMatcher
import com.bagridmaster.app.vision.RgbaFrame
import com.bagridmaster.app.vision.recoverSingleMissingCountFromThirtyCells
import com.bagridmaster.app.debug.RecognitionDebugSnapshot
import com.bagridmaster.app.debug.RecognitionDebugStore
import com.bagridmaster.app.debug.AnalysisDiagnosticLog
import com.bagridmaster.app.media.LatestGalleryFrameSource
import com.bagridmaster.app.media.SelectedGalleryFrameSource
import com.bagridmaster.app.media.validateGalleryAspect
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.data.BoardCalibrationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class CvAnalysisEngine(context: Context) : AnalysisEngine, AutoCloseable {
    private val detector = GameVisionDetector()
    private val countRecognizer = MlKitInventoryCountRecognizer()
    private val strategySolver = BoardStrategySolver()
    private val completionPlanner = FragmentCompletionPlanner()
    private val explorationGate = LitObjectExplorationGate()
    private val galleryFrameSource = LatestGalleryFrameSource(context)
    private val selectedFrameSource = SelectedGalleryFrameSource(context)
    private val calibration = BoardCalibrationRepository(context)

    override suspend fun analyze(request: AnalysisRequest): AnalysisResult {
        val startedAt = System.nanoTime()
        var capturedAt = System.currentTimeMillis()
        var capturedFrame: RgbaFrame? = null
        var inputDescription = request.imageInputMode.label
        RecognitionDebugStore.publish(RecognitionDebugSnapshot(capturedAt, null, null, "正在采集和识别…", request.strategyAlgorithm.label, inputDescription = inputDescription))
        try {
            delay(request.captureDelayMs.toLong().coerceAtLeast(80L))
            val frame = when (request.imageInputMode) {
                ImageInputMode.LATEST_PHOTO -> galleryFrameSource.read(checkNotNull(request.galleryCaptureBoundary) {
                    "请先点击清屏，再截取新的游戏画面"
                }).let { image -> inputDescription = image.description; image.frame }
                ImageInputMode.SELECTED_PHOTO -> selectedFrameSource.read(checkNotNull(request.selectedImageUri) {
                    "请先选择一张图片"
                }).let { image -> inputDescription = image.description; image.frame }
                ImageInputMode.SCREEN_CAPTURE -> {
                    check(CaptureRuntimeState.isActive.value) { "屏幕捕获未就绪，请回首页重新启动悬浮助手并授权，或开启图片识别" }
                    CaptureFrameBroker.capture()
                }
            }
            capturedFrame = frame
            capturedAt = System.currentTimeMillis()
            if (request.imageInputMode == ImageInputMode.LATEST_PHOTO) {
                validateGalleryAspect(frame.width, frame.height, request.screenWidthPx, request.screenHeightPx)
            }
            val result = analyzeFrame(frame, request, startedAt, elapsedMillis(startedAt)).let { result ->
                result.copy(diagnosticNotes = listOf(inputDescription) + result.diagnosticNotes)
            }
            AnalysisDiagnosticLog.record(capturedAt, result)
            RecognitionDebugStore.publish(RecognitionDebugSnapshot(capturedAt, frame, result, "识别完成", request.strategyAlgorithm.label, result.elapsedMs, inputDescription))
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ItemTemplateStore.update(emptyList())
            // Keep a rejected transport frame available for debug/save, but never analyze it or
            // teach the board calibration from it.
            capturedFrame = capturedFrame ?: (error as? CaptureFrameException)?.rejectedFrame
            RecognitionDebugStore.publish(RecognitionDebugSnapshot(
                capturedAt, capturedFrame, null, error.message ?: "识别失败", request.strategyAlgorithm.label,
                (System.nanoTime() - startedAt) / 1_000_000,
                inputDescription,
            ))
            throw error
        }
    }

    private suspend fun analyzeFrame(frame: RgbaFrame, request: AnalysisRequest, startedAt: Long, inputMs: Long): AnalysisResult {
        val boardStarted = System.nanoTime()
        val calibrated = calibration.analyze(
            frame = frame,
            source = request.imageInputMode,
            detector = detector,
            enabled = request.experienceCalibrationEnabled,
            detectBlackBorders = request.blackBorderDetectionEnabled,
        )
        val boardMs = elapsedMillis(boardStarted)
        val detected = calibrated.detection
            ?: error("未识别到内容，请切回翻面游戏的稳定画面后重试；${calibrated.note}")
        val notes = mutableListOf(calibrated.note)
        val ocrStarted = System.nanoTime()
        val digitValidated = validateBoardDigits(frame, detected, notes)
        val remainingEvidence = try {
            countRecognizer.recognizeBoardRemainingCount(frame, digitValidated.geometry.board)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notes += "顶部剩余格数 OCR 失败：${error.message ?: error.javaClass.simpleName}"
            null
        }
        val detection = detector.applyRemainingCountPrior(digitValidated, remainingEvidence)
        if (remainingEvidence != null) {
            val before = digitValidated.boardCells.count { it.state.isUnopened }
            val after = detection.boardCells.count { it.state.isUnopened }
            notes += "顶部剩余格数 ${remainingEvidence.remaining}/${remainingEvidence.total}，" +
                "置信度${(remainingEvidence.confidence * 100).roundToInt()}%；视觉未翻开 $before" +
                if (after == before) "，无需修正" else "，软约束调整为 $after"
        }
        val ocrInventory = try {
            countRecognizer.recognize(frame, detection.itemCards)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notes += "数量 OCR 失败：${error.message ?: error.javaClass.simpleName}"
            detection.itemCards
        }
        val ocrMs = elapsedMillis(ocrStarted)
        val stableInventory = ItemTemplateStore.recoverFinishedArtwork(ocrInventory)
        val previewStarted = System.nanoTime()
        val provisionalInventory = withContext(Dispatchers.Default) {
            val matcher = LocalTemplateMatcher()
            stableInventory.map { it.copy(templatePreview = matcher.preparePreview(it)) }
        }
        var timings = AnalysisTimings(inputMs = inputMs, boardMs = boardMs, inventoryOcrMs = ocrMs,
            templatePreparationMs = elapsedMillis(previewStarted))
        val completion = withContext(Dispatchers.Default) {
            completionPlanner.analyze(frame, detection, provisionalInventory)
        }
        notes += completion.diagnosticNotes
        timings = timings.copy(objectRecognitionMs = completion.timings.objectRecognitionMs,
            placementMatchingMs = completion.timings.placementMatchingMs,
            completionMs = completion.timings.completionMs)
        val boardCells = completion.boardCells.ifEmpty { detection.boardCells }
        val recovered = recoverSingleMissingCountFromThirtyCells(
            cards = provisionalInventory,
            completedCellCount = boardCells.count { it.state == BoardCellState.OPEN_OBJECT },
        )
        recovered.note?.let { notes += it }
        val inventory = recovered.inventory
        if (inventory.any { !it.isFinished && (it.shape == null || it.remainingCount == null) }) {
            notes += "形状或数量不完整，暂缓额外探索，仅保留碎片补全建议"
        }
        ItemTemplateStore.update(inventory)
        val selectedCount = boardCells.count { it.state == BoardCellState.SELECTED }
        if (selectedCount > 0) notes += "$selectedCount 格已勾选，按未翻开参与推荐；已选建议无需重复点击"
        val (recommendations, objects, hasExploration) = withContext(Dispatchers.Default) {
            val explorationStarted = System.nanoTime()
            val fragments = completion.recommendations
            val prepared = explorationGate.prepare(detection.geometry.board, boardCells, inventory, completion)
            notes += prepared.explanation
            val exploration = if (prepared.allowed) strategySolver.recommend(
                cells = prepared.cells,
                cards = prepared.inventory,
                algorithm = request.strategyAlgorithm,
                excluded = fragments.mapTo(mutableSetOf()) { GridCell(it.row, it.column) },
                knownLitFootprints = prepared.knownLitFootprints,
            ) else null
            timings = timings.copy(explorationMs = elapsedMillis(explorationStarted))
            if (prepared.allowed && exploration == null) notes += "当前约束下没有足够的有效布局样本或可探索格，暂不强行探索"
            val displayObjects = completion.objects.map { item ->
                val footprint = completion.fullCompletions.firstOrNull { it.objectId == item.id }
                    ?.footprints?.distinct()?.singleOrNull().orEmpty()
                item.copy(estimatedFootprint = footprint)
            }
            Triple(fragments + listOfNotNull(exploration), displayObjects, exploration != null)
        }
        return AnalysisResult(
            recommendations = recommendations,
            elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            timings = timings,
            source = when (request.imageInputMode) {
                ImageInputMode.SCREEN_CAPTURE -> ResultSource.LIVE
                ImageInputMode.LATEST_PHOTO -> ResultSource.GALLERY
                ImageInputMode.SELECTED_PHOTO -> ResultSource.SELECTED_PHOTO
            },
            frameWidthPx = frame.width,
            frameHeightPx = frame.height,
            geometry = detection.geometry,
            inventory = inventory,
            boardCells = boardCells,
            boardObjects = objects,
            boardContentScore = detection.boardContentScore,
            diagnosticNotes = notes,
            statusText = when {
                boardCells.any { it.state == BoardCellState.UNCERTAIN } -> "存在待确认格 · 请重新识别"
                recommendations.isEmpty() -> "暂无可靠建议"
                !hasExploration && objects.any { it.phase == BoardObjectPhase.LIT } -> "仅补开点亮物品 · 无额外探索"
                recommendations.any { it.strategy.contains("预计算") } -> "${request.strategyAlgorithm.label} · 开局预设"
                else -> "${request.strategyAlgorithm.label} · 现场计算"
            },
        )
    }

    override fun close() {
        countRecognizer.close()
    }

    private suspend fun validateBoardDigits(
        frame: RgbaFrame,
        detected: com.bagridmaster.app.vision.GameVisionDetection,
        notes: MutableList<String>,
    ): com.bagridmaster.app.vision.GameVisionDetection {
        val board = detected.geometry.board
        if (board.confidence >= BOARD_DIGIT_VALIDATION_CONFIDENCE || detected.itemCards.size != 3) return detected
        val evidence = try {
            countRecognizer.recognizeSpatialDigits(frame, board.region, detected.itemCards)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notes += "低置信度棋盘数字复核失败：${error.message ?: error.javaClass.simpleName}"
            return detected
        }
        if (evidence.boardTokens.isEmpty()) return detected
        if (evidence.spriteTokens.isNotEmpty()) {
            notes += "棋盘内检出数字「${evidence.boardDebugLabel}」，但物品贴图本身也含数字" +
                "「${evidence.spriteDebugLabel}」；不将数字作为网格负证据"
            return detected
        }
        val cellHeight = board.subpixelGrid?.cellHeightPx ?: board.region.height.toDouble() / board.rows
        val shiftedRegion = board.region.copy(
            top = (board.region.top + cellHeight).roundToInt(),
            bottom = (board.region.bottom + cellHeight).roundToInt(),
        )
        val shiftedDigits = evidence.tokensInside(shiftedRegion)
        val shifted = if (shiftedRegion.bottom <= frame.height && shiftedDigits.isEmpty()) {
            detector.reinspect(frame, detected, shiftedRegion)
        } else null
        if (shifted != null && shifted.boardContentScore >= detected.boardContentScore - 0.05) {
            notes += "物品贴图内无数字，但原棋盘区域检出数字「${evidence.boardDebugLabel}」；" +
                "已拒绝包含标题栏的纵向相位，并改用向下一格的无数字网格"
            return shifted
        }
        notes += "物品贴图内无数字，但棋盘区域检出数字「${evidence.boardDebugLabel}」；" +
            "未找到通过场景复检的无数字相位，定位置信度大幅降级"
        return detected.copy(
            geometry = detected.geometry.copy(board = board.copy(
                confidence = board.confidence * BOARD_DIGIT_CONFIDENCE_FACTOR,
            )),
            boardContentScore = detected.boardContentScore * BOARD_DIGIT_CONFIDENCE_FACTOR,
        )
    }

    companion object {
        private const val BOARD_DIGIT_VALIDATION_CONFIDENCE = 0.95
        private const val BOARD_DIGIT_CONFIDENCE_FACTOR = 0.30
    }

}
