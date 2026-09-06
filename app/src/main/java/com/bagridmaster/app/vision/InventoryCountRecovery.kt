package com.bagridmaster.app.vision

import com.bagridmaster.app.analysis.ItemCardRecognition
import com.bagridmaster.app.analysis.itemCode

internal data class InventoryCountRecovery(
    val inventory: List<ItemCardRecognition>,
    val note: String? = null,
)

/**
 * A deliberately narrow experience fallback for a missed count of one.
 *
 * The displayed inventory contains only remaining objects, so completed silhouette cells are
 * added back before checking the historically common 30-cell total. Known non-30 combinations
 * and ambiguous cases are left untouched.
 */
internal fun recoverSingleMissingCountFromThirtyCells(
    cards: List<ItemCardRecognition>,
    completedCellCount: Int,
): InventoryCountRecovery {
    val active = cards.filterNot { it.isFinished }
    val unknown = active.filter { it.remainingCount == null }
    if (unknown.size != 1) return InventoryCountRecovery(cards)
    if (active.any { it.shape == null || (it.remainingCount != null && it.remainingCount < 0) }) {
        return InventoryCountRecovery(cards)
    }

    val missing = unknown.single()
    val missingCells = missing.shape?.cellCount ?: return InventoryCountRecovery(cards)
    val knownRemainingCells = active.sumOf { card ->
        if (card.index == missing.index) 0 else card.shape!!.cellCount * (card.remainingCount ?: 0)
    }
    val assumedTotal = knownRemainingCells + completedCellCount + missingCells
    if (assumedTotal != EXPERIENCE_TOTAL_CELLS) return InventoryCountRecovery(cards)

    val evidence = "经验修正：已知剩余物品${knownRemainingCells}格 + 完成剪影${completedCellCount}格 + " +
        "假设物品${itemCode(missing.index)}为1件的${missingCells}格 = ${EXPERIENCE_TOTAL_CELLS}格；数量按1参与运算"
    return InventoryCountRecovery(
        inventory = cards.map { card ->
            if (card.index == missing.index) card.copy(
                remainingCount = 1,
                countEvidence = listOf(card.countEvidence, evidence).filter { it.isNotBlank() }.joinToString("；"),
            ) else card
        },
        note = evidence,
    )
}

private const val EXPERIENCE_TOTAL_CELLS = 30
