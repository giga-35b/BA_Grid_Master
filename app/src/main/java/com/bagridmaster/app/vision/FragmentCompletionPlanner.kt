package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.*
import kotlin.math.max

data class FragmentCompletion(
    val objects: List<BoardObjectObservation>,
    val recommendations: List<CellRecommendation>,
    val fullCompletions: List<PlannedLitObject> = emptyList(),
    val timings: AnalysisTimings = AnalysisTimings(),
    val boardCells: List<BoardCellObservation> = emptyList(),
    val diagnosticNotes: List<String> = emptyList(),
)

/** Texture alignment first, shape-constrained edge continuation only when alignment is uncertain. */
class FragmentCompletionPlanner {
    fun analyze(frame: RgbaFrame, detection: GameVisionDetection, cards: List<ItemCardRecognition>): FragmentCompletion {
        val objectStarted = System.nanoTime()
        val recognition = BoardObjectRecognizer().recognizeWithCorrections(
            frame, detection.geometry.board, detection.boardCells, cards)
        val objects = recognition.objects
        val boardCells = recognition.cells
        val objectMs = elapsedMillis(objectStarted)
        val matchStarted = System.nanoTime()
        val matcher = LocalTemplateMatcher()
        val matched = objects.map { item ->
            if (item.phase != BoardObjectPhase.LIT) item else item.copy(
                localMatch = matcher.match(frame, detection.geometry.board, boardCells, item, cards))
        }
        val consolidated = mergeDisconnectedLitObjects(
            matched, detection.geometry.board, boardCells, cards, detection.fragmentEdges)
        // A single cell cannot belong to two separately observed objects. Keep conflicts uncertain.
        val checked = consolidated.map { item ->
            val match = item.localMatch
            val best = match?.candidates?.firstOrNull()
            if (match?.accepted == true && best != null && boardCells.any {
                    it.state == BoardCellState.UNCERTAIN && GridCell(it.row, it.column) in best.cells
                }) return@map item.copy(localMatch = match.copy(accepted = false,
                    evidence = "候选范围含待确认格，不能视为可靠完整摆法；请重新识别"))
            if (match?.accepted == true && best != null && consolidated.any { other -> other.id != item.id &&
                (other.observedCells.any { it in best.cells } ||
                    (other.localMatch?.accepted == true && other.localMatch.candidates.first().cells.any { it in best.cells })) }) {
                item.copy(localMatch = match.copy(accepted = false, evidence = "推定范围与其他已见物品冲突，未采用局部位置"))
            } else item
        }
        val matchMs = elapsedMillis(matchStarted)
        val completionStarted = System.nanoTime()
        val completion = complete(detection.geometry.board, boardCells, cards, checked, detection.fragmentEdges)
        return completion.copy(timings = AnalysisTimings(objectRecognitionMs = objectMs,
            placementMatchingMs = matchMs, completionMs = elapsedMillis(completionStarted)), boardCells = boardCells,
            diagnosticNotes = recognition.diagnostics.map { "整体剪影候选：$it" })
    }

    /** Join separated visible pieces only when their shared type, legal shape and measured edge
     * continuations leave one exact footprint. The unopened cells between them are not observed. */
    internal fun mergeDisconnectedLitObjects(
        objects: List<BoardObjectObservation>,
        board: BoardGeometry,
        cells: List<BoardCellObservation>,
        cards: List<ItemCardRecognition>,
        edges: List<FragmentEdgeEvidence>,
    ): List<BoardObjectObservation> {
        val states = cells.associate { GridCell(it.row, it.column) to it.state }
        val result = objects.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (leftIndex in 0 until result.lastIndex) for (rightIndex in leftIndex + 1 until result.size) {
                val left = result[leftIndex]
                val right = result[rightIndex]
                if (left.phase != BoardObjectPhase.LIT || right.phase != BoardObjectPhase.LIT ||
                    left.observedCells.any { it in right.observedCells }) continue
                val commonItem = left.possibleItemIndices.intersect(right.possibleItemIndices.toSet()).singleOrNull()
                    ?: continue
                val observed = left.observedCells + right.observedCells
                val relevantEdges = edges.filter { it.cell in observed }
                if (relevantEdges.size < observed.size) continue
                val placements = legalObjectPlacements(
                    board, states, observed, cards.filter { it.index == commonItem })
                    .filter { edgeCompatible(it, relevantEdges, board) }
                    .distinctBy { it.cells }
                if (placements.size != 1) continue
                val placement = placements.single()
                val rankedCandidate = (left.localMatch?.candidates.orEmpty() + right.localMatch?.candidates.orEmpty())
                    .filter { it.itemIndex == commonItem && it.cells == placement.cells }
                    .maxByOrNull { it.similarity }
                val candidate = rankedCandidate ?: TemplatePlacementMatch(
                    commonItem, placement.origin, placement.rows, placement.columns, 0,
                    minOf(left.confidence, right.confidence).coerceAtLeast(0.75), 1.0, 1.0)
                val mergedIds = "${left.id}+${right.id}"
                result[leftIndex] = left.copy(
                    id = mergedIds,
                    observedCells = left.observedCells + right.observedCells,
                    possibleItemIndices = listOf(candidate.itemIndex),
                    confidence = minOf(left.confidence, right.confidence),
                    evidence = "断裂碎片的共同物品类型、合法形状与真实边缘延伸仅允许一个完整范围，合并为同一物品；${left.evidence}",
                    localMatch = LocalTemplateMatch(
                        accepted = true,
                        candidates = listOf(candidate),
                        margin = 1.0,
                        evidence = "$mergedIds 的共同真实边缘与合法形状唯一确定完整范围",
                    ),
                )
                result.removeAt(rightIndex)
                changed = true
                break@outer
            }
        }
        return result
    }

    /** A strong border continuation must remain inside the footprint; when another direction is
     * strongly present, a near-zero border is treated as background and must stay outside it. */
    private fun edgeCompatible(
        placement: ObjectPlacement,
        evidence: List<FragmentEdgeEvidence>,
        board: BoardGeometry,
    ): Boolean = evidence.all { edge ->
        val directions = listOf(
            Triple(-1, 0, edge.top), Triple(0, 1, edge.right),
            Triple(1, 0, edge.bottom), Triple(0, -1, edge.left),
        )
        val strongest = directions.maxOf { it.third }
        val positiveThreshold = max(0.12, strongest * 0.42)
        directions.all { (dr, dc, score) ->
            val target = GridCell(edge.cell.row + dr, edge.cell.column + dc)
            if (target.row !in 0 until board.rows || target.column !in 0 until board.columns) true
            else when {
                score >= positiveThreshold -> target in placement.cells
                strongest >= 0.25 && score <= 0.06 -> target !in placement.cells
                else -> true
            }
        }
    }

    internal fun complete(
        board: BoardGeometry,
        cells: List<BoardCellObservation>,
        cards: List<ItemCardRecognition>,
        objects: List<BoardObjectObservation>,
        edges: List<FragmentEdgeEvidence>,
    ): FragmentCompletion {
        val states = cells.associate { GridCell(it.row, it.column) to it.state }
        val fullCompletions = mutableListOf<PlannedLitObject>()
        val recommendations = linkedMapOf<GridCell, CellRecommendation>()
        fun suggest(cell: GridCell, score: Double, reason: String) {
            if (states[cell]?.isUnopened != true) return
            val recommendation = CellRecommendation(cell.row, cell.column, 1.0 - score.coerceIn(0.0, 1.0), reason)
            if (recommendation.missProbability < (recommendations[cell]?.missProbability ?: Double.POSITIVE_INFINITY)) recommendations[cell] = recommendation
        }
        val updated = objects.map { item ->
            if (item.phase != BoardObjectPhase.LIT) return@map item
            val ownSuggestions = mutableSetOf<GridCell>()
            fun suggestOwn(cell: GridCell, score: Double, reason: String) {
                if (states[cell]?.isUnopened == true) ownSuggestions += cell
                suggest(cell, score, reason)
            }
            val match = item.localMatch
            val plausible = cards.filter { it.index in item.possibleItemIndices }
            val otherObserved = objects.filter { it.id != item.id }.flatMap { it.observedCells }.toSet()
            var placements = legalObjectPlacements(board, states, item.observedCells, plausible)
                .filter { placement -> placement.cells.none { it in otherObserved } }
            if (placements.isEmpty()) return@map item.copy(completionEvidence = "无可靠形状或合法摆法，暂不强行补全")
            val best = match?.candidates?.firstOrNull()
            if (match?.accepted == true && best != null && best.cells.none { states[it] == BoardCellState.UNCERTAIN } &&
                placements.any { it.itemIndex == best.itemIndex && it.cells == best.cells }) {
                best.cells.forEach { suggestOwn(it, best.similarity, "局部贴图匹配补全 ${item.id}") }
                fullCompletions += PlannedLitObject(item.id, best.itemIndex, listOf(best.cells), "情况1：可靠匹配")
                return@map item.copy(completionEvidence = "情况1：可靠匹配，本次可完整补开；探索将使用该物品的摆法与数量，不改写实际格子状态")
            }
            val evidence = edges.filter { it.cell in item.observedCells }
            val squarePlacements = SquarePlacementResolver.resolve(placements, evidence)
            if (squarePlacements.isNotEmpty() && squarePlacements.map { it.cells }.distinct().size == 1 &&
                squarePlacements.first().cells.none { states[it] == BoardCellState.UNCERTAIN }) {
                val footprint = squarePlacements.first().cells
                footprint.forEach { suggestOwn(it, 0.85, "正方形方向定位补全 ${item.id}") }
                val types = squarePlacements.map { it.itemIndex }.distinct()
                if (types.size == 1) fullCompletions += PlannedLitObject(item.id, types.single(), listOf(footprint), "情况2：正方形延伸方向确定完整摆法")
                val decision = if (types.size == 1) "情况2：正方形方向确定唯一完整摆法，可完整补开"
                    else "情况3：正方形范围可补开，但物品种类不唯一，暂停探索"
                val addresses = footprint.sortedWith(compareBy({ it.row }, { it.column })).joinToString(" ") { cellAddress(it.row, it.column) }
                return@map item.copy(completionEvidence = "$decision；推定占格：$addresses；不依赖贴图旋转；${edgeText(evidence)}")
            }
            // Actual boundary contact outranks the silhouette's principal axis. A curved
            // head may be horizontally wide while its neck continues down through the cell edge.
            val hasContact = evidence.any { maxOf(it.top, it.right, it.bottom, it.left) >= 0.12 }
            fun verticalHint(edge: FragmentEdgeEvidence) = if (hasContact) 0.0 else edge.verticalHint
            fun horizontalHint(edge: FragmentEdgeEvidence) = if (hasContact) 0.0 else edge.horizontalHint
            if (placements.all { minOf(it.rows, it.columns) == 1 }) {
                val vertical = evidence.sumOf { max(it.top, verticalHint(it)) + max(it.bottom, verticalHint(it)) }
                val horizontal = evidence.sumOf { max(it.left, horizontalHint(it)) + max(it.right, horizontalHint(it)) }
                val hasVertical = placements.any { it.rows > 1 }
                val hasHorizontal = placements.any { it.columns > 1 }
                val axis = when {
                    hasVertical && !hasHorizontal -> true
                    hasHorizontal && !hasVertical -> false
                    vertical > horizontal * 1.3 && vertical >= 0.12 -> true
                    horizontal > vertical * 1.3 && horizontal >= 0.12 -> false
                    else -> null
                }
                if (axis == null) return@map item.copy(completionEvidence = "条形物品方向证据冲突，暂不把横向和竖向候选一起推荐")
                placements = placements.filter { if (axis) it.columns == 1 else it.rows == 1 }
            }
            val directionalTargets = mutableSetOf<GridCell>()
            for (edge in evidence) {
                val directions = listOf(
                    Triple(-1, 0, max(edge.top, verticalHint(edge))), Triple(0, 1, max(edge.right, horizontalHint(edge))),
                    Triple(1, 0, max(edge.bottom, verticalHint(edge))), Triple(0, -1, max(edge.left, horizontalHint(edge))))
                val threshold = max(0.12, directions.maxOf { it.third } * 0.42)
                val accepted = directions.filter { (dr, dc, score) ->
                    val target = GridCell(edge.cell.row + dr, edge.cell.column + dc)
                    score >= threshold && states[target]?.isUnopened == true && placements.any { target in it.cells }
                }
                accepted.forEach { (dr, dc, score) ->
                    val target = GridCell(edge.cell.row + dr, edge.cell.column + dc)
                    directionalTargets += target
                    suggestOwn(target, score, "形状约束延伸 ${item.id}")
                }
                for (v in accepted.filter { it.first != 0 }) for (h in accepted.filter { it.second != 0 }) {
                    val target = GridCell(edge.cell.row + v.first, edge.cell.column + h.second)
                    val verticalCell = GridCell(edge.cell.row + v.first, edge.cell.column)
                    val horizontalCell = GridCell(edge.cell.row, edge.cell.column + h.second)
                    // Both directions AND their diagonal must fit the same physical footprint.
                    if (placements.any { target in it.cells && verticalCell in it.cells && horizontalCell in it.cells }) {
                        suggestOwn(target, minOf(v.third, h.third) * 0.85, "形状约束对角补全 ${item.id}")
                    }
                }
            }
            // Positive continuation directions can constrain the footprint (e.g. a 2x2 corner).
            // If they conflict, keep all hypotheses rather than selecting whichever one happens
            // to fit the recommendation count. Every surviving footprint must be fully covered.
            val directionalPlacements = placements.filter { it.cells.containsAll(directionalTargets) }
            val possible = directionalPlacements.ifEmpty { placements }
            val coverage = item.observedCells + ownSuggestions
            val types = possible.map { it.itemIndex }.distinct()
            val complete = types.size == 1 && possible.all { coverage.containsAll(it.cells) }
            if (complete) fullCompletions += PlannedLitObject(item.id, types.single(),
                possible.map { it.cells }.distinct(), "情况2：延伸完整覆盖")
            val edgeText = edgeText(evidence)
            val decision = if (complete) "情况2：本次延伸覆盖完整物品（${possible.size}种合法摆法）"
                else "情况3：延伸尚不能保证完整翻开，或种类未确定；本轮禁止额外探索"
            item.copy(completionEvidence = "$decision；局部匹配未采纳，使用真实边界/主轴 + 合法形状约束；$edgeText")
        }
        return FragmentCompletion(updated, recommendations.values.toList(), fullCompletions)
    }

    private fun percent(value: Double) = "${(value * 100).toInt()}%"

    private fun edgeText(evidence: List<FragmentEdgeEvidence>) = evidence.joinToString("；") {
        "${cellAddress(it.cell.row, it.cell.column)}边界接触 上${percent(it.top)} 右${percent(it.right)} 下${percent(it.bottom)} 左${percent(it.left)}"
    }
}
