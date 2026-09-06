package com.bagridmaster.app.analysis

import com.bagridmaster.app.vision.BoardCellObservation
import com.bagridmaster.app.vision.BoardCellState
import com.bagridmaster.app.vision.FragmentCompletion
import com.bagridmaster.app.vision.legalObjectPlacements

data class PreparedExploration(
    val allowed: Boolean,
    val cells: List<BoardCellObservation>,
    val inventory: List<ItemCardRecognition>,
    val knownLitFootprints: List<List<Set<GridCell>>> = emptyList(),
    val explanation: String,
)

/** All lit objects must be fully coverable this turn. Normalization is local, not persisted. */
class LitObjectExplorationGate {
    fun prepare(board: BoardGeometry, cells: List<BoardCellObservation>, cards: List<ItemCardRecognition>,
                completion: FragmentCompletion): PreparedExploration {
        fun stop(reason: String) = PreparedExploration(false, cells, cards, explanation = reason)
        if (cells.any { it.state == BoardCellState.UNCERTAIN }) {
            return stop("存在待确认格，不把它当作空格或未开格；请重新识别，本轮暂停额外探索")
        }
        val lit = completion.objects.filter { it.phase == BoardObjectPhase.LIT }
        val actualFragments = cells.filter { it.state == BoardCellState.OPEN_FRAGMENT }
            .mapTo(mutableSetOf()) { GridCell(it.row, it.column) }
        if (lit.flatMap { it.observedCells }.toSet() != actualFragments) {
            return stop("保守补开：存在未分组或不一致的点亮格，本轮不探索")
        }
        val byId = completion.fullCompletions.groupBy { it.objectId }
        val unresolved = lit.filter { byId[it.id]?.size != 1 }
        if (unresolved.isNotEmpty()) return stop("情况3/4：${unresolved.joinToString { it.id }} 尚不能完整补开，仅继续翻开点亮物品，不探索")
        if (cards.size != 3 || cards.map { it.index }.distinct().size != cards.size || cards.any {
                !it.isFinished && it.remainingCount != 0 && (it.shape == null || it.remainingCount == null || it.remainingCount < 0)
            }) return stop("物品清单形状或数量不完整，暂不探索")
        val plans = lit.map { byId.getValue(it.id).single() }
        // One physical object may be visible in several disconnected cells while the unopened
        // cells between them still hide its artwork. If independently planned fragments of the
        // same item share an exact legal footprint, count that footprint once. This is deliberately
        // stricter than merely overlapping rectangles: two nearby copies must stay separate.
        data class PhysicalLitObject(
            val members: MutableList<BoardObjectObservation>,
            val itemIndex: Int,
            var footprints: Set<Set<GridCell>>,
        )
        val physical = mutableListOf<PhysicalLitObject>()
        for ((item, plan) in lit.zip(plans)) {
            val candidates = plan.footprints.toSet()
            val existing = physical.firstOrNull { unit ->
                unit.itemIndex == plan.itemIndex && unit.footprints.intersect(candidates).isNotEmpty()
            }
            if (existing == null) physical += PhysicalLitObject(mutableListOf(item), plan.itemIndex, candidates)
            else {
                existing.members += item
                existing.footprints = existing.footprints.intersect(candidates)
            }
        }
        val states = cells.associate { GridCell(it.row, it.column) to it.state }
        val suggested = completion.recommendations.mapTo(mutableSetOf()) { GridCell(it.row, it.column) }
        for (unit in physical) {
            val label = unit.members.joinToString("+") { it.id }
            val observed = unit.members.flatMapTo(mutableSetOf()) { it.observedCells }
            val card = cards.singleOrNull { it.index == unit.itemIndex }
                ?: return stop("保守补开：$label 无法对应物品清单")
            val legal = legalObjectPlacements(board, states, observed, listOf(card)).map { it.cells }.toSet()
            val others = lit.filter { it !in unit.members }.flatMap { it.observedCells }.toSet()
            if (unit.footprints.isEmpty() || unit.footprints.any { footprint ->
                    footprint !in legal || footprint.any { it in others } ||
                        !((observed + suggested).containsAll(footprint))
                }) return stop("保守补开：$label 的完整补开计划与棋盘冲突，不探索")
        }
        val deductions = physical.groupingBy { it.itemIndex }.eachCount()
        val reduced = cards.map { card ->
            val number = deductions[card.index] ?: 0
            if (number == 0) card else {
                val count = card.remainingCount ?: return stop("点亮物品数量无法核对，不探索")
                if (card.isFinished || count < number) return stop("点亮物品数量超过清单剩余数量，不探索")
                card.copy(remainingCount = count - number, isFinished = count == number)
            }
        }
        val fixed = mutableSetOf<GridCell>()
        for (unit in physical.filter { it.footprints.size == 1 }) {
            if (unit.footprints.single().any { it in fixed }) return stop("情况4：多个点亮物品的推定范围冲突，仅补开，不探索")
            fixed += unit.footprints.single()
        }
        val alternatives = physical.filter { it.footprints.size > 1 }.map { unit ->
            unit.footprints.filter { footprint -> footprint.none { it in fixed } }
                .ifEmpty { return stop("情况4：点亮物品摆法无法同时成立，不探索") }
        }
        val normalized = cells.map { cell ->
            if (GridCell(cell.row, cell.column) in fixed) cell.copy(state = BoardCellState.OPEN_OBJECT) else cell
        }
        if (reduced.none { !it.isFinished && (it.remainingCount ?: 0) > 0 }) {
            return PreparedExploration(false, normalized, reduced, alternatives,
                "所有剩余物品均已点亮，本次只补开，不再探索")
        }
        val details = physical.joinToString("；") { unit ->
            val before = cards.single { it.index == unit.itemIndex }.remainingCount
            val after = reduced.single { it.index == unit.itemIndex }.remainingCount
            val ids = unit.members.joinToString("+") { it.id }
            "$ids 合并为同一完整范围，物品${itemCode(unit.itemIndex)}剩余 $before→$after，${unit.footprints.size}种摆法"
        }
        return PreparedExploration(true, normalized, reduced, alternatives,
            if (lit.isEmpty()) "无点亮物品，正常探索" else "情况1/2/4：全部${lit.size}个点亮物品可完整补开，允许另探索一格；$details")
    }
}
