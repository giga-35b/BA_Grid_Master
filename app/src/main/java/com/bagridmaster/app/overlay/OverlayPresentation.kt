package com.bagridmaster.app.overlay

import com.bagridmaster.app.analysis.*
import com.bagridmaster.app.model.OverlayContentMode
import kotlin.math.roundToInt

fun CellRecommendation.displayAddress(): String = cellAddress(row, column) + if (isExploration) "*" else ""

data class InventoryDisplay(val index: Int, val shape: String, val remaining: Int?, val total: Int?) {
    val label: String get() = "${itemCode(index)}：$shape，${remaining ?: "?"}/${total ?: "?"}"
}

/** Totals come only from compatible catalog configurations, never from a remaining count alone. */
fun AnalysisResult.inventoryDisplay(): List<InventoryDisplay> {
    val cards = inventory.distinctBy { it.index }.sortedBy { it.index }
    val configurations = InventoryConfiguration.entries.filter { config ->
        cards.isNotEmpty() && cards.all { card ->
            val expected = config.items.getOrNull(card.index) ?: return@all false
            val shapeMatches = card.shape?.let {
                (minOf(it.rows, it.columns) to maxOf(it.rows, it.columns)) == expected.normalizedSize
            } ?: true
            val completed = boardObjects.count { item ->
                item.phase == BoardObjectPhase.COMPLETED && item.possibleItemIndices == listOf(card.index) &&
                    item.observedCells.size == expected.rows * expected.columns
            }
            shapeMatches && (card.remainingCount ?: 0) >= 0 &&
                (card.remainingCount ?: 0) + completed <= expected.count
        }
    }
    return cards.map { card ->
        val total = configurations.map { it.items[card.index].count }.distinct().singleOrNull()
        // Keep shape unknown if CV did not read it. Catalog agreement is not a new CV observation.
        InventoryDisplay(card.index, card.shape?.let { "${it.columns}×${it.rows}" } ?: "形状?",
            if (card.isFinished) 0 else card.remainingCount, total)
    }
}

/** Hypothesized upper-left for lit objects is shown only when a full placement is accepted. */
fun recognizedObjectList(objects: List<BoardObjectObservation>): String {
    data class Entry(val item: BoardObjectObservation, val type: Int?, val footprint: Set<GridCell>, val located: Boolean) {
        val top = footprint.minOf { it.row }
        val left = footprint.minOf { it.column }
    }
    val entries = objects.filter { it.observedCells.isNotEmpty() }.map { item ->
        val accepted = item.localMatch?.takeIf { it.accepted }?.candidates?.firstOrNull()
        val footprint = when {
            item.phase == BoardObjectPhase.COMPLETED -> item.observedCells
            item.estimatedFootprint.isNotEmpty() -> item.estimatedFootprint
            accepted != null -> accepted.cells
            else -> item.observedCells
        }
        val rectangular = footprint.size ==
            (footprint.maxOf { it.row } - footprint.minOf { it.row } + 1) *
            (footprint.maxOf { it.column } - footprint.minOf { it.column } + 1)
        Entry(item, accepted?.itemIndex ?: item.possibleItemIndices.singleOrNull(), footprint,
            rectangular && (item.phase == BoardObjectPhase.COMPLETED || accepted != null || item.estimatedFootprint.isNotEmpty()))
    }.sortedWith(compareBy({ it.type ?: Int.MAX_VALUE }, { it.top }, { it.left }, { it.item.id }))
    val counts = mutableMapOf<Int?, Int>()
    return entries.joinToString("、") { entry ->
        val number = (counts[entry.type] ?: 0) + 1
        counts[entry.type] = number
        val location = if (entry.located) {
            val rows = entry.footprint.maxOf { it.row } - entry.top + 1
            val columns = entry.footprint.maxOf { it.column } - entry.left + 1
            cellAddress(entry.top, entry.left) + when { columns > rows -> "横"; rows > columns -> "纵"; else -> "方" }
        } else "左上? 已见" + entry.item.observedCells.sortedWith(compareBy({ it.row }, { it.column }))
            .joinToString("/") { cellAddress(it.row, it.column) }
        "[${entry.type?.let(::itemCode) ?: "?"}$number $location]"
    }.ifEmpty { "无" }
}

fun overlayResultText(result: AnalysisResult, mode: OverlayContentMode): String = buildList {
    if (mode == OverlayContentMode.BUTTON_ONLY) return ""
    if (mode == OverlayContentMode.FULL) {
        val region = result.geometry.board.region
        add("棋盘：(${region.left},${region.top})–(${region.right},${region.bottom})")
        addAll(result.inventoryDisplay().map { it.label })
        add("已识别清单：${recognizedObjectList(result.boardObjects)}")
    }
    add("建议：" + result.recommendations.joinToString(" ") { it.displayAddress() }.ifEmpty { "无" })
    val exploration = result.recommendations.filter { it.isExploration && it.missProbability.isFinite() }
    if (exploration.isNotEmpty()) add("探索置信度（估计）：" + exploration.joinToString(" ") {
        "${it.displayAddress()} ${((1.0 - it.missProbability).coerceIn(0.0, 1.0) * 100).roundToInt()}%"
    })
    add(result.timingSummary())
}.joinToString("\n")

/** Anchors follow the very same image-to-overlay board transform (including insets). */
fun inventoryOverlayAnnotations(result: AnalysisResult, overlayBoard: BoardGeometry): List<KnownObjectAnnotation> {
    val original = result.geometry.board.region
    if (original.width <= 0 || original.height <= 0) return emptyList()
    val sx = overlayBoard.region.width.toDouble() / original.width
    val sy = overlayBoard.region.height.toDouble() / original.height
    fun x(value: Int) = (overlayBoard.region.left + (value - original.left) * sx).roundToInt()
    fun y(value: Int) = (overlayBoard.region.top + (value - original.top) * sy).roundToInt()
    val labels = result.inventoryDisplay().associateBy { it.index }
    return result.inventory.mapNotNull { card ->
        val label = labels[card.index] ?: return@mapNotNull null
        KnownObjectAnnotation(ScreenRegion(x(card.cardRegion.left), y(card.spriteRegion.bottom),
            x(card.cardRegion.right), y(card.cardRegion.bottom)), label.label, 0xFFCCFFF4.toInt())
    }
}

/** Final badge clamping uses the actual overlay viewport, not assumed screenshot dimensions. */
fun inventoryBadgeBounds(anchor: ScreenRegion, width: Int, height: Int, viewWidth: Int, viewHeight: Int): ScreenRegion {
    val w = width.coerceIn(0, viewWidth.coerceAtLeast(0))
    val h = height.coerceIn(0, viewHeight.coerceAtLeast(0))
    val left = ((anchor.left + anchor.right - w) / 2).coerceIn(0, (viewWidth - w).coerceAtLeast(0))
    val top = anchor.top.coerceIn(0, (viewHeight - h).coerceAtLeast(0))
    return ScreenRegion(left, top, left + w, top + h)
}
