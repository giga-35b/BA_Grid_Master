package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.BoardGeometry
import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.ItemShape
import com.bagridmaster.app.analysis.RecognitionGeometry
import com.bagridmaster.app.analysis.RgbaPatch
import com.bagridmaster.app.analysis.ScreenRegion
import com.bagridmaster.app.analysis.ShapeCell
import com.bagridmaster.app.analysis.GridCell
import com.bagridmaster.app.analysis.SubpixelGridGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class GameVisionDetection(
    val geometry: RecognitionGeometry,
    val itemCards: List<ItemCardRecognition>,
    val boardContentScore: Double,
    val boardCells: List<BoardCellObservation>,
    val fragmentEdges: List<FragmentEdgeEvidence> = emptyList(),
    val strictSquareFitUsed: Boolean = false,
    val strictSquareFitConfidence: Double = 0.0,
    val strictSquareAnchorCount: Int = 0,
)

data class FragmentEdgeEvidence(
    val cell: GridCell,
    val top: Double, val right: Double, val bottom: Double, val left: Double,
    val verticalHint: Double, val horizontalHint: Double,
)

enum class BoardCellState {
    CLOSED,
    SELECTED,
    OPEN_EMPTY,
    OPEN_FRAGMENT,
    UNCERTAIN,
    OPEN_OBJECT;

    val isUnopened: Boolean get() = this == CLOSED || this == SELECTED
}

data class BoardCellObservation(
    val row: Int,
    val column: Int,
    val state: BoardCellState,
    val presence: CellPresenceEvidence? = null,
    val classification: CellClassificationEvidence? = null,
)

/** Visual label strengths retained so a later header-count prior can adjust only ambiguous cells. */
data class CellClassificationEvidence(
    val closedScore: Double,
    val emptyScore: Double,
    val fragmentScore: Double,
)

/** Measured pixel fractions, not probabilities or item-type confidence. */
data class CellPresenceEvidence(
    val chromaticRatio: Double,
    val paleRatio: Double,
    val connectedForegroundRatio: Double,
    val openedBackgroundRatio: Double,
    val unexplainedRatio: Double,
    val explanation: String,
)

/** Lightweight CV tailored to the 9x5 treasure board. It has no Android/OpenCV dependency. */
class GameVisionDetector : FrameGeometryDetector {
    override suspend fun detect(frame: RgbaFrame): RecognitionGeometry? = analyze(frame)?.geometry

    fun analyze(frame: RgbaFrame): GameVisionDetection? {
        if (frame.width < 320 || frame.height < 240) return null
        val gray = GrayFrame(frame)
        val viewport = activeViewport(frame)
        val boardCandidate = locateBoard(gray, viewport) ?: return null
        val boardSelection = selectBoardRegion(frame, gray, boardCandidate, viewport)
        val boardRegion = boardSelection.region
        val board = BoardGeometry(
            region = boardRegion,
            rows = BOARD_ROWS,
            columns = BOARD_COLUMNS,
            confidence = (boardCandidate.confidence * 0.82 + boardSelection.quality * 0.18).coerceAtMost(0.99),
            subpixelGrid = boardSelection.grid,
        )
        return inspectLocatedBoard(frame, gray, board, viewport, boardSelection.squareFit)
    }

    /** Called only after a fresh detection, with bounded historical correction in image pixels. */
    fun reinspect(frame: RgbaFrame, detected: GameVisionDetection, region: ScreenRegion): GameVisionDetection? {
        val viewport = activeViewport(frame)
        val squarePlacement = enforceSquareGrid(region, viewport)
        val squareRegion = squarePlacement.region
        if (squareRegion.left < 0 || squareRegion.top < 0 || squareRegion.right > frame.width ||
            squareRegion.bottom > frame.height || squareRegion.width < BOARD_COLUMNS * 8 ||
            squareRegion.height < BOARD_ROWS * 8) return null
        val fitEvidence = if (detected.strictSquareFitUsed) StrictSquareFit(
            squareRegion, detected.strictSquareFitConfidence, detected.strictSquareAnchorCount,
            squarePlacement.grid,
        ) else null
        return inspectLocatedBoard(frame, GrayFrame(frame),
            detected.geometry.board.copy(region = squareRegion, subpixelGrid = squarePlacement.grid),
            viewport, fitEvidence)
    }

    /** Applies the header count as a bounded soft prior without reading pixels or relearning models. */
    fun applyRemainingCountPrior(
        detected: GameVisionDetection,
        evidence: BoardRemainingCountEvidence?,
    ): GameVisionDetection {
        if (evidence == null || evidence.total != BOARD_ROWS * BOARD_COLUMNS || evidence.confidence < 0.55) {
            return detected
        }
        val current = detected.boardCells.count { it.state.isUnopened }
        val difference = evidence.remaining - current
        if (difference == 0) return detected
        val needClosed = difference > 0
        val maximumVisualShift = 0.08 + evidence.confidence * 0.18
        val candidates = detected.boardCells.mapNotNull { cell ->
            val scores = cell.classification ?: return@mapNotNull null
            val eligible = if (needClosed) {
                cell.state == BoardCellState.OPEN_EMPTY || cell.state == BoardCellState.UNCERTAIN
            } else cell.state == BoardCellState.CLOSED
            if (!eligible) return@mapNotNull null
            val margin = scores.closedScore - scores.emptyScore
            val visuallyCompatible = if (needClosed) margin >= -maximumVisualShift else margin <= maximumVisualShift
            if (!visuallyCompatible) null else cell to margin
        }.sortedBy { (_, margin) -> if (needClosed) -margin else margin }
        val requested = abs(difference)
        val allowed = ceil(requested * evidence.confidence).toInt().coerceAtMost(candidates.size)
        if (allowed <= 0) return detected
        val changed = candidates.take(allowed).mapTo(mutableSetOf()) { (cell, _) -> cell.row to cell.column }
        if (changed.isEmpty()) return detected
        return detected.copy(boardCells = detected.boardCells.map { cell ->
            if (cell.row to cell.column !in changed) cell else cell.copy(
                state = if (needClosed) BoardCellState.CLOSED else BoardCellState.OPEN_EMPTY,
                presence = cell.presence?.copy(explanation = cell.presence.explanation +
                    "；顶部${evidence.remaining}/${evidence.total}作为软约束修正低置信度状态"),
            )
        })
    }

    private fun inspectLocatedBoard(
        frame: RgbaFrame,
        gray: GrayFrame,
        board: BoardGeometry,
        viewport: ScreenRegion,
        squareFit: StrictSquareFit? = null,
    ): GameVisionDetection? {
        val traySelection = locateItemTrayCandidates(gray, board.region, viewport)
            .map { candidate ->
                val candidateCards = recognizeItemCards(frame, candidate.region)
                val labelPairs = candidateCards.map { card -> hasInventoryLabelPair(frame, card.cardRegion) }
                TraySelection(
                    region = candidate.region,
                    cards = candidateCards,
                    latticeScore = candidate.score,
                    validCardCount = candidateCards.indices.count { index ->
                        candidateCards[index].shape != null || candidateCards[index].isFinished || labelPairs[index]
                    },
                    finishCardCount = candidateCards.count { it.isFinished },
                )
            }
            .maxWithOrNull(compareBy<TraySelection>(
                // A completed card is still a real third card. Its central Finish banner and
                // dimmed shape/count badges must beat a shifted lattice containing two live
                // cards plus scenery.
                { it.validCardCount },
                { it.finishCardCount },
                { it.cards.count { card -> card.shape != null } },
                { it.cards.map { card -> card.confidence }.average() },
                { it.latticeScore },
            ))
        val tray = traySelection?.region
        val cards = traySelection?.cards.orEmpty()
        val boardEvidence = inspectBoard(frame, board)
        val recognizedShapes = cards.count { it.shape != null }
        val uncertainCells = boardEvidence.cells.count { it.state == BoardCellState.UNCERTAIN }
        if (boardEvidence.contentScore < MIN_BOARD_CONTENT_SCORE || cards.size != 3 || recognizedShapes < 1 ||
            (recognizedShapes == 1 && uncertainCells >= 3)) {
            return null
        }
        val itemConfidence = if (cards.isEmpty()) 0.0 else cards.map { it.confidence }.average()
        val label = buildString {
            append("等距网格 CV")
            append(" · 棋盘 ")
            append((board.confidence * 100).roundToInt())
            append('%')
            if (squareFit != null) {
                append(" · 正方形锚点 ")
                append(squareFit.anchorCount)
                append(' ')
                append((squareFit.confidence * 100).roundToInt())
                append('%')
            } else append(" · 正方形约束 · 锚点不足，横纵线回退")
            if (cards.isNotEmpty()) {
                append(" · 物品 ")
                append(cards.count { it.shape != null })
                append('/')
                append(cards.size)
                append(" ")
                append((itemConfidence * 100).roundToInt())
                append('%')
            }
        }
        return GameVisionDetection(
            geometry = RecognitionGeometry(
                board = board,
                itemTray = tray,
                detectorLabel = label,
            ),
            itemCards = cards,
            boardContentScore = boardEvidence.contentScore,
            boardCells = boardEvidence.cells,
            fragmentEdges = boardEvidence.fragmentEdges,
            strictSquareFitUsed = squareFit != null,
            strictSquareFitConfidence = squareFit?.confidence ?: 0.0,
            strictSquareAnchorCount = squareFit?.anchorCount ?: 0,
        )
    }

    /**
     * Produces observations, not recommendations. Completion must pass through the spatial
     * matcher and shape constraints in FragmentCompletionPlanner before reaching the overlay.
     */
    private fun inspectBoard(frame: RgbaFrame, board: BoardGeometry): BoardEvidence {
        val regions = Array(BOARD_ROWS) { row ->
            Array(BOARD_COLUMNS) { column -> checkNotNull(board.cellRegion(row, column)) }
        }
        val rawCells = Array(BOARD_ROWS) { row ->
            Array(BOARD_COLUMNS) { column -> inspectCell(frame, regions[row][column]) }
        }
        val flatRaw = rawCells.flatten()
        // Learn repeating smooth cover colours from this board instead of assigning any hue to
        // "background".  A cover palette may contain one colour or many and may be dark or light.
        // Repetition across most of the lattice is the evidence; isolated smooth artwork is not.
        fun colourBin(evidence: CellEvidence) = Triple((evidence.meanR / 32).toInt(),
            (evidence.meanG / 32).toInt(), (evidence.meanB / 32).toInt())
        val smooth = flatRaw.filter { it.tileSmoothness >= 0.72 }
        val recurringBins = smooth.groupingBy(::colourBin).eachCount().filterValues { it >= 3 }.keys
        val recurringSmooth = smooth.filter { colourBin(it) in recurringBins }
        val adaptiveCoveredTheme = flatRaw.count { it.state == BoardCellState.CLOSED } <= 9 &&
            recurringSmooth.size >= 30 && recurringBins.size <= 12
        // Seed selection is deliberately high precision. The shared pixels outside these obvious
        // fragments teach the opened-cell background once; no second learning pass is performed.
        val seedCoordinates = buildSet {
            if (!adaptiveCoveredTheme) return@buildSet
            for (row in 0 until BOARD_ROWS) for (column in 0 until BOARD_COLUMNS) {
                val evidence = rawCells[row][column]
                val presence = evidence.presence
                val strongColour = presence.chromaticRatio >= 0.075
                val strongPaleShape = evidence.foregroundCoverage >= 0.10 && presence.unexplainedRatio < 0.25
                val reachesBoundary = maxOf(evidence.topEdge, evidence.rightEdge,
                    evidence.bottomEdge, evidence.leftEdge) > 0.0
                // Solid yellow/purple cover tiles can satisfy the legacy absolute colour test.
                // They are not seeds unless real structure reaches a boundary (or the whole cell
                // is conspicuously non-smooth), which keeps the seed set precision-first.
                val structural = reachesBoundary || evidence.tileSmoothness < 0.55
                if (evidence.state == BoardCellState.OPEN_FRAGMENT && structural &&
                    (strongColour || strongPaleShape)) {
                    add(row to column)
                }
            }
        }
        val openedBackground = OpenCellBackgroundModel.learn(frame,
            seedCoordinates.map { (row, column) -> regions[row][column] })
        val backgroundMeasurements = Array(BOARD_ROWS) { row -> Array(BOARD_COLUMNS) { column ->
            openedBackground?.measure(frame, regions[row][column])
        } }
        val patternCells = (0 until BOARD_ROWS).flatMap { row -> (0 until BOARD_COLUMNS).map { column ->
            val evidence = rawCells[row][column]
            CoveredPatternCell(row, column, evidence.tileSmoothness,
                evidence.meanR, evidence.meanG, evidence.meanB)
        } }
        val openedBackgroundConfidence = openedBackground?.confidence ?: 0.0
        val likelyOpenedBackground = buildSet {
            if (openedBackgroundConfidence < 0.60) return@buildSet
            for (row in 0 until BOARD_ROWS) for (column in 0 until BOARD_COLUMNS) {
                val measurement = backgroundMeasurements[row][column] ?: continue
                if (measurement.backgroundRatio >= 0.52 && measurement.residualConnectedRatio <= 0.105) {
                    add(row to column)
                }
            }
        }
        val coveredPattern = if (adaptiveCoveredTheme) {
            CoveredPatternModel.learn(patternCells, seedCoordinates + likelyOpenedBackground)
        } else null
        val classification = Array(BOARD_ROWS) { arrayOfNulls<CellClassificationEvidence>(BOARD_COLUMNS) }
        val cells = Array(BOARD_ROWS) { row ->
            Array(BOARD_COLUMNS) { column ->
                val evidence = rawCells[row][column]
                val repeatedCover = evidence.tileSmoothness >= 0.72 && colourBin(evidence) in recurringBins
                val measurement = backgroundMeasurements[row][column]
                val patternScore = coveredPattern?.score(patternCells[row * BOARD_COLUMNS + column]) ?: 0.0
                val legacyCoverScore = if (repeatedCover) (0.48 + evidence.tileSmoothness * 0.42) else 0.0
                val closedScore = maxOf(patternScore, legacyCoverScore)
                val emptyScore = if (measurement == null) 0.0 else openedBackgroundConfidence *
                    measurement.backgroundRatio * (1.0 - measurement.residualConnectedRatio).coerceAtLeast(0.0)
                val residualStructure = if (measurement == null) 0.0 else openedBackgroundConfidence *
                    measurement.residualConnectedRatio *
                    (0.55 + minOf(0.45, measurement.residualChangeRatio * 4.0))
                val edgeStructure = maxOf(evidence.topEdge, evidence.rightEdge,
                    evidence.bottomEdge, evidence.leftEdge)
                val fragmentScore = when {
                    row to column in seedCoordinates -> 1.0
                    measurement != null && measurement.backgroundRatio >= 0.20 ->
                        maxOf(residualStructure, if (edgeStructure > 0.0) residualStructure + 0.10 else 0.0)
                    else -> evidence.foregroundCoverage * 0.65 + edgeStructure * 0.20
                }.coerceIn(0.0, 1.0)
                classification[row][column] = if (adaptiveCoveredTheme) {
                    CellClassificationEvidence(closedScore, emptyScore, fragmentScore)
                } else CellClassificationEvidence(
                    closedScore = if (evidence.state.isUnopened) 0.95 else 0.05,
                    emptyScore = if (evidence.state == BoardCellState.OPEN_EMPTY) 0.95 else 0.05,
                    fragmentScore = if (evidence.state == BoardCellState.OPEN_FRAGMENT) 0.95 else 0.05,
                )
                val confidentEmpty = measurement != null && openedBackgroundConfidence >= 0.60 &&
                    measurement.backgroundRatio >= 0.52 && measurement.residualConnectedRatio <= 0.105 &&
                    emptyScore >= closedScore - 0.04
                val residualFragment = measurement != null && measurement.backgroundRatio >= 0.20 &&
                    measurement.residualConnectedRatio >= 0.055 &&
                    (measurement.residualChangeRatio >= 0.028 || edgeStructure > 0.0) &&
                    fragmentScore >= emptyScore * 0.42
                val patternCovered = closedScore >= 0.57 && closedScore >= emptyScore + 0.06
                when {
                    !adaptiveCoveredTheme -> evidence
                    evidence.state == BoardCellState.SELECTED -> evidence
                    row to column in seedCoordinates -> evidence.copy(
                        state = BoardCellState.OPEN_FRAGMENT,
                        presence = evidence.presence.copy(explanation = "高置信度物品纹理；非物品区域用于一次性标定已翻开背景"),
                    )
                    patternCovered -> evidence.copy(
                        state = BoardCellState.CLOSED,
                        presence = evidence.presence.copy(explanation = "符合本棋盘的纯色/斜向周期覆盖相位"),
                    )
                    confidentEmpty -> evidence.copy(
                        state = BoardCellState.OPEN_EMPTY,
                        presence = evidence.presence.copy(explanation = "符合物品格共同背景，扣除背景后无可靠物品结构"),
                    )
                    residualFragment -> evidence.copy(
                        state = BoardCellState.OPEN_FRAGMENT,
                        presence = evidence.presence.copy(explanation = "扣除物品格共同背景后仍有连贯纹理/边缘结构"),
                    )
                    repeatedCover && adaptiveCoveredTheme -> evidence.copy(
                        state = BoardCellState.CLOSED,
                        presence = evidence.presence.copy(explanation = "本棋盘重复出现的平滑整格覆盖背景，色相不参与判断"),
                    )
                    openedBackground != null && evidence.state == BoardCellState.OPEN_FRAGMENT -> evidence.copy(
                        state = BoardCellState.UNCERTAIN,
                        presence = evidence.presence.copy(explanation = "绝对浅色前景与已翻开背景冲突，不强行认作物品"),
                    )
                    else -> evidence
                }
            }
        }
        val hueSpecificContent = cells.flatten().count { it.boardLikeRatio >= 0.34 }.toDouble() / (BOARD_ROWS * BOARD_COLUMNS)
        val adaptiveContent = if (adaptiveCoveredTheme) recurringSmooth.size.toDouble() / (BOARD_ROWS * BOARD_COLUMNS) else 0.0
        val contentScore = max(hueSpecificContent, adaptiveContent)
        return BoardEvidence(
            contentScore = contentScore,
            fragmentEdges = cells.flatMapIndexed { row, rowCells -> rowCells.mapIndexedNotNull { column, cell ->
                if (cell.state != BoardCellState.OPEN_FRAGMENT) null else FragmentEdgeEvidence(GridCell(row, column),
                    cell.topEdge, cell.rightEdge, cell.bottomEdge, cell.leftEdge, cell.verticalAxisHint, cell.horizontalAxisHint)
            } },
            cells = cells.flatMapIndexed { row, rowCells ->
                rowCells.mapIndexed { column, evidence ->
                    BoardCellObservation(row, column, evidence.state, evidence.presence,
                        checkNotNull(classification[row][column]))
                }
            },
        )
    }

    internal fun inspectCellObservation(frame: RgbaFrame, region: ScreenRegion): BoardCellObservation =
        inspectCell(frame, region).let { BoardCellObservation(0, 0, it.state, it.presence) }

    private fun inspectCell(frame: RgbaFrame, region: ScreenRegion): CellEvidence {
        val horizontalInset = max(3, (region.width * 0.10f).roundToInt())
        val verticalInset = max(3, (region.height * 0.10f).roundToInt())
        val left = (region.left + horizontalInset).coerceIn(0, frame.width - 1)
        val right = (region.right - horizontalInset).coerceIn(left + 1, frame.width)
        val top = (region.top + verticalInset).coerceIn(0, frame.height - 1)
        val bottom = (region.bottom - verticalInset).coerceIn(top + 1, frame.height)
        var samples = 0
        var boardLike = 0
        var coveredBackground = 0
        var openedBackground = 0
        var darkObject = 0
        var fragment = 0
        var chromatic = 0
        var pale = 0
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var smoothComparisons = 0
        var smoothNeighbours = 0
        val sampleWidth = (right - left + 1) / 2
        val sampleHeight = (bottom - top + 1) / 2
        val foregroundMask = BooleanArray(sampleWidth * sampleHeight)
        val unexplainedMask = BooleanArray(foregroundMask.size)
        var fragmentX = 0.0
        var fragmentY = 0.0
        var fragmentX2 = 0.0
        var fragmentY2 = 0.0
        for (y in top until bottom step 2) for (x in left until right step 2) {
            val offset = y * frame.rowStrideBytes + x * 4
            val r = frame.rgba8888[offset].toInt() and 0xFF
            val g = frame.rgba8888[offset + 1].toInt() and 0xFF
            val b = frame.rgba8888[offset + 2].toInt() and 0xFF
            samples++
            redSum += r; greenSum += g; blueSum += b
            if (x + 2 < right) {
                val next = offset + 8
                val difference = max(abs(r - (frame.rgba8888[next].toInt() and 0xFF)),
                    max(abs(g - (frame.rgba8888[next + 1].toInt() and 0xFF)), abs(b - (frame.rgba8888[next + 2].toInt() and 0xFF))))
                smoothComparisons++
                if (difference <= 26) smoothNeighbours++
            }
            if (y + 2 < bottom) {
                val next = offset + frame.rowStrideBytes * 2
                val difference = max(abs(r - (frame.rgba8888[next].toInt() and 0xFF)),
                    max(abs(g - (frame.rgba8888[next + 1].toInt() and 0xFF)), abs(b - (frame.rgba8888[next + 2].toInt() and 0xFF))))
                smoothComparisons++
                if (difference <= 26) smoothNeighbours++
            }
            if (b - r >= 10 && b >= g - 45 && b >= 72 && g >= 48) boardLike++
            if (r < 105 && b - r >= 30 && b >= 105 && g >= 60) coveredBackground++
            if (r >= 90 && b - r >= 10 && g - r >= 6 && g >= 120 && b >= 140) openedBackground++
            // Completed sprites are tinted blue-grey; their lighter parts exceed RGB 105.
            if (max(r, max(g, b)) < 165 && max(r, max(g, b)) - min(r, min(g, b)) <= 60) darkObject++
            val maximum = max(r, max(g, b))
            val minimum = min(r, min(g, b))
            val isChromatic = FragmentPixels.chromatic(r, g, b)
            val isPale = FragmentPixels.pale(r, g, b)
            if (isChromatic) chromatic++
            if (isPale) pale++
            val isFragment = isChromatic || isPale
            foregroundMask[samples - 1] = isFragment
            val blueBackground = b - r >= 8 && b >= g - 10 && g >= 60
            val silhouette = maximum < 165 && maximum - minimum <= 60
            unexplainedMask[samples - 1] = !isFragment && !blueBackground && !silhouette
            if (!isFragment) continue
            fragment++
            val localX = (x - left).toDouble()
            val localY = (y - top).toDouble()
            fragmentX += localX
            fragmentY += localY
            fragmentX2 += localX * localX
            fragmentY2 += localY * localY
        }
        val safeSamples = samples.coerceAtLeast(1).toDouble()
        val fragmentSamples = fragment.coerceAtLeast(1).toDouble()
        val selectionPixels = IntArray(4)
        val selectionSamples = IntArray(4)
        var checkmarkPixels = 0
        var checkmarkSamples = 0
        val safeRegion = region.clamp(frame.width, frame.height)
        val selectionBandX = max(3, (safeRegion.width * 0.16f).roundToInt())
        val selectionBandY = max(3, (safeRegion.height * 0.16f).roundToInt())
        for (y in safeRegion.top until safeRegion.bottom step 3) for (x in safeRegion.left until safeRegion.right step 3) {
            val offset = y * frame.rowStrideBytes + x * 4
            val r = frame.rgba8888[offset].toInt() and 0xFF
            val g = frame.rgba8888[offset + 1].toInt() and 0xFF
            val b = frame.rgba8888[offset + 2].toInt() and 0xFF
            val sides = booleanArrayOf(
                y - safeRegion.top < selectionBandY,
                safeRegion.right - 1 - x < selectionBandX,
                safeRegion.bottom - 1 - y < selectionBandY,
                x - safeRegion.left < selectionBandX,
            )
            val selectionGreen = r >= 100 && g >= 205 && g - r >= 35 && g - b >= 65
            if (x - safeRegion.left in safeRegion.width / 4..safeRegion.width * 3 / 4 &&
                y - safeRegion.top in safeRegion.height / 4..safeRegion.height * 3 / 4) {
                checkmarkSamples++
                if (selectionGreen) checkmarkPixels++
            }
            for (side in sides.indices) if (sides[side]) {
                selectionSamples[side]++
                if (selectionGreen) selectionPixels[side]++
            }
        }
        val selected = selectionPixels.indices.count { side ->
            selectionPixels[side].toDouble() / selectionSamples[side].coerceAtLeast(1) >= 0.018
        } >= 2 && checkmarkPixels.toDouble() / checkmarkSamples.coerceAtLeast(1) >= 0.06
        val covered = coveredBackground / safeSamples >= 0.26
        val opened = openedBackground / safeSamples >= 0.10 || !covered
        val fragmentRatio = fragment / safeSamples
        val darkRatio = darkObject / safeSamples
        val connected = FragmentPixels.largestComponent(foregroundMask, sampleWidth)
        val connectedRatio = connected / safeSamples
        val unexplainedRatio = FragmentPixels.largestComponent(unexplainedMask, sampleWidth) / safeSamples
        val state = when {
            selected -> BoardCellState.SELECTED
            opened && (chromatic / safeSamples >= 0.018 ||
                (fragmentRatio >= 0.025 && connected >= 6 && connectedRatio >= 0.018)) -> BoardCellState.OPEN_FRAGMENT
            darkRatio >= 0.10 -> BoardCellState.OPEN_OBJECT
            covered -> BoardCellState.CLOSED
            connected >= 3 || unexplainedRatio >= 0.08 || openedBackground / safeSamples < 0.65 -> BoardCellState.UNCERTAIN
            else -> BoardCellState.OPEN_EMPTY
        }
        val varianceX = (fragmentX2 / fragmentSamples) - (fragmentX / fragmentSamples).let { it * it }
        val varianceY = (fragmentY2 / fragmentSamples) - (fragmentY / fragmentSamples).let { it * it }
        val verticalAxisHint = if (varianceY > varianceX * 1.18) 0.24 else 0.0
        val horizontalAxisHint = if (varianceX > varianceY * 1.18) 0.24 else 0.0
        val contacts = AdaptiveBoundaryContacts.measure(frame, region)
        return CellEvidence(
            boardLikeRatio = boardLike / safeSamples,
            state = state,
            topEdge = contacts[0],
            rightEdge = contacts[1],
            bottomEdge = contacts[2],
            leftEdge = contacts[3],
            verticalAxisHint = verticalAxisHint,
            horizontalAxisHint = horizontalAxisHint,
            tileSmoothness = smoothNeighbours.toDouble() / smoothComparisons.coerceAtLeast(1),
            foregroundCoverage = connectedRatio,
            meanR = redSum / safeSamples,
            meanG = greenSum / safeSamples,
            meanB = blueSum / safeSamples,
            presence = CellPresenceEvidence(chromatic / safeSamples, pale / safeSamples, connectedRatio,
                openedBackground / safeSamples, unexplainedRatio, when (state) {
                    BoardCellState.SELECTED -> "勾选边框与勾号，按未开处理"
                    BoardCellState.CLOSED -> "整格覆盖背景，未翻开"
                    BoardCellState.OPEN_FRAGMENT -> "检测到连贯物品前景（含低饱和度浅色），独立于种类匹配"
                    BoardCellState.OPEN_OBJECT -> "完成物品的暗色剪影"
                    BoardCellState.UNCERTAIN -> "背景或物品证据不足，不当作空格；请在稳定画面重新识别"
                    BoardCellState.OPEN_EMPTY -> "开放背景充分，未检测到连贯物品前景"
                }),
        )
    }

    private fun locateBoard(gray: GrayFrame, viewport: ScreenRegion): LatticeCandidate? {
        val view = viewport.downsample(gray.scale)
        val vertical = projectionX(gray, view.top + (view.height * 0.18f).toInt(), view.top + (view.height * 0.84f).toInt())
        val horizontal = projectionY(gray, view.left + (view.width * 0.39f).toInt(), view.left + (view.width * 0.96f).toInt())
        // Native tablet captures can have the same small board-to-frame ratio as a letterboxed
        // tablet image on a phone. Do not exclude their true lattice before scoring begins.
        // Ten vertical and six horizontal lines are still required, so title bars cannot win
        // merely because the lower bound is wider.
        val spacingMinRatio = 0.050f
        val spacingMin = (view.height * spacingMinRatio).roundToInt().coerceAtLeast(8)
        val spacingMax = (view.height * 0.125f).roundToInt().coerceAtLeast(spacingMin)
        var best: LatticeCandidate? = null

        for (spacing in spacingMin..spacingMax) {
            val maxStartX = view.right - BOARD_COLUMNS * spacing - 2
            val maxStartY = view.bottom - BOARD_ROWS * spacing - 2
            if (maxStartX <= 0 || maxStartY <= 0) continue

            var bestX = 0
            var bestXScore = Double.NEGATIVE_INFINITY
            val xStart = (view.left + view.width * 0.34f).toInt().coerceAtLeast(view.left + 2)
            val xEnd = min((view.left + view.width * 0.64f).toInt(), maxStartX)
            for (left in xStart..xEnd) {
                val score = latticeScore(vertical, left, spacing, BOARD_COLUMNS + 1)
                if (score > bestXScore) {
                    bestXScore = score
                    bestX = left
                }
            }

            var bestY = 0
            var bestYScore = Double.NEGATIVE_INFINITY
            val yStart = (view.top + view.height * 0.14f).toInt().coerceAtLeast(view.top + 2)
            val yEnd = min((view.top + view.height * 0.36f).toInt(), maxStartY)
            for (top in yStart..yEnd) {
                val score = latticeScore(horizontal, top, spacing, BOARD_ROWS + 1)
                if (score > bestYScore) {
                    bestYScore = score
                    bestY = top
                }
            }

            val combined = bestXScore + bestYScore
            if (best == null || combined > best.score) {
                val confidence = ((combined - 12.0) / 42.0).coerceIn(0.25, 0.99)
                best = LatticeCandidate(bestX, bestY, spacing, combined, confidence)
            }
        }
        return best?.takeIf { it.score >= 10.0 }
    }

    private fun refineBoard(
        frame: RgbaFrame,
        gray: GrayFrame,
        candidate: LatticeCandidate,
    ): ScreenRegion {
        val estimatedSpacing = candidate.spacing * gray.scale.toFloat()
        val estimatedLeft = candidate.left * gray.scale
        val estimatedTop = candidate.top * gray.scale
        val verticalLines = (0..BOARD_COLUMNS).map { index ->
            val expected = estimatedLeft + index * estimatedSpacing
            findBestVerticalLine(
                frame,
                expected.roundToInt(),
                estimatedTop,
                (estimatedTop + BOARD_ROWS * estimatedSpacing).roundToInt(),
                gray.scale * 4,
            )
        }
        val horizontalLines = (0..BOARD_ROWS).map { index ->
            val expected = estimatedTop + index * estimatedSpacing
            findBestHorizontalLine(
                frame,
                expected.roundToInt(),
                estimatedLeft,
                (estimatedLeft + BOARD_COLUMNS * estimatedSpacing).roundToInt(),
                gray.scale * 4,
            )
        }
        val xFit = robustLatticeFit(verticalLines)
        val yFit = robustLatticeFit(horizontalLines)
        val frameRegion = ScreenRegion(
            left = xFit.first.roundToInt().coerceIn(0, frame.width - 1),
            top = yFit.first.roundToInt().coerceIn(0, frame.height - 1),
            right = (xFit.first + BOARD_COLUMNS * xFit.second).roundToInt().coerceIn(1, frame.width),
            bottom = (yFit.first + BOARD_ROWS * yFit.second).roundToInt().coerceIn(1, frame.height),
        )
        // The strongest lattice response includes the decorative outer frame. Move to the
        // playable grid line centers using the frame inset measured relative to one cell.
        val cell = min(
            frameRegion.width / BOARD_COLUMNS.toFloat(),
            frameRegion.height / BOARD_ROWS.toFloat(),
        )
        return ScreenRegion(
            left = (frameRegion.left + cell * 0.07f).roundToInt(),
            top = (frameRegion.top + cell * 0.01f).roundToInt(),
            right = (frameRegion.right - cell * 0.14f).roundToInt(),
            bottom = (frameRegion.bottom - cell * 0.02f).roundToInt(),
        )
    }

    /**
     * A panel header can form the same six horizontal edges as a five-row board.  It is the
     * only common one-period alias, so compare just the detected lattice and one row lower.
     * This is deliberately bounded: two refinements plus sparse samples from 45 cells.
     */
    private fun selectBoardRegion(
        frame: RgbaFrame,
        gray: GrayFrame,
        candidate: LatticeCandidate,
        viewport: ScreenRegion,
    ): BoardSelection {
        val hasEmbeddedViewport = viewport.left > 0 || viewport.top > 0 ||
            viewport.right < frame.width || viewport.bottom < frame.height
        val viewportAspect = viewport.width.toDouble() / viewport.height
        val candidates = when {
            hasEmbeddedViewport -> listOf(candidate, candidate.copy(top = candidate.top + candidate.spacing))
            viewportAspect < TABLET_ASPECT_LIMIT -> (0..2).map { offset ->
                candidate.copy(left = candidate.left + offset * candidate.spacing)
            }
            // The toolbar directly above the board can form a complete-looking 9×5 lattice one
            // period too early even in a full-screen capture. Always compare the next vertical
            // phase instead of limiting this alias check to embedded/letterboxed viewports.
            else -> listOf(candidate, candidate.copy(top = candidate.top + candidate.spacing))
        }
        val variants = candidates
            .mapNotNull { shifted ->
                val estimatedBottom = (shifted.top + BOARD_ROWS * shifted.spacing) * gray.scale
                if (estimatedBottom > viewport.bottom + shifted.spacing * gray.scale / 3) null
                else refineBoard(frame, gray, shifted).takeIf {
                    it.left >= viewport.left && it.top >= viewport.top &&
                        it.right <= viewport.right && it.bottom <= viewport.bottom
                }
            }
            .distinct()
            .map { region ->
                val contentQuality = boardGridQuality(frame, region)
                val tabletPositionQuality = if (viewportAspect < TABLET_ASPECT_LIMIT) {
                    val relativeLeft = (region.left - viewport.left).toDouble() / viewport.width
                    (1.0 - abs(relativeLeft - EXPECTED_BOARD_LEFT_RATIO) / 0.18).coerceIn(0.0, 1.0)
                } else 0.0
                val quality = if (viewportAspect < TABLET_ASPECT_LIMIT) {
                    contentQuality * 0.72 + tabletPositionQuality * 0.28
                } else contentQuality
                BoardSelection(region, quality)
            }
        val base = variants.firstOrNull() ?: run {
            val fallback = enforceSquareGrid(refineBoard(frame, gray, candidate), viewport)
            return BoardSelection(fallback.region, 0.0, grid = fallback.grid)
        }
        val alternative = variants.drop(1).maxByOrNull { it.quality }
        // Keep the original geometry unless an alternative is materially more board-like.
        // This prevents decorative textures from making the geometry oscillate between frames.
        val selected = if (alternative != null &&
            alternative.quality >= base.quality + BOARD_ALIAS_QUALITY_MARGIN) alternative else base
        val squareFit = fitStrictSquareGrid(frame, selected.region, viewport)
        if (squareFit != null) return selected.copy(
            region = squareFit.region,
            squareFit = squareFit,
            grid = squareFit.grid,
        )
        val fallbackRegion = if (hasEmbeddedViewport && viewportAspect < TABLET_ASPECT_LIMIT) {
            val cell = min(selected.region.width / BOARD_COLUMNS.toFloat(),
                selected.region.height / BOARD_ROWS.toFloat())
            val recovery = (cell * EMBEDDED_TABLET_HORIZONTAL_RECOVERY).roundToInt()
            ScreenRegion(selected.region.left - recovery, selected.region.top,
                selected.region.right + recovery, selected.region.bottom)
        } else selected.region
        val fallback = enforceSquareGrid(fallbackRegion, viewport)
        return selected.copy(region = fallback.region, grid = fallback.grid)
    }

    /** Legacy-line fallback also keeps a continuous square grid, anchored at the board centre. */
    private fun enforceSquareGrid(region: ScreenRegion, viewport: ScreenRegion): SquareGridPlacement {
        val cell = ((region.width.toDouble() / BOARD_COLUMNS + region.height.toDouble() / BOARD_ROWS) / 2.0)
            .coerceAtLeast(8.0)
        var centerX = (region.left + region.right) / 2.0
        var centerY = (region.top + region.bottom) / 2.0
        val halfWidth = BOARD_COLUMNS * cell / 2.0
        val halfHeight = BOARD_ROWS * cell / 2.0
        centerX = centerX.coerceIn(viewport.left + halfWidth, viewport.right - halfWidth)
        centerY = centerY.coerceIn(viewport.top + halfHeight, viewport.bottom - halfHeight)
        val grid = SubpixelGridGeometry(centerX - halfWidth, centerY - halfHeight, cell)
        return SquareGridPlacement(gridRegion(grid), grid)
    }

    /** Uses only internal lines, then extrapolates the glossy outer boundary with one cell size. */
    private fun fitStrictSquareGrid(
        frame: RgbaFrame,
        seed: ScreenRegion,
        viewport: ScreenRegion,
    ): StrictSquareFit? {
        val seedCellX = seed.width.toDouble() / BOARD_COLUMNS
        val seedCellY = seed.height.toDouble() / BOARD_ROWS
        val seedCell = (seedCellX + seedCellY) / 2.0
        if (seedCell < 8.0 || abs(seedCellX - seedCellY) > seedCell * 0.22) return null
        val radius = (seedCell * STRICT_LINE_SEARCH_RADIUS).roundToInt().coerceIn(3, 24)
        val vertical = (1 until BOARD_COLUMNS).map { index ->
            findVerticalLineObservation(frame, (seed.left + index * seedCellX).roundToInt(),
                seed.top, seed.bottom, radius, index)
        }.filter { it.prominence >= STRICT_LINE_PROMINENCE }
        val horizontal = (1 until BOARD_ROWS).map { index ->
            findHorizontalLineObservation(frame, (seed.top + index * seedCellY).roundToInt(),
                seed.left, seed.right, radius, index)
        }.filter { it.prominence >= STRICT_LINE_PROMINENCE }
        if (vertical.size < 4 || horizontal.size < 3 ||
            vertical.maxOf { it.index } - vertical.minOf { it.index } < 4 ||
            horizontal.maxOf { it.index } - horizontal.minOf { it.index } < 2) return null

        val spacings = mutableListOf(seedCell)
        fun collectSpacing(lines: List<IndexedLine>) {
            for (a in lines.indices) for (b in a + 1 until lines.size) {
                val spacing = (lines[b].position - lines[a].position).toDouble() /
                    (lines[b].index - lines[a].index)
                if (spacing in seedCell * 0.84..seedCell * 1.16) spacings += spacing
            }
        }
        collectSpacing(vertical)
        collectSpacing(horizontal)
        var best: StrictSquareFit? = null
        for (spacing in spacings) {
            val x0 = vertical.map { it.position - it.index * spacing }.median()
            val y0 = horizontal.map { it.position - it.index * spacing }.median()
            val tolerance = max(1.5, spacing * STRICT_LINE_RESIDUAL_RATIO)
            val vInliers = vertical.filter { abs(it.position - (x0 + it.index * spacing)) <= tolerance }
            val hInliers = horizontal.filter { abs(it.position - (y0 + it.index * spacing)) <= tolerance }
            if (vInliers.size < 4 || hInliers.size < 3 ||
                vInliers.maxOf { it.index } - vInliers.minOf { it.index } < 4 ||
                hInliers.maxOf { it.index } - hInliers.minOf { it.index } < 2) continue
            // Anchor the model at the board centre. Each internal line votes for the centre,
            // so neither the left nor right half becomes the fixed expansion origin.
            val centerX = weightedCenterVotes(vInliers, BOARD_COLUMNS / 2.0, spacing).median()
            val centerY = weightedCenterVotes(hInliers, BOARD_ROWS / 2.0, spacing).median()
            val refinedX0 = centerX - BOARD_COLUMNS * spacing / 2.0
            val refinedY0 = centerY - BOARD_ROWS * spacing / 2.0
            val residuals = vInliers.map { abs(it.position - (centerX + (it.index - BOARD_COLUMNS / 2.0) * spacing)) } +
                hInliers.map { abs(it.position - (centerY + (it.index - BOARD_ROWS / 2.0) * spacing)) }
            val residualQuality = (1.0 - residuals.average() / tolerance).coerceIn(0.0, 1.0)
            val lineCoverage = ((vInliers.size / 8.0) + (hInliers.size / 4.0)) / 2.0
            val anchors = countCrossAnchors(frame, vInliers, hInliers, refinedX0, refinedY0, spacing)
            val confidence = (lineCoverage * 0.43 + residualQuality * 0.37 +
                (anchors / 12.0).coerceIn(0.0, 1.0) * 0.20).coerceIn(0.0, 0.99)
            if (anchors < STRICT_MIN_ANCHORS || confidence < STRICT_MIN_CONFIDENCE) continue
            val grid = SubpixelGridGeometry(refinedX0, refinedY0, spacing)
            val region = gridRegion(grid)
            if (region.left < viewport.left || region.top < viewport.top ||
                region.right > viewport.right || region.bottom > viewport.bottom) continue
            val fit = StrictSquareFit(region, confidence, anchors, grid)
            if (best == null || fit.confidence > best.confidence) best = fit
        }
        return best
    }

    /** Middle internal lines lead the centre estimate; outer internal lines remain safeguards. */
    private fun weightedCenterVotes(
        lines: List<IndexedLine>,
        midpoint: Double,
        spacing: Double,
    ): List<Double> = lines.flatMap { line ->
        val distance = abs(line.index - midpoint)
        val weight = when {
            distance <= 1.5 -> 3
            distance <= 2.5 -> 2
            else -> 1
        }
        List(weight) { line.position + (midpoint - line.index) * spacing }
    }

    private fun gridRegion(grid: SubpixelGridGeometry) = ScreenRegion(
        grid.originX.roundToInt(),
        grid.originY.roundToInt(),
        (grid.originX + BOARD_COLUMNS * grid.cellSizePx).roundToInt(),
        (grid.originY + BOARD_ROWS * grid.cellSizePx).roundToInt(),
    )

    /** T/X evidence boosts early-board confidence; failure simply returns to the line fallback. */
    private fun countCrossAnchors(
        frame: RgbaFrame,
        vertical: List<IndexedLine>,
        horizontal: List<IndexedLine>,
        x0: Double,
        y0: Double,
        spacing: Double,
    ): Int {
        val arm = (spacing * 0.10).roundToInt().coerceIn(2, 6)
        var count = 0
        for (v in vertical) for (h in horizontal) {
            val x = (x0 + v.index * spacing).roundToInt()
            val y = (y0 + h.index * spacing).roundToInt()
            val center = frame.luma(x, y)
            val lr = (frame.luma(x - arm, y) + frame.luma(x + arm, y)) / 2.0
            val tb = (frame.luma(x, y - arm) + frame.luma(x, y + arm)) / 2.0
            val diagonal = (frame.luma(x - arm, y - arm) + frame.luma(x + arm, y - arm) +
                frame.luma(x - arm, y + arm) + frame.luma(x + arm, y + arm)) / 4.0
            if (abs(center - lr) + abs(diagonal - lr) >= STRICT_ANCHOR_CONTRAST &&
                abs(center - tb) + abs(diagonal - tb) >= STRICT_ANCHOR_CONTRAST) count++
        }
        return count
    }

    /** Theme-independent completeness score. Neutral, very bright UI panels are penalized;
     * saturated yellow/pink/blue board themes are retained. */
    private fun boardGridQuality(frame: RgbaFrame, region: ScreenRegion): Double {
        val rowWhite = DoubleArray(BOARD_ROWS)
        val rowColor = DoubleArray(BOARD_ROWS)
        for (row in 0 until BOARD_ROWS) for (column in 0 until BOARD_COLUMNS) {
            val left = region.left + column * region.width / BOARD_COLUMNS
            val right = region.left + (column + 1) * region.width / BOARD_COLUMNS
            val top = region.top + row * region.height / BOARD_ROWS
            val bottom = region.top + (row + 1) * region.height / BOARD_ROWS
            var samples = 0
            var neutralWhite = 0
            var colored = 0
            val stepX = max(2, (right - left) / 7)
            val stepY = max(2, (bottom - top) / 6)
            for (y in top + stepY until bottom - stepY / 2 step stepY) {
                for (x in left + stepX until right - stepX / 2 step stepX) {
                    val offset = y * frame.rowStrideBytes + x * 4
                    val r = frame.rgba8888[offset].toInt() and 255
                    val g = frame.rgba8888[offset + 1].toInt() and 255
                    val b = frame.rgba8888[offset + 2].toInt() and 255
                    val maximum = max(r, max(g, b))
                    val minimum = min(r, min(g, b))
                    samples++
                    if (minimum >= 205 && maximum - minimum <= 34) neutralWhite++
                    if (maximum - minimum >= 18 || maximum < 205) colored++
                }
            }
            rowWhite[row] += neutralWhite.toDouble() / samples.coerceAtLeast(1)
            rowColor[row] += colored.toDouble() / samples.coerceAtLeast(1)
        }
        for (row in 0 until BOARD_ROWS) {
            rowWhite[row] /= BOARD_COLUMNS
            rowColor[row] /= BOARD_COLUMNS
        }
        val otherWhite = rowWhite.drop(1).average()
        val headerPenalty = (rowWhite[0] - otherWhite).coerceAtLeast(0.0)
        val completeness = rowColor.average()
        return (completeness - rowWhite.average() * 0.45 - headerPenalty * 1.8).coerceIn(0.0, 1.0)
    }

    private fun locateItemTrayCandidates(gray: GrayFrame, board: ScreenRegion, viewport: ScreenRegion): List<TrayCandidate> {
        val view = viewport.downsample(gray.scale)
        val topRangeStart = view.top + (view.height * 0.68f).toInt()
        val topRangeEnd = view.top + (view.height * 0.91f).toInt()
        val bottomRangeStart = view.top + (view.height * 0.84f).toInt()
        val bottomRangeEnd = view.bottom - 2
        if (topRangeStart >= topRangeEnd || bottomRangeStart >= bottomRangeEnd) return emptyList()

        val vertical = projectionX(gray, topRangeStart, bottomRangeEnd)
        val horizontal = projectionY(gray, view.left + (view.width * 0.03f).toInt(), view.left + (view.width * 0.52f).toInt())
        val boardCellDownsampled = min(
            board.width / BOARD_COLUMNS.toFloat(),
            board.height / BOARD_ROWS.toFloat(),
        ) / gray.scale
        val stepMin = (boardCellDownsampled * 1.65f).toInt().coerceAtLeast(12)
        val stepMax = (boardCellDownsampled * 2.30f).toInt().coerceAtLeast(stepMin)
        val candidates = mutableListOf<TrayLattice>()

        for (step in stepMin..stepMax) {
            val leftStart = view.left + (view.width * 0.01f).toInt()
            val leftEnd = minOf(
                (view.left + view.width * 0.40f).toInt(),
                board.left / gray.scale - 3 * step - 2,
                view.right - 3 * step - 2,
            )
            if (leftEnd < leftStart) continue
            for (left in leftStart..leftEnd) {
                val score = latticeScore(vertical, left, step, 4)
                val separation = max(3, step / 7)
                val existing = candidates.indexOfFirst { abs(it.left - left) < separation }
                if (existing >= 0) {
                    if (score > candidates[existing].score) candidates[existing] = TrayLattice(left, step, score)
                } else if (candidates.size < MAX_TRAY_CANDIDATES || score > candidates.minOf { it.score }) {
                    candidates += TrayLattice(left, step, score)
                    candidates.sortByDescending { it.score }
                    while (candidates.size > MAX_TRAY_CANDIDATES) candidates.removeAt(candidates.lastIndex)
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val broadTop = strongestInRange(horizontal, topRangeStart, topRangeEnd)
        val bottom = strongestInRange(horizontal, bottomRangeStart, bottomRangeEnd)
        if (bottom <= broadTop) return emptyList()

        val scale = gray.scale
        val boardCell = board.width / BOARD_COLUMNS.toFloat()
        return candidates.mapNotNull { candidate ->
            val broadAspect = (bottom + 1 - broadTop).toDouble() / candidate.step
            val top = if (broadAspect in 0.72..1.03) broadTop else {
                val narrowStart = max(topRangeStart, bottom - (candidate.step * 1.02f).roundToInt())
                val narrowEnd = min(topRangeEnd, bottom - (candidate.step * 0.72f).roundToInt())
                if (narrowStart < narrowEnd) strongestInRange(horizontal, narrowStart, narrowEnd)
                else (bottom - candidate.step * 0.86f).roundToInt()
            }
            val region = ScreenRegion(
                left = candidate.left * scale,
                top = top * scale,
                right = (candidate.left + 3 * candidate.step) * scale,
                bottom = min(gray.sourceHeight, (bottom + 1) * scale),
            )
            region.takeIf { it.width > boardCell * 4.0f && it.height > boardCell * 1.15f }
                ?.let { TrayCandidate(it, candidate.score) }
        }.distinctBy { it.region }
    }

    private fun activeViewport(frame: RgbaFrame): ScreenRegion {
        val borders = FrameBlackBorderDetector.inspect(frame)
        return if (borders.exceedsCalibrationThreshold) ActiveViewportDetector.detect(frame)
        else ScreenRegion(0, 0, frame.width, frame.height)
    }

    private fun recognizeItemCards(frame: RgbaFrame, tray: ScreenRegion): List<ItemCardRecognition> {
        val cardWidth = tray.width / 3f
        return (0 until 3).map { index ->
            val card = ScreenRegion(
                left = (tray.left + index * cardWidth).roundToInt(),
                top = tray.top,
                right = (tray.left + (index + 1) * cardWidth).roundToInt(),
                bottom = tray.bottom,
            )
            val sprite = relativeRegion(card, 0.07f, 0.04f, 0.93f, 0.72f)
            val footprint = relativeRegion(card, 0.01f, 0.69f, 0.34f, 0.98f)
            val count = relativeRegion(card, 0.62f, 0.70f, 0.99f, 0.99f)
            val template = crop(frame, sprite)
            val finished = FinishLabelDetector.detect(template)
            val shapeResult = recognizeFootprint(frame, footprint, allowDimmedPanel = finished)
            ItemCardRecognition(
                index = index,
                cardRegion = card,
                spriteRegion = sprite,
                footprintRegion = footprint,
                countRegion = count,
                shape = shapeResult?.first,
                remainingCount = if (finished) 0 else null,
                isFinished = finished,
                confidence = shapeResult?.second ?: 0.35,
                spriteTemplate = template,
                finishEvidence = if (finished) "中央 Finish 黄字字形与深色横幅均通过" else "",
            )
        }
    }

    /** The small shape badge and quantity badge remain after Finish, only dimmer.
     * Detect their broad neutral panels without OCR so all tray candidates stay cheap. */
    private fun hasInventoryLabelPair(frame: RgbaFrame, card: ScreenRegion): Boolean {
        val footprint = relativeRegion(card, 0.01f, 0.69f, 0.34f, 0.98f)
        val count = relativeRegion(card, 0.62f, 0.70f, 0.99f, 0.99f)
        return hasDimmedLabelPanel(frame, footprint) && hasDimmedLabelPanel(frame, count)
    }

    private fun hasDimmedLabelPanel(frame: RgbaFrame, region: ScreenRegion): Boolean {
        if (region.width < 8 || region.height < 6) return false
        val step = max(1, min(region.width, region.height) / 28)
        val rows = ((region.height + step - 1) / step).coerceAtLeast(1)
        val columns = ((region.width + step - 1) / step).coerceAtLeast(1)
        val rowHits = IntArray(rows)
        val columnHits = IntArray(columns)
        var neutral = 0
        var samples = 0
        var row = 0
        for (y in region.top until region.bottom step step) {
            var column = 0
            for (x in region.left until region.right step step) {
                val offset = y * frame.rowStrideBytes + x * 4
                val r = frame.rgba8888[offset].toInt() and 255
                val g = frame.rgba8888[offset + 1].toInt() and 255
                val b = frame.rgba8888[offset + 2].toInt() and 255
                val maximum = max(r, max(g, b))
                val minimum = min(r, min(g, b))
                // Includes the normal white badge and its completed-card gray variant, but
                // excludes dark artwork and saturated scenery.
                if (maximum in 58..250 && maximum - minimum <= 54) {
                    neutral++
                    rowHits[row]++
                    columnHits[column]++
                }
                samples++
                column++
            }
            row++
        }
        val neutralRatio = neutral.toDouble() / samples.coerceAtLeast(1)
        val broadRows = rowHits.count { it >= max(2, (columns * 0.24).roundToInt()) }
        val broadColumns = columnHits.count { it >= max(2, (rows * 0.24).roundToInt()) }
        return neutralRatio >= 0.17 && broadRows >= rows * 0.30 && broadColumns >= columns * 0.30
    }

    private fun recognizeFootprint(
        frame: RgbaFrame,
        region: ScreenRegion,
        allowDimmedPanel: Boolean = false,
    ): Pair<ItemShape, Double>? {
        val whiteComponents = components(frame, region) { r, g, b ->
            val maximum = max(r, max(g, b))
            val minimum = min(r, min(g, b))
            if (allowDimmedPanel) maximum in 58..250 && maximum - minimum < 58
            else r > 175 && g > 175 && b > 175 && maximum - minimum < 85
        }
        val panel = whiteComponents
            .filter { it.width in max(8, (region.width * 0.24f).roundToInt())..(region.width * 0.90f).roundToInt() }
            .filter { it.height in max(6, (region.height * 0.24f).roundToInt())..(region.height * 0.90f).roundToInt() }
            .maxByOrNull { it.area }
            ?: return null
        val inner = ScreenRegion(
            left = panel.left + max(2, panel.width / 12),
            top = panel.top + max(2, panel.height / 12),
            right = panel.right - max(2, panel.width / 12),
            bottom = panel.bottom - max(2, panel.height / 12),
        )
        if (inner.width <= 0 || inner.height <= 0) return null

        val smallFootprint = region.height < 30
        val minimumDotSize = if (smallFootprint) 2 else 3
        val minimumDotArea = if (smallFootprint) 3 else 7
        val panelLevel = if (allowDimmedPanel) {
            buildList {
                val step = max(1, min(inner.width, inner.height) / 20)
                for (y in inner.top until inner.bottom step step) for (x in inner.left until inner.right step step) {
                    val offset = y * frame.rowStrideBytes + x * 4
                    add(max(frame.rgba8888[offset].toInt() and 255,
                        max(frame.rgba8888[offset + 1].toInt() and 255, frame.rgba8888[offset + 2].toInt() and 255)))
                }
            }.sorted().let { values -> values.getOrElse((values.size * 3 / 4).coerceAtMost(values.lastIndex)) { 175 } }
        } else 175
        val dimmedDotThreshold = panelLevel - max(12, (panelLevel * 0.12).roundToInt())
        val darkComponents = components(frame, inner) { r, g, b ->
            val maximum = max(r, max(g, b))
            val minimum = min(r, min(g, b))
            (maximum < if (allowDimmedPanel) dimmedDotThreshold else 175) ||
                (maximum - minimum > 65 && b > r)
        }.filter {
            it.width >= minimumDotSize && it.height >= minimumDotSize &&
                it.width <= panel.height / 2 && it.height <= panel.height / 2 &&
                it.area >= minimumDotArea
        }
        if (darkComponents.isEmpty()) return null

        val centers = darkComponents
            .sortedByDescending { it.area }
            .take(12)
            .map { (it.left + it.right) / 2f to (it.top + it.bottom) / 2f }
        val tolerance = if (smallFootprint) max(1.5f, panel.height * 0.10f)
            else max(3f, panel.height * 0.16f)
        val componentColumns = cluster(centers.map { it.first }, tolerance)
        val componentRows = cluster(centers.map { it.second }, tolerance)
        // JPEG/downsampling can bridge two neighbouring badge squares into one tall component.
        // Axis projections still retain a light valley between them, so use the projection only
        // when it recovers at most one additional, regularly spaced band. This preserves the
        // component detector's rejection of arbitrary text/noise while restoring faint 3x3 tags.
        val projectedColumns = footprintProjectionBands(frame, inner, horizontal = true)
        val projectedRows = footprintProjectionBands(frame, inner, horizontal = false)
        fun recovered(component: List<Float>, projected: List<Float>): List<Float> = when {
            projected.size in component.size..component.size + 1 && projected.size <= 8 -> projected
            else -> component
        }
        val columns = recovered(componentColumns, projectedColumns)
        val rows = recovered(componentRows, projectedRows)
        if (columns.isEmpty() || rows.isEmpty() || columns.size > 8 || rows.size > 8) return null
        val observed = centers.mapNotNull { (x, y) ->
            val column = columns.indices.minByOrNull { abs(columns[it] - x) } ?: return@mapNotNull null
            val row = rows.indices.minByOrNull { abs(rows[it] - y) } ?: return@mapNotNull null
            ShapeCell(row, column)
        }.toSet()
        if (observed.isEmpty() || observed.size > 20) return null

        val projectedArea = rows.size * columns.size
        val projectionCoversEveryAxis = observed.map { it.row }.toSet().size == rows.size &&
            observed.map { it.column }.toSet().size == columns.size
        if (projectedArea in 2..20 && projectionCoversEveryAxis && observed.size >= projectedArea * 0.65) {
            val occupied = buildSet {
                for (row in rows.indices) for (column in columns.indices) add(ShapeCell(row, column))
            }
            val support = observed.size.toDouble() / projectedArea
            return ItemShape(rows.size, columns.size, occupied) to (0.62 + support * 0.28)
        }

        // Inventory footprints are always solid rectangles. Compression or label artwork may
        // add a stray component, but must never turn the returned shape into an L/staircase.
        // Select the largest fully-supported contiguous rectangle and reject weak guesses.
        data class RectangleFit(val rowStart: Int, val rowCount: Int, val columnStart: Int, val columnCount: Int) {
            val area: Int get() = rowCount * columnCount
        }
        val fits = buildList {
            for (rowStart in rows.indices) for (rowEnd in rowStart until rows.size) {
                for (columnStart in columns.indices) for (columnEnd in columnStart until columns.size) {
                    val fit = RectangleFit(rowStart, rowEnd - rowStart + 1, columnStart, columnEnd - columnStart + 1)
                    val complete = (rowStart..rowEnd).all { row ->
                        (columnStart..columnEnd).all { column -> ShapeCell(row, column) in observed }
                    }
                    if (complete) add(fit)
                }
            }
        }
        val fit = fits.maxWithOrNull(compareBy<RectangleFit>({ it.area },
            { -abs(it.rowCount - rows.size) - abs(it.columnCount - columns.size) })) ?: return null
        val outside = observed.count { cell ->
            cell.row !in fit.rowStart until fit.rowStart + fit.rowCount ||
                cell.column !in fit.columnStart until fit.columnStart + fit.columnCount
        }
        if (fit.area < 2 || fit.area < observed.size - 2 || outside > max(1, observed.size / 4)) return null
        val occupied = buildSet {
            for (row in 0 until fit.rowCount) for (column in 0 until fit.columnCount) add(ShapeCell(row, column))
        }
        val evidenceQuality = (fit.area.toDouble() / centers.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return ItemShape(fit.rowCount, fit.columnCount, occupied) to (0.55 + evidenceQuality * 0.35)
    }

    private fun footprintProjectionBands(frame: RgbaFrame, region: ScreenRegion, horizontal: Boolean): List<Float> {
        val length = if (horizontal) region.width else region.height
        val cross = if (horizontal) region.height else region.width
        if (length < 4 || cross < 4) return emptyList()
        val projection = IntArray(length)
        for (along in 0 until length) for (other in 0 until cross) {
            val x = if (horizontal) region.left + along else region.left + other
            val y = if (horizontal) region.top + other else region.top + along
            val offset = y * frame.rowStrideBytes + x * 4
            val r = frame.rgba8888[offset].toInt() and 255
            val g = frame.rgba8888[offset + 1].toInt() and 255
            val b = frame.rgba8888[offset + 2].toInt() and 255
            val maximum = max(r, max(g, b)); val minimum = min(r, min(g, b))
            if (maximum < 175 || (maximum - minimum > 65 && b > r)) projection[along]++
        }
        val peak = projection.maxOrNull() ?: return emptyList()
        if (peak < 2) return emptyList()
        val threshold = max(1, (peak * 0.28).roundToInt())
        val bands = mutableListOf<IntRange>()
        var start = -1
        for (index in projection.indices) {
            if (projection[index] >= threshold && start < 0) start = index
            if ((projection[index] < threshold || index == projection.lastIndex) && start >= 0) {
                val end = if (projection[index] >= threshold) index else index - 1
                if (end - start + 1 >= 2) bands += start..end
                start = -1
            }
        }
        if (bands.isEmpty()) return emptyList()
        val centres = bands.map { (it.first + it.last) / 2f }
        if (centres.size >= 3) {
            val gaps = centres.zipWithNext { a, b -> b - a }
            val average = gaps.average()
            if (average <= 0.0 || gaps.any { it !in average * 0.55..average * 1.45 }) return emptyList()
        }
        return centres.map { if (horizontal) region.left + it else region.top + it }
    }

    private fun projectionX(gray: GrayFrame, yStart: Int, yEnd: Int): DoubleArray {
        val result = DoubleArray(gray.width)
        val start = yStart.coerceIn(1, gray.height - 2)
        val end = yEnd.coerceIn(start, gray.height - 2)
        for (x in 1 until gray.width - 1) {
            var total = 0.0
            var count = 0
            for (y in start..end) {
                val center = gray[x, y]
                val left = gray[x - 1, y]
                val right = gray[x + 1, y]
                total += abs(left - right) + abs(center - (left + right) / 2.0)
                count++
            }
            result[x] = total / count.coerceAtLeast(1)
        }
        return result
    }

    private fun projectionY(gray: GrayFrame, xStart: Int, xEnd: Int): DoubleArray {
        val result = DoubleArray(gray.height)
        val start = xStart.coerceIn(1, gray.width - 2)
        val end = xEnd.coerceIn(start, gray.width - 2)
        for (y in 1 until gray.height - 1) {
            var total = 0.0
            var count = 0
            for (x in start..end) {
                val center = gray[x, y]
                val top = gray[x, y - 1]
                val bottom = gray[x, y + 1]
                total += abs(top - bottom) + abs(center - (top + bottom) / 2.0)
                count++
            }
            result[y] = total / count.coerceAtLeast(1)
        }
        return result
    }

    private fun latticeScore(projection: DoubleArray, start: Int, spacing: Int, lineCount: Int): Double {
        var lines = 0.0
        for (index in 0 until lineCount) lines += localMax(projection, start + index * spacing, 1)
        var interiors = 0.0
        for (index in 0 until lineCount - 1) {
            interiors += localMax(projection, start + index * spacing + spacing / 2, 1)
        }
        return lines / lineCount - 0.42 * interiors / (lineCount - 1).coerceAtLeast(1)
    }

    private fun localMax(values: DoubleArray, center: Int, radius: Int): Double {
        var result = 0.0
        for (index in max(0, center - radius)..min(values.lastIndex, center + radius)) {
            result = max(result, values[index])
        }
        return result
    }

    private fun strongestInRange(values: DoubleArray, start: Int, end: Int): Int {
        var bestIndex = start.coerceIn(values.indices)
        var bestValue = Double.NEGATIVE_INFINITY
        for (index in start.coerceIn(values.indices)..end.coerceIn(values.indices)) {
            if (values[index] > bestValue) {
                bestValue = values[index]
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun findBestVerticalLine(frame: RgbaFrame, expected: Int, top: Int, bottom: Int, radius: Int): Int {
        return findVerticalLineObservation(frame, expected, top, bottom, radius, 0).position
    }

    private fun findVerticalLineObservation(
        frame: RgbaFrame, expected: Int, top: Int, bottom: Int, radius: Int, index: Int,
    ): IndexedLine {
        var best = expected.coerceIn(2, frame.width - 3)
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = mutableListOf<Double>()
        for (x in max(2, expected - radius)..min(frame.width - 3, expected + radius)) {
            var score = 0.0
            var count = 0
            for (y in top.coerceAtLeast(2)..bottom.coerceAtMost(frame.height - 3) step 5) {
                val center = frame.luma(x, y)
                val left = frame.luma(x - 2, y)
                val right = frame.luma(x + 2, y)
                score += abs(left - right) +
                    abs(center - (left + right) / 2.0) +
                    (255 - center) * 0.24
                count++
            }
            val average = score / count.coerceAtLeast(1)
            scores += average
            if (average > bestScore) {
                bestScore = average
                best = x
            }
        }
        val baseline = scores.median()
        return IndexedLine(index, best,
            ((bestScore - baseline) / (abs(baseline) + 1.0)).coerceAtLeast(0.0))
    }

    private fun findBestHorizontalLine(frame: RgbaFrame, expected: Int, left: Int, right: Int, radius: Int): Int {
        return findHorizontalLineObservation(frame, expected, left, right, radius, 0).position
    }

    private fun findHorizontalLineObservation(
        frame: RgbaFrame, expected: Int, left: Int, right: Int, radius: Int, index: Int,
    ): IndexedLine {
        var best = expected.coerceIn(2, frame.height - 3)
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = mutableListOf<Double>()
        for (y in max(2, expected - radius)..min(frame.height - 3, expected + radius)) {
            var score = 0.0
            var count = 0
            for (x in left.coerceAtLeast(2)..right.coerceAtMost(frame.width - 3) step 5) {
                val center = frame.luma(x, y)
                val top = frame.luma(x, y - 2)
                val bottom = frame.luma(x, y + 2)
                score += abs(top - bottom) +
                    abs(center - (top + bottom) / 2.0) +
                    (255 - center) * 0.24
                count++
            }
            val average = score / count.coerceAtLeast(1)
            scores += average
            if (average > bestScore) {
                bestScore = average
                best = y
            }
        }
        val baseline = scores.median()
        return IndexedLine(index, best,
            ((bestScore - baseline) / (abs(baseline) + 1.0)).coerceAtLeast(0.0))
    }

    private fun robustLatticeFit(points: List<Int>): Pair<Double, Double> {
        if (points.size < 2) return (points.firstOrNull() ?: 0).toDouble() to 1.0
        val slopes = mutableListOf<Double>()
        for (left in points.indices) for (right in left + 1 until points.size) {
            slopes += (points[right] - points[left]).toDouble() / (right - left)
        }
        val spacing = slopes.median()
        val origin = points.indices.map { points[it] - it * spacing }.median()
        return origin to spacing
    }

    private fun components(
        frame: RgbaFrame,
        region: ScreenRegion,
        predicate: (Int, Int, Int) -> Boolean,
    ): List<Component> {
        val safe = region.clamp(frame.width, frame.height)
        val width = safe.width
        val height = safe.height
        if (width <= 0 || height <= 0) return emptyList()
        val mask = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = (safe.top + y) * frame.rowStrideBytes + (safe.left + x) * 4
            val r = frame.rgba8888[offset].toInt() and 0xFF
            val g = frame.rgba8888[offset + 1].toInt() and 0xFF
            val b = frame.rgba8888[offset + 2].toInt() and 0xFF
            mask[y * width + x] = predicate(r, g, b)
        }
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<Component>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var area = 0
            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                area++
                if (x > 0) visit(current - 1, mask, visited, queue, tail).also { tail = it }
                if (x + 1 < width) visit(current + 1, mask, visited, queue, tail).also { tail = it }
                if (y > 0) visit(current - width, mask, visited, queue, tail).also { tail = it }
                if (y + 1 < height) visit(current + width, mask, visited, queue, tail).also { tail = it }
            }
            result += Component(
                left = safe.left + minX,
                top = safe.top + minY,
                right = safe.left + maxX + 1,
                bottom = safe.top + maxY + 1,
                area = area,
            )
        }
        return result
    }

    private fun visit(
        index: Int,
        mask: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (!mask[index] || visited[index]) return tail
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun cluster(values: List<Float>, tolerance: Float): List<Float> {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Float>>()
        for (value in sorted) {
            val group = groups.lastOrNull()
            if (group == null || abs(group.average().toFloat() - value) > tolerance) {
                groups += mutableListOf(value)
            } else {
                group += value
            }
        }
        return groups.map { it.average().toFloat() }
    }

    private fun crop(frame: RgbaFrame, region: ScreenRegion): RgbaPatch {
        val safe = region.clamp(frame.width, frame.height)
        val bytes = ByteArray(safe.width * safe.height * 4)
        for (row in 0 until safe.height) {
            val source = (safe.top + row) * frame.rowStrideBytes + safe.left * 4
            frame.rgba8888.copyInto(bytes, row * safe.width * 4, source, source + safe.width * 4)
        }
        return RgbaPatch(safe.width, safe.height, bytes)
    }

    private fun relativeRegion(
        parent: ScreenRegion,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ScreenRegion(
        (parent.left + parent.width * left).roundToInt(),
        (parent.top + parent.height * top).roundToInt(),
        (parent.left + parent.width * right).roundToInt(),
        (parent.top + parent.height * bottom).roundToInt(),
    )

    private data class LatticeCandidate(
        val left: Int,
        val top: Int,
        val spacing: Int,
        val score: Double,
        val confidence: Double,
    )

    private data class IndexedLine(val index: Int, val position: Int, val prominence: Double)
    private data class StrictSquareFit(
        val region: ScreenRegion,
        val confidence: Double,
        val anchorCount: Int,
        val grid: SubpixelGridGeometry,
    )
    private data class SquareGridPlacement(
        val region: ScreenRegion,
        val grid: SubpixelGridGeometry,
    )
    private data class BoardSelection(
        val region: ScreenRegion,
        val quality: Double,
        val squareFit: StrictSquareFit? = null,
        val grid: SubpixelGridGeometry? = null,
    )

    private data class TrayLattice(val left: Int, val step: Int, val score: Double)
    private data class TrayCandidate(val region: ScreenRegion, val score: Double)
    private data class TraySelection(
        val region: ScreenRegion,
        val cards: List<ItemCardRecognition>,
        val latticeScore: Double,
        val validCardCount: Int,
        val finishCardCount: Int,
    )

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val area: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private data class BoardEvidence(
        val contentScore: Double,
        val cells: List<BoardCellObservation>,
        val fragmentEdges: List<FragmentEdgeEvidence>,
    )

    private data class CellEvidence(
        val boardLikeRatio: Double,
        val state: BoardCellState,
        val topEdge: Double,
        val rightEdge: Double,
        val bottomEdge: Double,
        val leftEdge: Double,
        val verticalAxisHint: Double,
        val horizontalAxisHint: Double,
        val tileSmoothness: Double,
        val foregroundCoverage: Double,
        val meanR: Double,
        val meanG: Double,
        val meanB: Double,
        val presence: CellPresenceEvidence,
    )

    private class GrayFrame(frame: RgbaFrame) {
        val sourceWidth = frame.width
        val sourceHeight = frame.height
        val scale = ceil(frame.width / 600.0).toInt().coerceAtLeast(1)
        val width = frame.width / scale
        val height = frame.height / scale
        private val pixels = IntArray(width * height)

        init {
            for (y in 0 until height) for (x in 0 until width) {
                pixels[y * width + x] = frame.luma(x * scale + scale / 2, y * scale + scale / 2)
            }
        }

        operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    }

    companion object {
        private const val BOARD_ROWS = 5
        private const val BOARD_COLUMNS = 9
        private const val MIN_BOARD_CONTENT_SCORE = 0.40
        private const val BOARD_ALIAS_QUALITY_MARGIN = 0.055
        private const val MAX_TRAY_CANDIDATES = 4
        private const val TABLET_ASPECT_LIMIT = 1.75
        private const val EXPECTED_BOARD_LEFT_RATIO = 0.47
        private const val EMBEDDED_TABLET_HORIZONTAL_RECOVERY = 0.18f
        private const val STRICT_LINE_SEARCH_RADIUS = 0.22
        private const val STRICT_LINE_PROMINENCE = 0.035
        private const val STRICT_LINE_RESIDUAL_RATIO = 0.055
        private const val STRICT_ANCHOR_CONTRAST = 10.0
        private const val STRICT_MIN_ANCHORS = 4
        private const val STRICT_MIN_CONFIDENCE = 0.72
    }
}

private fun RgbaFrame.luma(x: Int, y: Int): Int {
    val offset = y.coerceIn(0, height - 1) * rowStrideBytes + x.coerceIn(0, width - 1) * 4
    val r = rgba8888[offset].toInt() and 0xFF
    val g = rgba8888[offset + 1].toInt() and 0xFF
    val b = rgba8888[offset + 2].toInt() and 0xFF
    return (r * 77 + g * 150 + b * 29) shr 8
}

private fun ScreenRegion.clamp(width: Int, height: Int) = ScreenRegion(
    left.coerceIn(0, width),
    top.coerceIn(0, height),
    right.coerceIn(0, width),
    bottom.coerceIn(0, height),
)

private fun ScreenRegion.downsample(scale: Int) = ScreenRegion(
    left / scale,
    top / scale,
    (right / scale).coerceAtLeast(left / scale + 1),
    (bottom / scale).coerceAtLeast(top / scale + 1),
)

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}
