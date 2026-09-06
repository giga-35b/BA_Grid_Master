package com.bagridmaster.app.analysis

import com.bagridmaster.app.model.StrategyAlgorithm
import com.bagridmaster.app.model.ImageInputMode
import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.media.GalleryCaptureBoundary
import kotlin.math.roundToInt

data class AnalysisRequest(
    val captureDelayMs: Int,
    val includeAlternativeCandidates: Boolean,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val strategyAlgorithm: StrategyAlgorithm = StrategyAlgorithm.LIMITED_LOOKAHEAD,
    val imageInputMode: ImageInputMode = ImageInputMode.SCREEN_CAPTURE,
    val galleryCaptureBoundary: GalleryCaptureBoundary? = null,
    /** Content URI returned by the system document picker; only used by SELECTED_PHOTO. */
    val selectedImageUri: String? = null,
    val experienceCalibrationEnabled: Boolean = true,
    val blackBorderDetectionEnabled: Boolean = true,
)

data class AnalysisResult(
    val recommendations: List<CellRecommendation>,
    val elapsedMs: Long,
    val source: ResultSource,
    val geometry: RecognitionGeometry,
    val inventory: List<ItemCardRecognition> = emptyList(),
    val statusText: String = "",
    val boardCells: List<BoardCellObservation> = emptyList(),
    val boardObjects: List<BoardObjectObservation> = emptyList(),
    val boardContentScore: Double? = null,
    val diagnosticNotes: List<String> = emptyList(),
    val frameWidthPx: Int = 0,
    val frameHeightPx: Int = 0,
    val timings: AnalysisTimings? = null,
)

data class CellRecommendation(
    val row: Int,
    val column: Int,
    val missProbability: Double,
    val strategy: String,
    val isExploration: Boolean = false,
)

data class ScreenPoint(
    val x: Int,
    val y: Int,
)

data class ScreenRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val origin: ScreenPoint get() = ScreenPoint(left, top)
}

data class BoardGeometry(
    val region: ScreenRegion,
    val rows: Int,
    val columns: Int,
    val confidence: Double,
    /** Continuous grid coordinates prevent a fractional cell size accumulating at one edge. */
    val subpixelGrid: SubpixelGridGeometry? = null,
) {
    fun cellRegion(row: Int, column: Int): ScreenRegion? {
        if (row !in 0 until rows || column !in 0 until columns) return null
        subpixelGrid?.let { grid ->
            return ScreenRegion(
                left = (grid.originX + column * grid.cellSizePx).roundToInt(),
                top = (grid.originY + row * grid.cellHeightPx).roundToInt(),
                right = (grid.originX + (column + 1) * grid.cellSizePx).roundToInt(),
                bottom = (grid.originY + (row + 1) * grid.cellHeightPx).roundToInt(),
            )
        }
        val cellWidth = region.width.toFloat() / columns
        val cellHeight = region.height.toFloat() / rows
        return ScreenRegion(
            left = (region.left + column * cellWidth).toInt(),
            top = (region.top + row * cellHeight).toInt(),
            right = (region.left + (column + 1) * cellWidth).toInt(),
            bottom = (region.top + (row + 1) * cellHeight).toInt(),
        )
    }
}

data class SubpixelGridGeometry(
    val originX: Double,
    val originY: Double,
    /** Horizontal size; detection coordinates keep this strictly equal to [cellHeightPx]. */
    val cellSizePx: Double,
    /** May differ after mapping an image into a slightly different-aspect overlay viewport. */
    val cellHeightPx: Double = cellSizePx,
)

data class RecognitionGeometry(
    val board: BoardGeometry,
    val itemTray: ScreenRegion?,
    val detectorLabel: String,
)

data class ItemShape(
    val rows: Int,
    val columns: Int,
    val occupiedCells: Set<ShapeCell>,
) {
    val cellCount: Int get() = occupiedCells.size

    val compactLabel: String
        get() = if (occupiedCells.isEmpty()) "?" else "${columns}×${rows}/${cellCount}格"
}

data class ShapeCell(
    val row: Int,
    val column: Int,
)

data class RgbaPatch(
    val width: Int,
    val height: Int,
    val rgba8888: ByteArray,
)

data class ItemCardRecognition(
    val index: Int,
    val cardRegion: ScreenRegion,
    val spriteRegion: ScreenRegion,
    val footprintRegion: ScreenRegion,
    val countRegion: ScreenRegion,
    val shape: ItemShape?,
    val remainingCount: Int?,
    val isFinished: Boolean,
    val confidence: Double,
    val spriteTemplate: RgbaPatch,
    val countText: String = "",
    val finishText: String = "",
    val finishEvidence: String = "",
    val templatePreview: TemplatePreview? = null,
    val countEvidence: String = "",
)

/** The canonical preprocessing reference, not a claim about a hidden board placement. */
data class TemplatePreview(
    val rows: Int,
    val columns: Int,
    val rotationDegrees: Int?,
    val aligned: RgbaPatch?,
    val tiles: List<RgbaPatch>,
    val explanation: String,
)

data class ItemTypeScore(val itemIndex: Int, val appearanceScore: Double, val retained: Boolean)

enum class BoardObjectPhase(val label: String) {
    COMPLETED("已完全打开"),
    LIT("已点亮"),
}

/** Only observed cells are certain; no inferred hidden footprint is drawn as fact. */
data class BoardObjectObservation(
    val id: String,
    val phase: BoardObjectPhase,
    val observedCells: Set<GridCell>,
    val possibleItemIndices: List<Int>,
    val confidence: Double,
    val evidence: String,
    val localMatch: LocalTemplateMatch? = null,
    val completionEvidence: String = "",
    // Presentation hypothesis only; not an observation and never fed back into the solver.
    val estimatedFootprint: Set<GridCell> = emptySet(),
    val typeScores: List<ItemTypeScore> = emptyList(),
) {
    val typeLabel: String get() = when (possibleItemIndices.size) {
        0 -> "种类未知"
        1 -> "物品${itemCode(possibleItemIndices.single())}（推定）"
        else -> "候选物品" + possibleItemIndices.joinToString("/") { itemCode(it) }
    }
}

/** A scored hypothesis, never an observation of the still-covered cells. */
data class TemplatePlacementMatch(
    val itemIndex: Int,
    val origin: GridCell,
    val rows: Int,
    val columns: Int,
    val rotationDegrees: Int,
    val similarity: Double,
    val maskSimilarity: Double,
    val textureSimilarity: Double,
    val preRotationDegrees: Int? = null,
) {
    val cells: Set<GridCell> get() = (0 until rows).flatMap { r ->
        (0 until columns).map { c -> GridCell(origin.row + r, origin.column + c) }
    }.toSet()

    fun localLabel(cell: GridCell): String {
        val r = cell.row - origin.row
        val c = cell.column - origin.column
        if (r !in 0 until rows || c !in 0 until columns) return "范围外"
        if (minOf(rows, columns) == 1) {
            val position = maxOf(r, c) + 1
            val length = maxOf(rows, columns)
            val part = if (position > 1 && position < length) "中段" else "端段"
            return "${if (rows > 1) "竖向" else "横向"}$part 第$position/$length 格（${if (rows > 1) "从上到下" else "从左到右"}）"
        }
        return "物品内第${r + 1}行第${c + 1}列（屏幕方向）"
    }
}

data class LocalTemplateMatch(
    val accepted: Boolean,
    val candidates: List<TemplatePlacementMatch>,
    val margin: Double,
    val evidence: String,
)

enum class ResultSource {
    MOCK,
    LIVE,
    GALLERY,
    SELECTED_PHOTO,
}

interface AnalysisEngine {
    suspend fun analyze(request: AnalysisRequest): AnalysisResult
}

fun itemCode(index: Int): String = if (index in 0..2) ('a' + index).toString() else "?"
